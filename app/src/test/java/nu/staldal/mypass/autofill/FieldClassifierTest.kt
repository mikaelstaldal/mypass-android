package nu.staldal.mypass.autofill

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FieldClassifierTest {

    private fun hints(vararg hints: String) = FieldSignals(autofillHints = hints.toList())

    private fun input(vararg attributes: Pair<String, String>) =
        FieldSignals(htmlTag = "input", htmlAttributes = attributes.toMap())

    private fun inputType(type: Int) = FieldSignals(inputType = type)

    @Test
    fun autofillHintsWinWhenThePageDeclaresThem() {
        assertEquals(FieldKind.PASSWORD, FieldClassifier.classify(hints("password")))
        assertEquals(FieldKind.PASSWORD, FieldClassifier.classify(hints("current-password")))
        assertEquals(FieldKind.PASSWORD, FieldClassifier.classify(hints("new-password")))
        assertEquals(FieldKind.USERNAME, FieldClassifier.classify(hints("username")))
        assertEquals(FieldKind.USERNAME, FieldClassifier.classify(hints("emailAddress")))
        assertEquals(FieldKind.USERNAME, FieldClassifier.classify(hints("email")))
        // Case is the page's business, not ours.
        assertEquals(FieldKind.PASSWORD, FieldClassifier.classify(hints("PASSWORD")))
        // A hint about something else leaves the field unclassified.
        assertNull(FieldClassifier.classify(hints("postalCode")))
    }

    @Test
    fun hintsOutrankTheInputType() {
        // A "show password" field is a visible-password input the page has
        // told us is the password.
        val signals = FieldSignals(
            autofillHints = listOf("password"),
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        )
        assertEquals(FieldKind.PASSWORD, FieldClassifier.classify(signals))
    }

    @Test
    fun htmlPasswordInputIsAPassword() {
        assertEquals(FieldKind.PASSWORD, FieldClassifier.classify(input("type" to "password")))
        assertEquals(FieldKind.PASSWORD, FieldClassifier.classify(input("type" to "PASSWORD")))
    }

    @Test
    fun htmlNameAndIdIdentifyAUsername() {
        assertEquals(FieldKind.USERNAME, FieldClassifier.classify(input("name" to "username")))
        assertEquals(FieldKind.USERNAME, FieldClassifier.classify(input("id" to "user_id")))
        assertEquals(
            FieldKind.USERNAME,
            FieldClassifier.classify(input("type" to "email", "name" to "signin_email")),
        )
        assertEquals(
            FieldKind.USERNAME,
            FieldClassifier.classify(input("autocomplete" to "username")),
        )
    }

    @Test
    fun nonTextualInputsAreNeverUsernames() {
        // A "remember me" checkbox called `login-remember` is not a username
        // box, whatever its name says.
        assertNull(FieldClassifier.classify(input("type" to "checkbox", "name" to "login")))
        assertNull(FieldClassifier.classify(input("type" to "submit", "name" to "login")))
        assertNull(FieldClassifier.classify(input("type" to "date", "name" to "account")))
        // Nor a candidate for the positional rule, which only ever looks at
        // inputs that could hold one.
        assertNull(FieldClassifier.classify(input("type" to "checkbox")))
        assertNull(FieldClassifier.classify(input("type" to "number")))
    }

    @Test
    fun anEmailInputIsAUsernameWithoutBeingNamedOne() {
        assertEquals(FieldKind.USERNAME, FieldClassifier.classify(input("type" to "email")))
        // my.charge.space/userapp/login: a shared input component that sets
        // autocomplete="off" and neither a name nor an id. Browsers do not
        // populate the Android input type for web fields, so the declared
        // type is the only thing here that says "username".
        assertEquals(
            FieldKind.USERNAME,
            FieldClassifier.classify(input("type" to "email", "autocomplete" to "off")),
        )
    }

    @Test
    fun anInputWithNothingToSayCouldStillHoldAUsername() {
        // Not "this is the username" — only "this could be". Which one is, if
        // any, is FormSelector's question, answered by position.
        assertEquals(FieldKind.TEXT, FieldClassifier.classify(input()))
        assertEquals(
            FieldKind.TEXT,
            FieldClassifier.classify(input("type" to "text", "name" to "search")),
        )
        assertEquals(FieldKind.TEXT, FieldClassifier.classify(input("type" to "tel")))
        // A tag that is not an input is not a form field we fill.
        assertNull(
            FieldClassifier.classify(
                FieldSignals(htmlTag = "div", htmlAttributes = mapOf("id" to "username"))
            )
        )
        // Nor is a native Android field: this rule is the desktop's, and the
        // desktop only ever looks at a web page.
        assertNull(FieldClassifier.classify(FieldSignals(htmlTag = null)))
    }

    @Test
    fun inputTypeIsTheLastResort() {
        assertEquals(
            FieldKind.PASSWORD,
            FieldClassifier.classify(
                inputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            ),
        )
        assertEquals(
            FieldKind.PASSWORD,
            FieldClassifier.classify(
                inputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
            ),
        )
        assertEquals(
            FieldKind.PASSWORD,
            FieldClassifier.classify(
                inputType(
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                )
            ),
        )
        assertEquals(
            FieldKind.USERNAME,
            FieldClassifier.classify(
                inputType(
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                )
            ),
        )
        // Not text at all: a phone number or a date is neither.
        assertNull(FieldClassifier.classify(inputType(InputType.TYPE_CLASS_PHONE)))
        assertNull(FieldClassifier.classify(inputType(InputType.TYPE_CLASS_DATETIME)))
        assertNull(
            FieldClassifier.classify(
                inputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS)
            ),
        )
    }

    @Test
    fun nothingAtAllIsNotAField() {
        assertNull(FieldClassifier.classify(FieldSignals()))
    }
}
