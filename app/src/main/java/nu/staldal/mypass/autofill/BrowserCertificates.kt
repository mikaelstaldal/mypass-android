package nu.staldal.mypass.autofill

import android.content.pm.PackageManager

/** Read fresh on every request; absent/invisible/unreadable packages fail closed. */
object BrowserCertificates {
    @Suppress("DEPRECATION")
    fun read(manager: PackageManager, packageName: String): Browsers.Identity? = try {
        val info = manager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signing = info.signingInfo
        if (signing == null) null else {
            val current = signing.apkContentsSigners?.map { Browsers.certificateDigest(it.toByteArray()) }?.toSet().orEmpty()
            val history = if (signing.hasMultipleSigners()) emptySet() else
                signing.signingCertificateHistory?.map { Browsers.certificateDigest(it.toByteArray()) }?.toSet().orEmpty()
            if (current.isEmpty()) null else Browsers.Identity(current, history)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } catch (_: RuntimeException) {
        null
    }
}
