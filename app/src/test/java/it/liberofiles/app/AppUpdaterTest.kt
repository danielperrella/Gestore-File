package it.liberofiles.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppUpdaterTest {
    private fun manifest() = JSONObject().put("versionCode", 4).put("versionName", "0.2.1")
        .put("sha256", "a".repeat(64)).put("size", 1000)
        .put("apkUrl", AppUpdater.BASE + "gestorefile.apk")

    @Test fun acceptsOnlyOfficialBoundedManifest() {
        AppUpdater.validate(manifest())
        for (invalid in listOf(manifest().put("apkUrl", "https://example.com/update.apk"),
            manifest().put("size", 40000001), manifest().put("size", 0),
            manifest().put("versionCode", 0), manifest().put("sha256", "wrong"))) {
            try { AppUpdater.validate(invalid); fail("Manifest accettato") }
            catch (expected: IllegalArgumentException) { /* expected */ }
        }
    }

    @Test fun rejectsCorruptedDownloadBeforeInstaller() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val file = File.createTempFile("update", ".apk")
        try {
            file.writeText("corrupt")
            try { AppUpdater(activity).verify(file, manifest()); fail("APK corrotto accettato") }
            catch (expected: SecurityException) { /* expected */ }
        } finally { file.delete() }
    }

    @Test fun computesKnownSha256() {
        val file = File.createTempFile("digest", ".txt")
        try {
            file.writeText("abc")
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", AppUpdater.digest(file))
        } finally { file.delete() }
    }
}
