package nu.staldal.pw.autofill

import org.junit.Assert.assertEquals
import org.junit.Test

class WebOriginTest {
    private val outer = WebOrigin("https", "outer.example")

    @Test fun undeclaredOriginInheritsAsAUnit() {
        assertEquals(outer, outer.descend(null, null))
    }

    @Test fun partialAndEmptyDeclarationsClearTheOtherComponent() {
        assertEquals(WebOrigin(null, "inner.example"), outer.descend(null, "inner.example"))
        assertEquals(WebOrigin("http", null), outer.descend("http", null))
        assertEquals(WebOrigin(null, null), outer.descend("", ""))
        assertEquals(WebOrigin(null, null), outer.descend(null, ""))
        assertEquals(WebOrigin(null, null), outer.descend("", null))
    }

    @Test fun nestedFrameChildrenRetainOnlyTheirOwnDeclaration() {
        val inner = outer.descend(null, "inner.example").descend(null, null)
        assertEquals(WebOrigin(null, "inner.example"), inner)
        assertEquals(WebOrigin("https", "nested.example"), inner.descend("https", "nested.example"))
    }

    @Test fun nestedFramesResetOuterFormsEvenForTheSameOrigin() {
        val outerForm = Any()
        val outerContext = FormContext(outer, outerForm, outerForm)
        val frame = Any()
        val frameContext = outerContext.descend(frame, outerForm, "https", "outer.example", false)
        assertEquals(null, frameContext.form)
        assertEquals(null, frameContext.container)
        val field = frameContext.descend(Any(), frame, null, null, false)
        assertEquals(frame, field.container)
        assertEquals(null, field.form)
    }

    @Test fun formsKeepTheirIdentityAcrossNestedLayoutContainers() {
        val form = Any()
        val context = FormContext(outer).descend(form, Any(), null, null, true)
            .descend(Any(), form, null, null, false)
            .descend(Any(), Any(), null, null, false)
        assertEquals(form, context.container)
        assertEquals(outer, context.origin)
        val incompleteFrame = context.descend(Any(), form, null, "inner.example", false)
        assertEquals(null, incompleteFrame.form)
        assertEquals(WebOrigin(null, "inner.example"), incompleteFrame.origin)
    }

    @Test fun adjacentContainersDoNotShareAnImplicitForm() {
        val first = Any()
        val second = Any()
        val root = FormContext(outer)
        assertEquals(first, root.descend(Any(), first, null, null, false).container)
        assertEquals(second, root.descend(Any(), second, null, null, false).container)
    }

    @Test fun anOriginDeclaringFormIsItsOwnContainer() {
        val form = Any()
        val context = FormContext(outer, Any()).descend(form, Any(), "https", "inner.example", true)
        assertEquals(form, context.form)
        assertEquals(form, context.container)
        assertEquals(WebOrigin("https", "inner.example"), context.origin)
    }
}
