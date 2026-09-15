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
}
