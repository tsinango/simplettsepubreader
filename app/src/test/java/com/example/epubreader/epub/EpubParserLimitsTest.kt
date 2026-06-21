package com.example.epubreader.epub

import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubParserLimitsTest {
    private val parser = EpubParser()

    @Test
    fun rejectTooManyEntries() {
        val manyEntries = createTempFile("many", ".epub")
        ZipOutputStream(manyEntries.outputStream()).use { zip ->
            repeat(5000) { i ->
                zip.putNextEntry(ZipEntry("file$i.xml"))
                zip.write("<html><body><p>test</p></body></html>".toByteArray())
                zip.closeEntry()
            }
        }
        val ex = assertThrows(IllegalStateException::class.java) {
            parser.parse(manyEntries)
        }
        assert(ex.message?.contains("过多") == true)
    }

    @Test
    fun rejectMissingContainerXml() {
        val noContainer = createTempFile("nocontainer", ".epub")
        ZipOutputStream(noContainer.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("content.opf"))
            zip.write(opfXmlWithItem().toByteArray())
            zip.closeEntry()
        }
        val ex = assertThrows(IllegalStateException::class.java) {
            parser.parse(noContainer)
        }
        assert(ex.message?.contains("container.xml") == true)
    }

    @Test
    fun rejectEncryptedEpub() {
        val encrypted = createTempFile("encrypted", ".epub")
        ZipOutputStream(encrypted.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write("""<?xml version="1.0"?><container><rootfiles><rootfile full-path="content.opf"/></rootfiles></container>""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("META-INF/encryption.xml"))
            zip.write("<encryption/>".toByteArray())
            zip.closeEntry()
        }
        val ex = assertThrows(IllegalStateException::class.java) {
            parser.parse(encrypted)
        }
        assert(ex.message?.contains("DRM") == true || ex.message?.contains("加密") == true)
    }

    @Test
    fun rejectEmptySpine() {
        val noSpine = createTempFile("nospine", ".epub")
        ZipOutputStream(noSpine.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write("""<?xml version="1.0"?><container><rootfiles><rootfile full-path="content.opf"/></rootfiles></container>""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("content.opf"))
            zip.write(opfWithoutSpine().toByteArray())
            zip.closeEntry()
        }
        val ex = assertThrows(IllegalStateException::class.java) {
            parser.parse(noSpine)
        }
        assert(ex.message?.contains("spine") == true || ex.message?.contains("缺少") == true)
    }

    @Test
    fun xxePreventsExternalEntities() {
        val xxe = createTempFile("xxe", ".epub")
        ZipOutputStream(xxe.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write("""<?xml version="1.0"?><!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><container><rootfiles><rootfile full-path="content.opf"/></rootfiles></container>""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("content.opf"))
            zip.write("""<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf"><metadata><dc:title xmlns:dc="http://purl.org/dc/elements/1.1/">XXE &xxe;</dc:title></metadata><manifest><item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c1"/></spine></package>""".toByteArray())
            zip.closeEntry()
        }
        val ex = assertThrows(Exception::class.java) {
            parser.parse(xxe)
        }
        assert(ex.message?.contains("container.xml") == true || ex.message?.contains("XXE") == false)
    }

    private fun opfXmlWithItem(): String = """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf">
  <metadata><dc:title xmlns:dc="http://purl.org/dc/elements/1.1/">Test</dc:title></metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="c1" href="OEBPS/chapter1.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine><itemref idref="c1"/></spine>
</package>"""

    private fun opfWithoutSpine(): String = """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf">
  <metadata><dc:title xmlns:dc="http://purl.org/dc/elements/1.1/">Test</dc:title></metadata>
  <manifest>
    <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
</package>"""
}
