package com.hermexapp.android

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

class BackupPolicyTest {
    private val expectedDomains = setOf(
        "root",
        "file",
        "database",
        "sharedpref",
        "external",
        "device_root",
        "device_file",
        "device_database",
        "device_sharedpref",
    )

    @Test
    fun `manifest explicitly disables backup`() {
        val application = document("AndroidManifest.xml")
            .getElementsByTagName("application")
            .item(0) as Element
        assertEquals(
            "false",
            application.getAttributeNS(ANDROID_NAMESPACE, "allowBackup"),
        )
    }

    @Test
    fun `legacy rules exclude every data domain at its root`() {
        val root = document("res/xml/backup_rules.xml").documentElement
        assertEquals(expectedDomains, exclusionDomains(root))
    }

    @Test
    fun `cloud and device transfer rules have identical complete exclusions`() {
        val rules = document("res/xml/data_extraction_rules.xml")
        val cloud = rules.getElementsByTagName("cloud-backup").item(0) as Element
        val transfer = rules.getElementsByTagName("device-transfer").item(0) as Element
        assertEquals(expectedDomains, exclusionDomains(cloud))
        assertEquals(expectedDomains, exclusionDomains(transfer))
    }

    private fun exclusionDomains(parent: Element): Set<String> =
        (0 until parent.childNodes.length)
            .map { parent.childNodes.item(it) }
            .filterIsInstance<Element>()
            .filter { it.tagName == "exclude" }
            .onEach { assertEquals(".", it.getAttribute("path")) }
            .map { it.getAttribute("domain") }
            .toSet()

    private fun document(relativePath: String): Document =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(File(mainSourceDirectory, relativePath))

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"

        val mainSourceDirectory: File = requireNotNull(
            File(
                requireNotNull(System.getenv("HERMEX_MANIFEST_SOURCE_PATH")) {
                    "HERMEX_MANIFEST_SOURCE_PATH is configured by android/build.gradle.kts"
                },
            ).parentFile,
        )
    }
}
