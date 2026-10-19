package it.liberofiles.app

import android.os.Environment
import android.content.pm.PackageManager
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowEnvironment
import org.robolectric.Shadows.shadowOf
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [AccessEnvironment::class])
class MainActivityTest {
    private val graph get() = (RuntimeEnvironment.getApplication() as FilesApplication).graph
    @Before fun setup() { AccessEnvironment.allowed = false }
    @After fun cleanup() { graph.cancellation?.cancel(); graph.reads.shutdownNow(); graph.operations.shutdownNow() }
    private fun texts(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }
    @Test fun startsWithPermissionExplanation() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val values = texts(controller.get().window.decorView)
        assertTrue(values.any { it == "Autorizza memoria interna" })
        assertTrue(values.any { it.contains("Internet solo per gli aggiornamenti") })
        controller.pause().stop().destroy()
    }
    @Test fun lightThemeAndLargeFontCanStart() {
        graph.preferences.edit().putBoolean("dark", false).putFloat("font", 1.3f).commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertTrue(texts(controller.get().window.decorView).contains("Gestore File"))
        controller.pause().stop().destroy()
    }
    @Test fun grantedStorageListsRealFixtureAsynchronously() {
        AccessEnvironment.allowed = true
        Files.createDirectories(graph.local.root)
        Files.write(graph.local.root.resolve("fixture.txt"), "contents".toByteArray())
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        var found = false
        for (i in 0 until 100) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (texts(controller.get().window.decorView).contains("fixture.txt")) { found = true; break }
            Thread.sleep(10)
        }
        assertTrue("Folder entries must reach the UI", found)
        controller.pause().stop().destroy()
    }
    @Test fun manifestAllowsUpdatesAndKeepsFileProvidersPrivate() {
        val context = RuntimeEnvironment.getApplication()
        val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertTrue(permissions.contains("android.permission.INTERNET"))
        assertTrue(permissions.contains("android.permission.REQUEST_INSTALL_PACKAGES"))
        val updates = context.packageManager.resolveContentProvider("${context.packageName}.updates", 0)!!
        assertFalse(updates.exported); assertTrue(updates.grantUriPermissions)
        val provider = context.packageManager.resolveContentProvider(ShareProvider.AUTHORITY, 0)!!
        assertFalse(provider.exported); assertTrue(provider.grantUriPermissions)
    }
    @Test fun revokingStorageAccessHidesPreviouslyLoadedEntries() {
        AccessEnvironment.allowed = true
        Files.createDirectories(graph.local.root)
        Files.write(graph.local.root.resolve("private-fixture.txt"), "contents".toByteArray())
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        for (i in 0 until 100) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (texts(controller.get().window.decorView).contains("private-fixture.txt")) break
            Thread.sleep(10)
        }
        assertTrue(texts(controller.get().window.decorView).contains("private-fixture.txt"))
        controller.pause(); AccessEnvironment.allowed = false; controller.resume()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val visible = texts(controller.get().window.decorView)
        assertFalse(visible.contains("private-fixture.txt"))
        assertTrue(visible.contains("Autorizza memoria interna"))
        controller.pause().stop().destroy()
    }
    @Test fun lateRecoveryResultCannotOpenDialogOnDestroyedActivity() {
        AccessEnvironment.allowed = true
        Files.createDirectories(graph.local.root)
        graph.journal.begin(mapOf("operation" to "test", "phase" to "prepare"))
        val started = java.util.concurrent.CountDownLatch(2)
        val release = java.util.concurrent.CountDownLatch(1)
        repeat(2) { graph.reads.execute { started.countDown(); release.await() } }
        assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
        try {
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            controller.pause().stop().destroy()
        } finally { release.countDown() }
        graph.reads.shutdown()
        assertTrue(graph.reads.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNull(org.robolectric.shadows.ShadowDialog.getLatestDialog())
    }
}

@Implements(Environment::class)
class AccessEnvironment : ShadowEnvironment() {
    companion object {
        @JvmField var allowed = false
        @Implementation @JvmStatic fun isExternalStorageManager(): Boolean = allowed
    }
}
