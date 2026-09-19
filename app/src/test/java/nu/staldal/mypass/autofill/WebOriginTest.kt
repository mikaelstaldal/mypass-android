package nu.staldal.mypass.autofill

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

    /**
     * Browsers annotate individual field nodes with their frame's origin.
     * Treating that as a document boundary put every such field in a container
     * of its own, so no username could ever pair with a password —
     * my.charge.space in Chrome, reported as NO_SHARED_CONTAINER.
     */
    @Test fun aFieldDeclaringItsOwnOriginStaysInItsForm() {
        val form = Any()
        val row = Any()
        val inForm = FormContext(outer).descend(form, Any(), null, null, true)
            .descend(row, form, null, null, false)
        // What the field itself belongs to: the form around it, and its own
        // declaration is not an input to that question at all.
        assertEquals(form, inForm.fieldContainer(row))
        // What a *subtree* under an origin-declaring node belongs to: not the
        // outer form. Both answers are wanted; only the first places a field.
        assertEquals(null, inForm.descend(Any(), row, "https", "outer.example", false).container)
    }

    @Test fun aFieldWithNoEnclosingFormBelongsToItsParent() {
        val parent = Any()
        assertEquals(parent, FormContext(outer).fieldContainer(parent))
        // Nothing to belong to at all is still refused downstream.
        assertEquals(null, FormContext(outer).fieldContainer(null))
    }

    /** A field inside a frame belongs to the frame, never to the outer form. */
    @Test fun aFieldInsideAFrameBelongsToTheFrame() {
        val outerForm = Any()
        val frame = Any()
        val frameContext = FormContext(outer, outerForm, outerForm)
            .descend(frame, outerForm, "https", "inner.example", false)
        assertEquals(frame, frameContext.fieldContainer(frame))
    }

    @Test fun anOriginDeclaringFormIsItsOwnContainer() {
        val form = Any()
        val context = FormContext(outer, Any()).descend(form, Any(), "https", "inner.example", true)
        assertEquals(form, context.form)
        assertEquals(form, context.container)
        assertEquals(WebOrigin("https", "inner.example"), context.origin)
    }
}
