package nu.staldal.pw.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormSelectorTest {
    private fun field(id: String, kind: FieldKind, focused: Boolean = false,
                      container: Any? = "form", domain: String? = "example.com",
                      scheme: String? = "https", value: String? = null) =
        FieldCandidate(kind, id, scheme, domain, value, focused, container)

    private fun assertEmpty(fields: List<FieldCandidate<String>>) {
        assertEquals(SelectedForm<String>(null, null, null, null, null, null), FormSelector.select(fields))
    }

    @Test fun missingOrMultipleFocusIsRefused() {
        assertEmpty(emptyList())
        assertEmpty(listOf(field("u", FieldKind.USERNAME), field("p", FieldKind.PASSWORD)))
        assertEmpty(listOf(field("u", FieldKind.USERNAME, true), field("p", FieldKind.PASSWORD, true)))
    }

    @Test fun focusedUsernameCannotSelectAnotherFrame() {
        assertEmpty(listOf(field("u", FieldKind.USERNAME, true),
            field("p", FieldKind.PASSWORD, domain = "other.example")))
        // Even same-origin frames have different container identities.
        assertEmpty(listOf(field("u", FieldKind.USERNAME, true, "frame-a"),
            field("p", FieldKind.PASSWORD, container = "frame-b")))
    }

    @Test fun usernameOnlyFormCannotBorrowAdjacentPassword() {
        assertEmpty(listOf(field("u", FieldKind.USERNAME, true, "username-only"),
            field("p", FieldKind.PASSWORD, container = "login")))
    }

    @Test fun focusSelectsTheEnclosingFormInEitherFieldOrder() {
        for (reverse in listOf(false, true)) {
            val selectedFields = listOf(field("u", FieldKind.USERNAME, true, "registration"),
                field("p", FieldKind.PASSWORD, container = "registration"))
            val form = FormSelector.select(listOf(field("other-u", FieldKind.USERNAME),
                field("other-p", FieldKind.PASSWORD)) + if (reverse) selectedFields.reversed() else selectedFields)
            assertEquals("u", form.usernameId)
            assertEquals("p", form.passwordId)
        }
    }

    @Test fun ambiguousPasswordsAreRefused() {
        assertEmpty(listOf(field("u", FieldKind.USERNAME, true),
            field("p1", FieldKind.PASSWORD), field("p2", FieldKind.PASSWORD)))
    }

    @Test fun focusedPasswordDoesNotGuessAmongUsernames() {
        val form = FormSelector.select(listOf(field("u1", FieldKind.USERNAME),
            field("u2", FieldKind.USERNAME), field("p", FieldKind.PASSWORD, true)))
        assertNull(form.usernameId)
        assertEquals("p", form.passwordId)
    }

    @Test fun crossOriginUsernameIdAndValueAreDropped() {
        for (username in listOf(field("u", FieldKind.USERNAME, domain = "evil.example", value = "attacker"),
            field("u", FieldKind.USERNAME, scheme = "http", value = "attacker"),
            field("u", FieldKind.USERNAME, container = "other", value = "attacker"))) {
            val form = FormSelector.select(listOf(username, field("p", FieldKind.PASSWORD, true, value = "secret")))
            assertNull(form.usernameId)
            assertNull(form.usernameValue)
            assertEquals("secret", form.passwordValue)
            assertEquals("example.com", form.webDomain)
        }
    }

    @Test fun missingOrIneligiblePasswordOriginIsNeverSynthesized() {
        for (password in listOf(field("p", FieldKind.PASSWORD, true, scheme = null),
            field("p", FieldKind.PASSWORD, true, domain = null),
            field("p", FieldKind.PASSWORD, true, scheme = "http"))) {
            assertEmpty(listOf(field("u", FieldKind.USERNAME), password))
        }
    }

    @Test fun missingContainerAllowsOnlyTheFocusedPassword() {
        assertEmpty(listOf(field("u", FieldKind.USERNAME, true, null), field("p", FieldKind.PASSWORD, container = null)))
        val form = FormSelector.select(listOf(field("u", FieldKind.USERNAME, container = null),
            field("p", FieldKind.PASSWORD, true, null)))
        assertNull(form.usernameId)
        assertEquals("p", form.passwordId)
    }

    @Test fun valuesAreCarriedForTheFocusedForm() {
        val form = FormSelector.select(listOf(field("u", FieldKind.USERNAME, value = "alice"),
            field("p", FieldKind.PASSWORD, true, value = "secret")))
        assertEquals("alice", form.usernameValue)
        assertEquals("secret", form.passwordValue)
        assertEquals("https", form.webScheme)
    }

    @Test fun frameworkFocusOverridesMissingOrConflictingNodeFlags() {
        val fields = listOf(field("u", FieldKind.USERNAME, true),
            field("p1", FieldKind.PASSWORD), field("p2", FieldKind.PASSWORD))
        assertEquals("p2", FormSelector.select(fields, "p2").passwordId)
        assertEquals("p2", FormSelector.select(fields.map { it.copy(focused = it.id == "p2") }).passwordId)
        assertEquals(null, FormSelector.select(fields, "unclassified").passwordId)
        assertEquals("p1", FormSelector.select(fields.map { it.copy(focused = false) }, "p1").passwordId)
    }

    @Test fun focusedUsernameWinsAmongMultipleUsernames() {
        val form = FormSelector.select(listOf(field("other-u", FieldKind.USERNAME),
            field("u", FieldKind.USERNAME, true), field("p", FieldKind.PASSWORD)))
        assertEquals("u", form.usernameId)
        assertEquals("p", form.passwordId)
    }
}
