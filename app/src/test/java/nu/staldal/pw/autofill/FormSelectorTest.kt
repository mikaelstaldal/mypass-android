package nu.staldal.pw.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormSelectorTest {

    private fun field(
        id: String,
        kind: FieldKind,
        domain: String? = "example.com",
        scheme: String? = "https",
        value: String? = null,
        focused: Boolean = false,
    ) = FieldCandidate(
        kind = kind,
        id = id,
        scheme = scheme,
        domain = domain,
        value = value,
        focused = focused,
    )

    private fun user(id: String) = field(id, FieldKind.USERNAME)
    private fun pass(id: String) = field(id, FieldKind.PASSWORD)

    @Test
    fun picksTheOnlyFormOnThePage() {
        val form = FormSelector.select(listOf(user("u"), pass("p")))
        assertEquals("u", form.usernameId)
        assertEquals("p", form.passwordId)
        assertEquals("example.com", form.webDomain)
        assertEquals("https", form.webScheme)
    }

    @Test
    fun withoutAFocusedFieldTheFirstFormWins() {
        val form = FormSelector.select(
            listOf(user("signin-u"), pass("signin-p"), user("register-u"), pass("register-p"))
        )
        assertEquals("signin-u", form.usernameId)
        assertEquals("signin-p", form.passwordId)
    }

    @Test
    fun theFocusedPasswordFieldDecidesWhichFormIsAnswered() {
        // A sign-in form above a registration form: tapping the registration
        // password must not offer to fill the sign-in form's fields.
        val form = FormSelector.select(
            listOf(
                user("signin-u"),
                pass("signin-p"),
                user("register-u"),
                field("register-p", FieldKind.PASSWORD, focused = true),
            )
        )
        assertEquals("register-u", form.usernameId)
        assertEquals("register-p", form.passwordId)
    }

    @Test
    fun theFocusedUsernameFieldPicksThePasswordBelowIt() {
        val form = FormSelector.select(
            listOf(
                user("signin-u"),
                pass("signin-p"),
                field("register-u", FieldKind.USERNAME, focused = true),
                pass("register-p"),
            )
        )
        assertEquals("register-u", form.usernameId)
        assertEquals("register-p", form.passwordId)
    }

    @Test
    fun aFocusedUsernameFallsBackUpwardsWhenThePasswordComesFirst() {
        // Some layouts put the password box above the username box.
        val form = FormSelector.select(
            listOf(pass("p"), field("u", FieldKind.USERNAME, focused = true))
        )
        assertEquals("u", form.usernameId)
        assertEquals("p", form.passwordId)
    }

    @Test
    fun theUsernameIsTheNearestOneAboveThePassword() {
        val form = FormSelector.select(
            listOf(user("far"), user("near"), field("p", FieldKind.PASSWORD, focused = true))
        )
        assertEquals("near", form.usernameId)
    }

    @Test
    fun aUsernameOnAnotherOriginIsDropped() {
        // A cross-origin iframe must not collect the outer page's credential,
        // and its field is not part of this form.
        val form = FormSelector.select(
            listOf(
                field("outer-u", FieldKind.USERNAME, domain = "evil.example"),
                field("inner-p", FieldKind.PASSWORD, domain = "bank.example"),
            )
        )
        assertNull(form.usernameId)
        assertEquals("inner-p", form.passwordId)
        // The origin reported is the password field's, never the other frame's.
        assertEquals("bank.example", form.webDomain)
    }

    @Test
    fun aDifferentSchemeIsAlsoADifferentOrigin() {
        val form = FormSelector.select(
            listOf(
                field("u", FieldKind.USERNAME, scheme = "http"),
                field("p", FieldKind.PASSWORD, scheme = "https"),
            )
        )
        assertNull(form.usernameId)
        assertEquals("https", form.webScheme)
    }

    @Test
    fun valuesAreCarriedThroughForSaveRequests() {
        val form = FormSelector.select(
            listOf(
                field("u", FieldKind.USERNAME, value = "alice"),
                field("p", FieldKind.PASSWORD, value = "hunter2"),
            )
        )
        assertEquals("alice", form.usernameValue)
        assertEquals("hunter2", form.passwordValue)
    }

    @Test
    fun aCrossOriginUsernameValueIsDroppedToo() {
        // Not just the id: the value must not reach a save request either, or
        // the other frame's text would be stored as this site's username.
        val form = FormSelector.select(
            listOf(
                field("u", FieldKind.USERNAME, domain = "evil.example", value = "attacker"),
                field("p", FieldKind.PASSWORD, domain = "bank.example", value = "hunter2"),
            )
        )
        assertNull(form.usernameValue)
        assertEquals("hunter2", form.passwordValue)
    }

    @Test
    fun aPasswordFieldAloneIsStillAForm() {
        val form = FormSelector.select(listOf(pass("p")))
        assertNull(form.usernameId)
        assertEquals("p", form.passwordId)
        assertEquals("example.com", form.webDomain)
    }

    @Test
    fun aUsernameFieldAloneIsNotSomethingToFill() {
        // No password field: the service declines the request entirely, but
        // the origin is still reported from what there is.
        val form = FormSelector.select(listOf(user("u")))
        assertNull(form.passwordId)
        assertEquals("u", form.usernameId)
        assertEquals("example.com", form.webDomain)
    }

    @Test
    fun nothingAtAllSelectsNothing() {
        val form = FormSelector.select(emptyList<FieldCandidate<String>>())
        assertNull(form.usernameId)
        assertNull(form.passwordId)
        assertNull(form.webDomain)
        assertNull(form.webScheme)
    }
}
