package nu.staldal.pw.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormSelectorTest {
    private fun field(id: String, kind: FieldKind, focused: Boolean = false,
                      container: Any? = "form", domain: String? = "example.com",
                      scheme: String? = "https", value: String? = null) =
        FieldCandidate(kind, id, scheme, domain, value, focused, container)

    /**
     * A refusal names no fields and says which rule refused: the reason is what
     * [FillDiagnostics] reports, so it is asserted alongside the emptiness.
     */
    private fun assertRefused(refusal: FormRefusal, fields: List<FieldCandidate<String>>) {
        val form = FormSelector.select(fields)
        assertNull(form.usernameId)
        assertNull(form.passwordId)
        assertNull(form.webScheme)
        assertNull(form.webDomain)
        assertNull(form.usernameValue)
        assertNull(form.passwordValue)
        assertEquals(refusal, form.diagnosis.refusal)
    }

    @Test fun missingOrMultipleFocusIsRefused() {
        assertRefused(FormRefusal.NO_CLASSIFIED_FIELD, emptyList())
        assertRefused(FormRefusal.NO_FOCUSED_FIELD,
            listOf(field("u", FieldKind.USERNAME), field("p", FieldKind.PASSWORD)))
        assertRefused(FormRefusal.NO_FOCUSED_FIELD,
            listOf(field("u", FieldKind.USERNAME, true), field("p", FieldKind.PASSWORD, true)))
    }

    @Test fun focusedUsernameCannotSelectAnotherFrame() {
        assertRefused(FormRefusal.NO_PASSWORD_FIELD, listOf(field("u", FieldKind.USERNAME, true),
            field("p", FieldKind.PASSWORD, domain = "other.example")))
        // Even same-origin frames have different container identities.
        assertRefused(FormRefusal.NO_PASSWORD_FIELD, listOf(field("u", FieldKind.USERNAME, true, "frame-a"),
            field("p", FieldKind.PASSWORD, container = "frame-b")))
    }

    @Test fun usernameOnlyFormCannotBorrowAdjacentPassword() {
        assertRefused(FormRefusal.NO_PASSWORD_FIELD,
            listOf(field("u", FieldKind.USERNAME, true, "username-only"),
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
        assertRefused(FormRefusal.AMBIGUOUS_PASSWORD, listOf(field("u", FieldKind.USERNAME, true),
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
            assertRefused(FormRefusal.INELIGIBLE_ORIGIN, listOf(field("u", FieldKind.USERNAME), password))
        }
    }

    /**
     * The origin the browser claimed is reported as it arrived, unjudged — a
     * browser that reports no scheme is the case this exists to diagnose.
     */
    @Test fun refusedOriginIsReportedAsTheBrowserClaimedIt() {
        val diagnosis = FormSelector.select(listOf(
            field("u", FieldKind.USERNAME),
            field("p", FieldKind.PASSWORD, true, scheme = null, domain = "example.com"),
        )).diagnosis
        assertEquals(FormRefusal.INELIGIBLE_ORIGIN, diagnosis.refusal)
        assertNull(diagnosis.focusedScheme)
        assertEquals("example.com", diagnosis.focusedHost)
        assertEquals(2, diagnosis.classifiedFields)
    }

    @Test fun aSelectedFormReportsNoRefusal() {
        val diagnosis = FormSelector.select(listOf(field("u", FieldKind.USERNAME),
            field("p", FieldKind.PASSWORD, true))).diagnosis
        assertNull(diagnosis.refusal)
        assertEquals("https", diagnosis.focusedScheme)
        assertEquals("example.com", diagnosis.focusedHost)
    }

    @Test fun missingContainerAllowsOnlyTheFocusedPassword() {
        assertRefused(FormRefusal.NO_SHARED_CONTAINER,
            listOf(field("u", FieldKind.USERNAME, true, null), field("p", FieldKind.PASSWORD, container = null)))
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

    /**
     * Desktop pw's rule (`webextension/fill.js` `findUsernameField`): the
     * nearest text input preceding the password in the same form, used only
     * when the page identified no username field at all.
     */
    @Test fun anUnidentifiedTextFieldBeforeThePasswordIsTheUsername() {
        val form = FormSelector.select(listOf(field("t", FieldKind.TEXT),
            field("p", FieldKind.PASSWORD, true)))
        assertEquals("t", form.usernameId)
        assertEquals("p", form.passwordId)
    }

    @Test fun theNearestPrecedingTextFieldWins() {
        val form = FormSelector.select(listOf(field("far", FieldKind.TEXT),
            field("near", FieldKind.TEXT), field("p", FieldKind.PASSWORD, true)))
        assertEquals("near", form.usernameId)
    }

    @Test fun aTextFieldAfterThePasswordIsNotTheUsername() {
        val form = FormSelector.select(listOf(field("p", FieldKind.PASSWORD, true),
            field("t", FieldKind.TEXT)))
        assertNull(form.usernameId)
        assertEquals("p", form.passwordId)
    }

    @Test fun anIdentifiedUsernameOutranksPosition() {
        val form = FormSelector.select(listOf(field("t", FieldKind.TEXT),
            field("u", FieldKind.USERNAME), field("p", FieldKind.PASSWORD, true)))
        assertEquals("u", form.usernameId)
    }

    /** Position does not rescue what the "do not guess" rule refused. */
    @Test fun ambiguousUsernamesAreStillOmittedRatherThanInferred() {
        val form = FormSelector.select(listOf(field("t", FieldKind.TEXT),
            field("u1", FieldKind.USERNAME), field("u2", FieldKind.USERNAME),
            field("p", FieldKind.PASSWORD, true)))
        assertNull(form.usernameId)
        assertEquals("p", form.passwordId)
    }

    @Test fun positionNeverCrossesAFormOrAnOrigin() {
        for (text in listOf(field("t", FieldKind.TEXT, container = "other"),
            field("t", FieldKind.TEXT, domain = "evil.example"),
            field("t", FieldKind.TEXT, scheme = "http"))) {
            val form = FormSelector.select(listOf(text, field("p", FieldKind.PASSWORD, true)))
            assertNull(form.usernameId)
            assertEquals("p", form.passwordId)
        }
    }

    /**
     * The NO_FOCUSED_FIELD case: tapping a username box the page says nothing
     * about must answer for its form, not refuse.
     */
    @Test fun focusingAnUnidentifiedTextFieldSelectsItsForm() {
        val form = FormSelector.select(listOf(field("t", FieldKind.TEXT, true),
            field("p", FieldKind.PASSWORD)))
        assertEquals("t", form.usernameId)
        assertEquals("p", form.passwordId)
        assertNull(form.diagnosis.refusal)
    }

    @Test fun focusingATextFieldWithNoPasswordInItsFormIsStillRefused() {
        assertRefused(FormRefusal.NO_PASSWORD_FIELD,
            listOf(field("t", FieldKind.TEXT, true, "search-form"),
                field("p", FieldKind.PASSWORD, container = "login")))
    }

    @Test fun focusedUsernameWinsAmongMultipleUsernames() {
        val form = FormSelector.select(listOf(field("other-u", FieldKind.USERNAME),
            field("u", FieldKind.USERNAME, true), field("p", FieldKind.PASSWORD)))
        assertEquals("u", form.usernameId)
        assertEquals("p", form.passwordId)
    }
}
