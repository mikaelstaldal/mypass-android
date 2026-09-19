package nu.staldal.mypass.autofill

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What became of a fill request. Every value but [OFFERED], [UNLOCK_OFFERED],
 * [ENROLLMENT_OFFERED], [NO_VAULT] and [REQUEST_CANCELLED] is a refusal, and the refusals are the
 * point: the autofill framework gives a service no way to say "I declined, and
 * here is why", so from the outside every one of them looks identical —
 * nothing appears.
 */
enum class FillOutcome(val description: String) {
    OFFERED("Offered matching entries."),
    UNLOCK_OFFERED("Offered to unlock the vault."),
    ENROLLMENT_OFFERED("Offered to review an unrecognized browser publisher."),
    NO_VAULT("No vault on this device yet."),
    REQUEST_CANCELLED("The framework withdrew the request before MyPass could answer it."),
    NO_STRUCTURE("The framework sent no view structure."),
    UNTRUSTED_BROWSER("Not a browser with a pinned or enrolled signing certificate."),
    NO_CLASSIFIED_FIELD("No field on the page looked like a username or password."),
    NO_FOCUSED_FIELD("Could not tell which field the request was for."),
    INELIGIBLE_ORIGIN("The origin the browser reported is not fillable — see the scheme and host below."),
    NO_SHARED_CONTAINER("The browser reported no form or container around the focused field."),
    NO_PASSWORD_FIELD("No password field in the same form as the focused field."),
    AMBIGUOUS_PASSWORD("Several password fields in that form — focus the one to fill."),
    NO_MATCHING_ENTRY("No entry has a url matching this host."),
}

/**
 * One request, as the diagnostic remembers it.
 *
 * Deliberately only the decision and what the decision was made from: the
 * requesting package, whether its signing certificate was accepted, the origin
 * the browser claimed, and how many fields were classified. **No field
 * contents, no entry names, no passphrase state** — nothing that a screenshot
 * of the diagnostic could leak beyond the fact that a page was visited.
 */
data class FillRecord(
    val browserPackage: String?,
    val outcome: FillOutcome,
    /** Whether Android derived this request through autofill compatibility mode. */
    val compatibilityMode: Boolean = false,
    /** Scheme as the browser reported it, before any eligibility rule. */
    val scheme: String? = null,
    /** Host as the browser reported it, before normalization. */
    val host: String? = null,
    /** Fields [FieldClassifier] recognised anywhere in the structure. */
    val classifiedFields: Int = 0,
)

/**
 * An opt-in, in-memory record of why recent fill requests produced no offer.
 *
 * Every refusal in this package is silent by design — a service that cannot
 * answer returns `null` and the keyboard simply shows nothing — which leaves a
 * user with a browser that does not fill and no way to tell whether the cause
 * is the browser's own autofill setting, an unpinned signing certificate, a
 * missing scheme, or an entry with no `url`. Each of those has a different fix
 * and they are indistinguishable from the outside.
 *
 * Off by default, and deliberately weak as a store: the last few records live
 * in this process's memory and are also sent to an application-supplied
 * diagnostic sink while enabled. They are cleared when switched off and lost
 * when the process dies.
 */
object FillDiagnostics {

    /** How many requests are remembered. A page load can raise several. */
    const val CAPACITY = 8

    private val _records = MutableStateFlow<List<FillRecord>>(emptyList())

    /**
     * Guards the switch and the records *together*. Checking [enabled] and
     * appending have to be one step: a request that passed the check before
     * the switch went off would otherwise commit its record after the clear,
     * leaving a host on a screen that says recording is off.
     */
    private val lock = Any()

    /** Android supplies Logcat here without making this class Android-bound. */
    private var diagnosticSink: ((FillRecord) -> Unit)? = null

    /** Most recent first. */
    val records: StateFlow<List<FillRecord>> = _records.asStateFlow()

    /**
     * Read on the autofill service's thread on every request, written from the
     * settings collector. Volatile so a reader outside [lock] — the settings
     * screen, a caller deciding whether to bother — sees the current value;
     * writes still happen under it.
     */
    @Volatile
    var enabled: Boolean = false
        private set

    /** Switching off also forgets what was already recorded. */
    fun setEnabled(value: Boolean) = synchronized(lock) {
        enabled = value
        if (!value) _records.value = emptyList()
    }

    fun clear() = synchronized(lock) {
        _records.value = emptyList()
    }

    /**
     * Installs the process-wide sink; kept separate so unit tests stay pure JVM.
     * The sink runs under [lock], so it must be non-blocking and must not call
     * back into this object.
     */
    fun setDiagnosticSink(sink: ((FillRecord) -> Unit)?) = synchronized(lock) {
        diagnosticSink = sink
    }

    fun record(record: FillRecord) = synchronized(lock) {
        if (enabled) {
            _records.value = (listOf(record) + _records.value).take(CAPACITY)
            diagnosticSink?.invoke(record)
        }
    }

    /** Stable, single-line representation shared by Logcat and its unit test. */
    fun logMessage(record: FillRecord): String =
        "outcome=${record.outcome.name} " +
            "package=${logValue(record.browserPackage)} " +
            "compatibilityMode=${record.compatibilityMode} " +
            "scheme=${logValue(record.scheme)} " +
            "host=${logValue(record.host)} " +
            "classifiedFields=${record.classifiedFields}"

    /** Quotes untrusted structure metadata and prevents it from forging log lines. */
    private fun logValue(value: String?): String {
        if (value == null) return "(none)"
        return buildString {
            append('"')
            for (character in value) when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.isISOControl()) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else append(character)
            }
            append('"')
        }
    }

    /** The service's convenience spelling, so each call site stays one line. */
    fun record(
        browserPackage: String?,
        outcome: FillOutcome,
        compatibilityMode: Boolean = false,
        scheme: String? = null,
        host: String? = null,
        classifiedFields: Int = 0,
    ) = record(FillRecord(browserPackage, outcome, compatibilityMode, scheme, host, classifiedFields))

    /** What [FormSelector] refused for, in this vocabulary. */
    fun outcomeOf(refusal: FormRefusal): FillOutcome = when (refusal) {
        FormRefusal.NO_CLASSIFIED_FIELD -> FillOutcome.NO_CLASSIFIED_FIELD
        FormRefusal.NO_FOCUSED_FIELD -> FillOutcome.NO_FOCUSED_FIELD
        FormRefusal.INELIGIBLE_ORIGIN -> FillOutcome.INELIGIBLE_ORIGIN
        FormRefusal.NO_SHARED_CONTAINER -> FillOutcome.NO_SHARED_CONTAINER
        FormRefusal.NO_PASSWORD_FIELD -> FillOutcome.NO_PASSWORD_FIELD
        FormRefusal.AMBIGUOUS_PASSWORD -> FillOutcome.AMBIGUOUS_PASSWORD
    }
}
