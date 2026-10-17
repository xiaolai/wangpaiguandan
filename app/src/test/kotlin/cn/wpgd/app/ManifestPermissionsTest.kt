package cn.wpgd.app

import cn.wpgd.app.ble.BlePermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * GDN-005, GDN-100, WI-09, WI-10: the app requests exactly the permissions in permissions-allowlist.txt
 * (the same list tools/ci.sh checks the merged manifests against), each with its reason written next
 * to it. The main manifest (every build) holds only normal install-time permissions; the Bluetooth
 * runtime permissions live in the bleTransport overlay, merged only when that flag is on. Keeping the
 * screen awake uses the window flag, not WAKE_LOCK; solo play needs nothing (SoloNeedsNoNetworkTest).
 */
class ManifestPermissionsTest {
    private val manifest = File("src/main/AndroidManifest.xml")
    private val bleManifest = File("src/bleTransport/AndroidManifest.xml")
    private val allowList = File("permissions-allowlist.txt")

    /**
     * Normal (install-time, no prompt) permissions this app might ever need. Adding to the
     * allow-list something outside this set fails here; RequestedPermissionsTest re-checks the
     * protection level on a device.
     */
    private val normal = setOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE", "android.permission.ACCESS_WIFI_STATE")

    private val tag = "bleTransport:"

    private fun lines(): List<String> = allowList.readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }

    private fun allowed(): List<String> = lines().filter { !it.startsWith(tag) }

    private fun allowedWithBle(): List<String> = lines().filter { it.startsWith(tag) }.map { it.removePrefix(tag).trim() }

    private fun requested(text: String): List<String> {
        // Entries marked tools:node="remove" strip a permission a library would merge in; they request nothing.
        val stripped = text.replace(Regex("""<(uses-permission|permission)\b[^>]*tools:node="remove"[^>]*/>"""), "")
        return Regex("""<uses-permission[^>]*android:name="([^"]+)"[^>]*>""").findAll(stripped).map { it.groupValues[1] }.toList()
    }

    private fun justified(text: String, p: String): Boolean {
        val tagAt = text.lastIndexOf("<uses-permission", text.indexOf("android:name=\"$p\""))
        return tagAt > 0 && text.substring(0, tagAt).trimEnd().endsWith("-->")
    }

    @Test fun theManifestRequestsExactlyTheAllowList() {
        assertTrue(manifest.isFile && allowList.isFile)
        val list = allowed()
        assertTrue("the allow-list is empty or unreadable", list.isNotEmpty())
        assertEquals("one entry per permission", lines().distinct(), lines())
        val text = manifest.readText()
        assertEquals(list.sorted(), requested(text).sorted())
        val stripped = text.replace(Regex("""<(uses-permission|permission)\b[^>]*tools:node="remove"[^>]*/>"""), "")
        assertTrue("uses-permission-sdk-23" !in stripped && "<permission" !in stripped)
        // The checker is not vacuous: an extra permission is caught.
        assertEquals(list.size + 1, requested(text.replace("<application ", "<uses-permission android:name=\"android.permission.WAKE_LOCK\" />\n    <application ")).size)
    }

    @Test fun everyAllowedPermissionIsNormalAndJustified() {
        val text = manifest.readText()
        for (p in allowed()) {
            assertTrue("$p is not a known normal (install-time) permission", p in normal)
            assertTrue("$p has no justification comment right before it in the manifest", justified(text, p))
        }
    }

    /** WI-10: with the flag off (the main manifest alone) nothing Bluetooth or location is declared. */
    @Test fun theMainManifestDeclaresNoBluetoothOrLocation() {
        val text = manifest.readText()
        assertTrue("BLUETOOTH" !in text && "LOCATION" !in text && "bluetooth" !in text)
    }

    /**
     * WI-10: the bleTransport overlay requests exactly the tagged allow-list lines: the three Android
     * 12+ "Nearby devices" permissions, each justified, scanning marked neverForLocation, and nothing
     * legacy (no BLUETOOTH, BLUETOOTH_ADMIN or location permission, scoped or not).
     */
    @Test fun theBluetoothOverlayRequestsExactlyTheTaggedLines() {
        assertTrue(bleManifest.isFile)
        val text = bleManifest.readText()
        val tagged = allowedWithBle()
        assertEquals(BlePermissions.RUNTIME, tagged.toSet())
        assertEquals(tagged.sorted(), requested(text).sorted())
        for (p in tagged) assertTrue("$p has no justification comment right before it", justified(text, p))
        val scan = Regex("""<uses-permission[^>]*android:name="android.permission.BLUETOOTH_SCAN"[^>]*>""").find(text)!!.value
        assertTrue("BLUETOOTH_SCAN must be neverForLocation", "android:usesPermissionFlags=\"neverForLocation\"" in scan)
        assertTrue("no location permission", "LOCATION\"" !in text)
        assertTrue("no maxSdk-scoped legacy permission", "maxSdkVersion" !in text)
        assertTrue("uses-permission-sdk-23" !in text && "<permission" !in text)
    }

    @Test fun theOverlayIsMergedOnlyWhenTheFlagIsOn() {
        val gradle = File("build.gradle.kts").readText()
        assertTrue(gradle.contains("""providers.gradleProperty("wpgd.bleTransport")"""))
        assertTrue(gradle.contains(".getOrElse(false)"))
        assertTrue(gradle.contains("""if (bleTransport) variant.sources.manifests.addStaticManifestFile("src/bleTransport/AndroidManifest.xml")"""))
    }

    @Test fun theApplicationHoldsTheSessionOwner() {
        assertTrue(manifest.readText().contains("android:name=\".WpgdApplication\""))
    }
}
