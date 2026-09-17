package nu.staldal.pw.autofill

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Modifier

class FillDiagnosticsTest {

    @Before fun reset() {
        FillDiagnostics.setEnabled(false)
        FillDiagnostics.setDiagnosticSink(null)
    }

    @After fun tearDown() {
        FillDiagnostics.setEnabled(false)
        FillDiagnostics.setDiagnosticSink(null)
    }

    private fun record(host: String) = FillDiagnostics.record(
        "com.example.browser", FillOutcome.NO_MATCHING_ENTRY, false, "https", host, 2)

    @Test fun recordsNothingUntilSwitchedOn() {
        record("example.com")
        assertTrue(FillDiagnostics.records.value.isEmpty())
        assertFalse(FillDiagnostics.enabled)

        FillDiagnostics.setEnabled(true)
        record("example.com")
        assertEquals(1, FillDiagnostics.records.value.size)
    }

    @Test fun switchingOffForgetsWhatWasRecorded() {
        FillDiagnostics.setEnabled(true)
        record("example.com")
        FillDiagnostics.setEnabled(false)
        assertTrue(FillDiagnostics.records.value.isEmpty())
    }

    @Test fun emitsToTheDiagnosticSinkOnlyWhileEnabled() {
        val emitted = mutableListOf<FillRecord>()
        FillDiagnostics.setDiagnosticSink(emitted::add)
        record("before.example")
        FillDiagnostics.setEnabled(true)
        record("enabled.example")
        FillDiagnostics.setDiagnosticSink(null)
        record("no-sink.example")
        FillDiagnostics.setEnabled(false)
        record("after.example")
        assertEquals(listOf("enabled.example"), emitted.map { it.host })
    }

    @Test fun logMessageContainsOnlyTheRecordedMetadata() {
        val record = FillRecord(
            "com.example.browser", FillOutcome.INELIGIBLE_ORIGIN,
            false, null, "example.com", 2,
        )
        assertEquals(
            "outcome=INELIGIBLE_ORIGIN package=\"com.example.browser\" " +
                "compatibilityMode=false " +
                "scheme=(none) host=\"example.com\" classifiedFields=2",
            FillDiagnostics.logMessage(record),
        )
    }

    /**
     * The flag is the framework's, not the browser's, so it is the one piece of
     * a record that needs no escaping — and the one a reader needs to see to
     * know why an accessibility-derived request classified nothing.
     */
    @Test fun logMessageReportsACompatibilityModeRequest() {
        val message = FillDiagnostics.logMessage(FillRecord(
            "com.example.browser", FillOutcome.NO_CLASSIFIED_FIELD,
            true, null, null, 0,
        ))
        assertEquals(
            "outcome=NO_CLASSIFIED_FIELD package=\"com.example.browser\" " +
                "compatibilityMode=true scheme=(none) host=(none) classifiedFields=0",
            message,
        )
    }

    @Test fun logMessageEscapesUntrustedMetadataOntoOneLine() {
        val message = FillDiagnostics.logMessage(FillRecord(
            "com.example\\\"browser", FillOutcome.INELIGIBLE_ORIGIN,
            false, "ht\ttp", "first.example\r\nI/pw-autofill: outcome=OFFERED", 1,
        ))
        assertEquals(
            "outcome=INELIGIBLE_ORIGIN package=\"com.example\\\\\\\"browser\" " +
                "compatibilityMode=false " +
                "scheme=\"ht\\ttp\" " +
                "host=\"first.example\\r\\nI/pw-autofill: outcome=OFFERED\" " +
                "classifiedFields=1",
            message,
        )
        assertFalse(message.contains('\n'))
        assertFalse(message.contains('\r'))
    }

    @Test fun keepsTheMostRecentRequestsFirst() {
        FillDiagnostics.setEnabled(true)
        repeat(FillDiagnostics.CAPACITY + 3) { record("host-$it.example") }
        val records = FillDiagnostics.records.value
        assertEquals(FillDiagnostics.CAPACITY, records.size)
        assertEquals("host-${FillDiagnostics.CAPACITY + 2}.example", records.first().host)
        assertEquals("host-3.example", records.last().host)
    }

    @Test fun clearingLeavesRecordingOn() {
        FillDiagnostics.setEnabled(true)
        record("example.com")
        FillDiagnostics.clear()
        assertTrue(FillDiagnostics.records.value.isEmpty())
        assertTrue(FillDiagnostics.enabled)
        record("example.com")
        assertEquals(1, FillDiagnostics.records.value.size)
    }

    @Test fun aRecordCarriesTheDecisionAndNothingFromTheForm() {
        FillDiagnostics.setEnabled(true)
        record("example.com")
        val record = FillDiagnostics.records.value.single()
        assertEquals("com.example.browser", record.browserPackage)
        assertEquals(FillOutcome.NO_MATCHING_ENTRY, record.outcome)
        assertEquals("https", record.scheme)
        assertEquals("example.com", record.host)
        assertEquals(2, record.classifiedFields)
        assertFalse(record.compatibilityMode)
        // Named rather than counted, so that adding somewhere to put a field
        // value or an entry name has to be a deliberate edit to this list.
        assertEquals(
            setOf("browserPackage", "outcome", "compatibilityMode", "scheme", "host",
                "classifiedFields"),
            FillRecord::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }.map { it.name }.toSet(),
        )
    }

    /** Every refusal the selector can reach has something to show for it. */
    @Test fun everySelectorRefusalMapsToAnOutcome() {
        for (refusal in FormRefusal.entries) {
            val outcome = FillDiagnostics.outcomeOf(refusal)
            assertTrue(outcome.description.isNotBlank())
            assertFalse(outcome == FillOutcome.OFFERED)
        }
    }

    /**
     * The description is the whole user-facing point of an outcome — a record
     * that cannot say what it means explains nothing — and the screen shows it
     * verbatim, so a new outcome must bring one.
     */
    @Test fun everyOutcomeSaysWhatItMeans() {
        for (outcome in FillOutcome.entries) {
            assertTrue(outcome.name, outcome.description.isNotBlank())
        }
    }
}
