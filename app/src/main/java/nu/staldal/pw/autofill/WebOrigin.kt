package nu.staldal.pw.autofill

/** Browser origin declarations replace both components, even when incomplete. */
internal data class WebOrigin(val scheme: String?, val domain: String?) {
    fun descend(declaredScheme: String?, declaredDomain: String?): WebOrigin =
        if (declaredScheme == null && declaredDomain == null) this else
            WebOrigin(declaredScheme?.takeIf { it.isNotBlank() },
                declaredDomain?.takeIf { it.isNotBlank() })
}

/** Tree-walk state shared by the Android parser and JVM boundary tests. */
internal data class FormContext(
    val origin: WebOrigin = WebOrigin(null, null),
    val form: Any? = null,
    val container: Any? = null,
) {
    fun descend(node: Any, parent: Any?, scheme: String?, domain: String?, isForm: Boolean): FormContext {
        val declaresOrigin = scheme != null || domain != null
        val enclosingForm = if (isForm) node else form.takeUnless { declaresOrigin }
        return FormContext(origin.descend(scheme, domain), enclosingForm,
            enclosingForm ?: parent.takeUnless { declaresOrigin })
    }

    /**
     * The form or container a *field* on this node belongs to, decided by its
     * ancestors alone.
     *
     * A node's own origin declaration settles where the node **is** — that is
     * what keeps a cross-origin iframe's field attributed to the iframe — but
     * not what it is **part of**. Browsers annotate individual field nodes
     * with their frame's origin, so reading such a declaration as "a new
     * document starts here" puts every annotated field in a container of its
     * own, and a username and password can then never pair. [descend] still
     * resets the form for everything *below* an origin-declaring node, which
     * is where a real document boundary is.
     *
     * Pairing across a genuine origin boundary stays impossible without this
     * reset: [FormSelector] requires the same scheme and domain as well as the
     * same container.
     */
    fun fieldContainer(parent: Any?): Any? = form ?: parent
}
