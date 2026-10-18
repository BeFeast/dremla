package com.befeast.sleepmusic.playback.testing

import org.w3c.dom.Element
import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory

/** The merged manifest of the unit-test variant (libraries included), as AGP hands it to Robolectric. */
object MergedManifest {
    const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    val root: Element by lazy {
        val props = Properties()
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("com/android/tools/test_config.properties")) {
            "test_config.properties missing: unitTests.isIncludeAndroidResources must stay on"
        }
        stream.use { props.load(it) }
        val path = checkNotNull(props.getProperty("android_merged_manifest")) { "no android_merged_manifest in test_config.properties" }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        factory.newDocumentBuilder().parse(File(path)).documentElement
    }

    fun elements(tag: String): List<Element> {
        val nodes = root.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    fun Element.android(name: String): String = getAttributeNS(ANDROID_NS, name)

    val application: Element get() = elements("application").single()

    val permissions: Set<String> get() = elements("uses-permission").map { it.android("name") }.toSet()
}
