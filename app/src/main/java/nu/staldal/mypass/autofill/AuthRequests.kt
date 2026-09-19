package nu.staldal.mypass.autofill

import nu.staldal.mypass.data.Matching
import java.util.UUID

/** Approved destinations stay in this process, never in mutable intent extras. */
internal data class AuthDestination<Id>(
    val packageName: String,
    val host: String,
    val usernameId: Id?,
    val passwordId: Id,
    val focusedId: Id?,
    /**
     * How the request that raised this offer reached us. Provenance rather
     * than destination, but the offer is rebuilt after the unlock and the
     * framework tells a service that only once, on the fill request — so this
     * is the only thing that survives the round trip to carry it.
     */
    val compatibilityMode: Boolean = false,
) {
    fun accepts(packageName: String?, trusted: Boolean, scheme: String?, domain: String?,
                usernameId: Id?, passwordId: Id?): Boolean =
        trusted && packageName == this.packageName &&
            Matching.eligibleWebHost(scheme, domain) == host &&
            usernameId == this.usernameId && passwordId == this.passwordId
}

/** Bounded, expiring, one-use requests. Process death deliberately fails closed. */
internal class AuthRequestStore<Id>(
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val lifetimeMillis: Long = 5 * 60 * 1000,
    private val capacity: Int = 128,
) : ExpiringRequestStore<AuthDestination<Id>>(now, lifetimeMillis, capacity)

/** Android-free bounded store shared by every autofill authentication round trip. */
internal open class ExpiringRequestStore<T>(
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val lifetimeMillis: Long = 5 * 60 * 1000,
    private val capacity: Int = 128,
) {
    init { require(lifetimeMillis > 0); require(capacity > 0) }
    private data class Request<T>(val value: T, val created: Long)
    private val requests = linkedMapOf<String, Request<T>>()

    @Synchronized fun register(value: T): String {
        prune()
        while (requests.size >= capacity) requests.remove(requests.keys.first())
        return UUID.randomUUID().toString().also { requests[it] = Request(value, now()) }
    }
    @Synchronized fun get(token: String): T? { prune(); return requests[token]?.value }
    @Synchronized fun take(token: String): T? { prune(); return requests.remove(token)?.value }
    @Synchronized fun cancel(token: String) { requests.remove(token) }
    private fun prune() {
        val time = now()
        requests.entries.removeAll { time - it.value.created >= lifetimeMillis }
    }
}
