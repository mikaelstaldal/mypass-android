package nu.staldal.pw.autofill

/**
 * Which apps' claim of a web domain is believed.
 *
 * Android hands the autofill service a `webDomain` that the *source app*
 * filled in. A browser fills it in honestly; any other app may put whatever it
 * likes there, and the framework does not check. Since a pw entry is released
 * on nothing but its host, an app that could name a host freely could name
 * yours — so a web domain counts only when it comes from a package on this
 * list.
 *
 * This is the Android counterpart of the desktop host's `allowed_extensions`
 * pin: there, one extension id may talk to the host; here, one of these
 * packages may claim to be showing a web page. The package name is the whole
 * of the trust boundary (Android grants it, and it is unique per installed
 * app, but it is not a signature check) — see the security model in README.md.
 *
 * Users can extend this from Settings when their browser is not listed; it is
 * better than the alternative of having the integration silently do nothing.
 */
object Browsers {

    val KNOWN: Set<String> = setOf(
        // Firefox and its rebuilds
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.fenix",
        "org.mozilla.fenix.nightly",
        "org.mozilla.fennec_fdroid",
        "org.mozilla.focus",
        "org.mozilla.klar",
        "io.github.forkmaintainers.iceraven",
        "us.spotco.fennec_dos", // Mull
        "org.torproject.torbrowser",
        // Chrome and Chromium rebuilds
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "org.chromium.chrome",
        "com.google.android.apps.chrome",
        "com.brave.browser",
        "com.brave.browser_beta",
        "com.brave.browser_nightly",
        "com.microsoft.emmx",
        "com.microsoft.emmx.beta",
        "com.vivaldi.browser",
        "com.vivaldi.browser.snapshot",
        "com.kiwibrowser.browser",
        "com.opera.browser",
        "com.opera.browser.beta",
        "com.opera.gx",
        "com.opera.mini.native",
        "com.duckduckgo.mobile.android",
        "com.sec.android.app.sbrowser",
        "com.sec.android.app.sbrowser.beta",
        "org.bromite.bromite",
        "org.cromite.cromite",
        "org.ungoogled.chromium.stable",
        "com.ecosia.android",
        "com.qwant.liberty",
        "acr.browser.lightning", // Lightning
        "org.lineageos.jelly",
        "net.slions.fulguris.full.download",
    )

    /**
     * Whether [packageName] may be believed when it reports a web domain,
     * given the user's extra packages from Settings.
     */
    fun isTrustedBrowser(packageName: String?, extraPackages: String): Boolean {
        if (packageName == null) return false
        return packageName in KNOWN || packageName in parseExtraPackages(extraPackages)
    }

    /** Split the Settings field on commas and whitespace, dropping blanks. */
    fun parseExtraPackages(extraPackages: String): Set<String> =
        extraPackages.split(',', ' ', '\n', '\t')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
}
