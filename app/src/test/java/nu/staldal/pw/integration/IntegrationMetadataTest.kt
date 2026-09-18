package nu.staldal.pw.integration

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the manifest half of the trust boundary, which Kotlin cannot see. */
class IntegrationMetadataTest {
    private val manifest: String = run {
        val path = "app/src/main/AndroidManifest.xml"
        val file = generateSequence(File(System.getProperty("user.dir").orEmpty()).absoluteFile) {
            it.parentFile
        }.map { File(it, path) }.firstOrNull { it.isFile }
        requireNotNull(file) { "Cannot find $path from ${System.getProperty("user.dir")}" }
        file.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
    }

    @Test fun fetchPermissionRequiresTheSameSigningIdentity() {
        val permission = Regex(
            "<permission\\s+[^>]*android:name=\"nu.staldal.pw.permission.FETCH_PASSWORD\"[^>]*/>",
            RegexOption.DOT_MATCHES_ALL,
        ).find(manifest)?.value.orEmpty()
        assertTrue("fetch permission must be declared", permission.isNotEmpty())
        assertTrue(
            "fetch permission must require the same signing identity",
            permission.contains("android:protectionLevel=\"signature\""),
        )
    }

    @Test fun exportedActivityIsGuardedByThatPermission() {
        val activity = Regex(
            "<activity\\s+android:name=\"\\.integration\\.FetchPasswordActivity\".*?</activity>",
            RegexOption.DOT_MATCHES_ALL,
        ).find(manifest)?.value.orEmpty()
        assertTrue("fetch activity must be declared", activity.isNotEmpty())
        assertTrue("fetch activity must be exported", activity.contains("android:exported=\"true\""))
        assertTrue(
            "fetch activity must require the signature permission",
            activity.contains("android:permission=\"nu.staldal.pw.permission.FETCH_PASSWORD\""),
        )
        assertTrue(
            "fetch activity must publish its action",
            activity.contains("<action android:name=\"${FetchPasswordActivity.ACTION_FETCH_PASSWORD}\""),
        )
    }
}
