package cn.wpgd.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Audit A29, privacy property P7, GDN-109: nothing private (hands, saved tables, rejoin
 * capabilities) is backed up or transferred by the platform. Parses the real manifest and
 * data-extraction rules and fails if any backup or transfer domain stops being excluded.
 */
class BackupRulesTest {
    private val manifest = File("src/main/AndroidManifest.xml")
    private val rules = File("src/main/res/xml/data_extraction_rules.xml")

    @Test fun manifestTurnsPlatformBackupOff() {
        assertTrue("manifest not found at ${manifest.absolutePath}", manifest.isFile)
        assertEquals(emptyList<String>(), manifestProblems(manifest.readText()))
    }

    @Test fun everyBackupAndTransferDomainIsExcluded() {
        assertTrue("rules not found at ${rules.absolutePath}", rules.isFile)
        assertEquals(emptyList<String>(), ruleProblems(rules.readText()))
    }

    /** The checks are not vacuous: each kind of regression is reported. */
    @Test fun theCheckerCatchesEachKindOfRegression() {
        val m = manifest.readText()
        assertTrue(manifestProblems(m.replace("android:allowBackup=\"false\"", "android:allowBackup=\"true\"")).isNotEmpty())
        assertTrue(manifestProblems(m.replace("android:fullBackupContent=\"false\"", "")).isNotEmpty())
        assertTrue(manifestProblems(m.replace("android:dataExtractionRules=\"@xml/data_extraction_rules\"", "")).isNotEmpty())
        assertTrue(manifestProblems(m.replace("<application ", "<application android:backupAgent=\".Agent\" ")).isNotEmpty())

        val r = rules.readText()
        val droppedOne = r.replaceFirst("<exclude domain=\"database\" path=\".\" />", "")
        assertTrue(ruleProblems(droppedOne).isNotEmpty())
        val narrowed = r.replaceFirst("<exclude domain=\"file\" path=\".\" />", "<exclude domain=\"file\" path=\"cache\" />")
        assertTrue(ruleProblems(narrowed).isNotEmpty())
        val included = r.replaceFirst("</device-transfer>", "<include domain=\"file\" path=\"tables\" /></device-transfer>")
        assertTrue(ruleProblems(included).isNotEmpty())
        val noTransfer = r.substring(0, r.indexOf("<device-transfer>")) + "</data-extraction-rules>\n"
        assertTrue(ruleProblems(noTransfer).isNotEmpty())
    }

    private fun parse(text: String): Element {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        return f.newDocumentBuilder().parse(text.byteInputStream()).documentElement
    }

    private fun manifestProblems(text: String): List<String> {
        val app = parse(text).getElementsByTagName("application").item(0) as? Element ?: return listOf("no <application>")
        fun attr(name: String): String? = app.getAttributeNS(ANDROID_NS, name).takeIf { app.hasAttributeNS(ANDROID_NS, name) }
        val out = ArrayList<String>()
        if (attr("allowBackup") != "false") out += "android:allowBackup must be false (Android 11 and lower)"
        if (attr("fullBackupContent") != "false") out += "android:fullBackupContent must be false"
        if (attr("dataExtractionRules") != "@xml/data_extraction_rules") out += "android:dataExtractionRules must name @xml/data_extraction_rules (Android 12 and higher)"
        if (attr("backupAgent") != null) out += "no custom backup agent"
        return out
    }

    private fun ruleProblems(text: String): List<String> {
        val root = parse(text)
        val out = ArrayList<String>()
        if (root.tagName != "data-extraction-rules") out += "root is ${root.tagName}"
        val sections = (0 until root.childNodes.length).mapNotNull { root.childNodes.item(it) as? Element }
        for (required in listOf("cloud-backup", "device-transfer")) {
            if (sections.none { it.tagName == required }) out += "no <$required> section: its default would back up app data"
        }
        for (section in sections) {
            val entries = (0 until section.childNodes.length).mapNotNull { section.childNodes.item(it) as? Element }
            for (e in entries) if (e.tagName != "exclude") out += "<${section.tagName}> has <${e.tagName} domain=${e.getAttribute("domain")}>"
            val excluded = entries.filter { it.tagName == "exclude" && it.getAttribute("path") == "." }.map { it.getAttribute("domain") }.toSet()
            for (d in DOMAINS - excluded) out += "<${section.tagName}> does not exclude the whole '$d' domain"
        }
        return out
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

        /** Every domain the data-extraction-rules format defines. */
        val DOMAINS = setOf("root", "file", "database", "sharedpref", "external", "device_root", "device_file", "device_database", "device_sharedpref")
    }
}
