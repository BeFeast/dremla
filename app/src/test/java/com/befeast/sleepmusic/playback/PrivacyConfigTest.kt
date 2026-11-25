package com.befeast.sleepmusic.playback

import android.content.pm.ApplicationInfo
import com.befeast.sleepmusic.R
import com.befeast.sleepmusic.playback.testing.MergedManifest
import com.befeast.sleepmusic.playback.testing.MergedManifest.android
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/** PRD 13 / AC-12: preferences never leave the device (no cloud backup, no device transfer). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivacyConfigTest {
    private val app = RuntimeEnvironment.getApplication()

    /** section -> list of (tag, domain, path) of a backup-rules resource. */
    private fun rules(id: Int): Map<String, List<Triple<String, String, String>>> {
        val parser = app.resources.getXml(id)
        val sections = linkedMapOf<String, MutableList<Triple<String, String, String>>>()
        var section = ""
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.depth) {
                1 -> section = parser.name
                2 -> if (parser.name in setOf("cloud-backup", "device-transfer")) section = parser.name
            }
            if (parser.name == "include" || parser.name == "exclude") {
                sections.getOrPut(section) { mutableListOf() } +=
                    Triple(parser.name, parser.getAttributeValue(null, "domain"), parser.getAttributeValue(null, "path"))
            }
        }
        return sections
    }

    @Test
    fun backupIsOff() {
        assertEquals(0, app.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    @Test
    fun dataExtractionRulesExcludeEverythingFromCloudBackupAndDeviceTransfer() {
        val domains = setOf(
            "root", "file", "database", "sharedpref", "external",
            "device_root", "device_file", "device_database", "device_sharedpref",
        )
        val sections = rules(R.xml.data_extraction_rules)
        assertEquals(setOf("cloud-backup", "device-transfer"), sections.keys)
        sections.forEach { (name, entries) ->
            assertTrue("$name has no include", entries.none { it.first == "include" })
            assertEquals(name, domains, entries.map { it.second }.toSet())
            assertTrue(name, entries.all { it.third == "." })
        }
    }

    @Test
    fun fullBackupContentExcludesEverythingOnOlderSystems() {
        val entries = rules(R.xml.backup_rules).values.flatten()
        assertTrue(entries.none { it.first == "include" })
        assertEquals(setOf("root", "file", "database", "sharedpref", "external"), entries.map { it.second }.toSet())
        assertTrue(entries.all { it.third == "." })
    }

    @Test
    fun theManifestReferencesBothRuleFiles() {
        val application = MergedManifest.application
        assertEquals("false", application.android("allowBackup"))
        assertEquals("@xml/data_extraction_rules", application.android("dataExtractionRules"))
        assertEquals("@xml/backup_rules", application.android("fullBackupContent"))
    }
}
