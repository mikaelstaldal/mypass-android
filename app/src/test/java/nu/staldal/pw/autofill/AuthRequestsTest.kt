package nu.staldal.pw.autofill

import org.junit.Assert.*
import org.junit.Test

class AuthRequestsTest {
    private val approved = AuthDestination("browser", "example.com", "user", "password", "password")

    @Test fun destinationMustStillBeApproved() {
        assertTrue(approved.accepts("browser", true, "https", "EXAMPLE.COM", "user", "password"))
        assertFalse(approved.accepts("other.browser", true, "https", "example.com", "user", "password"))
        assertFalse(approved.accepts("browser", false, "https", "example.com", "user", "password"))
        assertFalse(approved.accepts("browser", true, "http", "example.com", "user", "password"))
        assertFalse(approved.accepts("browser", true, null, "example.com", "user", "password"))
        assertFalse(approved.accepts("browser", true, "https", "other.example.com", "user", "password"))
        assertFalse(approved.accepts("browser", true, "https", "example.com", "other", "password"))
        assertFalse(approved.accepts("browser", true, "https", "example.com", null, "password"))
        assertFalse(approved.accepts("browser", true, "https", "example.com", "user", "other"))
        assertFalse(approved.accepts("browser", true, "https", "example.com", "user", null))
    }

    @Test fun localHttpExceptionStillRequiresTheApprovedHost() {
        val local = approved.copy(host = "localhost")
        assertTrue(local.accepts("browser", true, "http", "localhost", "user", "password"))
        assertFalse(local.accepts("browser", true, "http", "127.0.0.1", "user", "password"))
    }

    @Test fun concurrentRequestsAreIndependentAndOneUse() {
        val store = AuthRequestStore<String>()
        val first = store.register(approved)
        val other = approved.copy(host = "other.example.com")
        val second = store.register(other)
        assertNotEquals(first, second)
        assertEquals(approved, store.get(first))
        assertEquals(approved, store.take(first))
        assertNull(store.take(first))
        assertEquals(other, store.take(second))
        assertNull(store.get("unknown"))
    }

    @Test fun backingOutAllowsRetryUntilCredentialRelease() {
        val store = AuthRequestStore<String>()
        val token = store.register(approved)
        assertEquals(approved, store.get(token)) // first launch, user backs out
        assertEquals(approved, store.get(token)) // retry or configuration change
        assertEquals(approved, store.take(token)) // successful release
        assertNull(store.get(token))
    }

    @Test fun cancellationBeforePublicationAndExpiryFailClosed() {
        var time = 0L
        val store = AuthRequestStore<String>(now = { time }, lifetimeMillis = 100)
        val cancelled = store.register(approved)
        val expired = store.register(approved)
        store.cancel(cancelled)
        assertNull(store.take(cancelled))
        time = 100
        assertNull(store.get(expired))
        assertNull(store.take(expired))
        assertNull(AuthRequestStore<String>().get(expired)) // process restart
    }

    @Test fun changedDestinationIsRejectedAtRelease() {
        val store = AuthRequestStore<String>()
        val token = store.register(approved)
        val before = requireNotNull(store.get(token))
        assertTrue(before.accepts("browser", true, "https", "example.com", "user", "password"))
        val release = requireNotNull(store.take(token))
        assertFalse(release.accepts("browser", true, "https", "evil.example", "user", "password"))
        assertNull(store.take(token))
    }

    @Test fun abandonedRequestsAreBounded() {
        val store = AuthRequestStore<String>(capacity = 2)
        val first = store.register(approved)
        val second = store.register(approved)
        val third = store.register(approved)
        assertNull(store.get(first))
        assertEquals(approved, store.get(second))
        assertEquals(approved, store.get(third))
    }
}
