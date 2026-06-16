package com.example.epubreader.epub

import com.example.epubreader.data.Chapter
import com.example.epubreader.data.ParsedBook
import org.jsoup.Jsoup
import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

class EpubParser {
    private val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        safeFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        safeFeature("http://xml.org/sax/features/external-general-entities", false)
        safeFeature("http://xml.org/sax/features/external-parameter-entities", false)
    }

    fun parse(file: File): ParsedBook = ZipFile(file).use { zip ->
        if (zip.getEntry("META-INF/encryption.xml") != null) {
            error("暂不支持加密或 DRM 保护的 EPUB")
        }
        val container = xml(zip.read("META-INF/container.xml"))
        val packagePath = container.getElementsByTagNameNS("*", "rootfile")
            .item(0)?.attributes?.getNamedItem("full-path")?.nodeValue
            ?: error("EPUB 缺少内容清单")
        val opf = xml(zip.read(packagePath))
        val base = packagePath.substringBeforeLast('/', "")
        val metadata = opf.getElementsByTagNameNS("*", "metadata").item(0) as? Element
        val title = metadata?.getElementsByTagNameNS("*", "title")?.item(0)?.textContent
            ?.trim().orEmpty().ifBlank { file.nameWithoutExtension }
        val author = metadata?.getElementsByTagNameNS("*", "creator")?.item(0)?.textContent
            ?.trim().orEmpty().ifBlank { "未知作者" }

        val manifest = mutableMapOf<String, String>()
        val items = opf.getElementsByTagNameNS("*", "item")
        for (i in 0 until items.length) {
            val item = items.item(i) as Element
            manifest[item.getAttribute("id")] = resolve(base, item.getAttribute("href"))
        }
        val chapters = mutableListOf<Chapter>()
        val refs = opf.getElementsByTagNameNS("*", "itemref")
        for (i in 0 until refs.length) {
            val path = manifest[(refs.item(i) as Element).getAttribute("idref")] ?: continue
            val entry = zip.getEntry(path) ?: continue
            val document = Jsoup.parse(zip.getInputStream(entry), "UTF-8", "")
            document.select("script,style,nav").remove()
            val blocks = document.select("h1,h2,h3,h4,h5,h6,p,li,blockquote")
                .map { it.text().replace(Regex("\\s+"), " ").trim() }
                .filter { it.isNotBlank() }
                .ifEmpty {
                    document.body()?.text()?.trim()?.takeIf(String::isNotBlank)?.let(::listOf)
                        ?: emptyList()
                }
            if (blocks.isNotEmpty()) {
                val chapterTitle = document.selectFirst("h1,h2,h3,title")?.text()
                    ?.trim().orEmpty().ifBlank { "第 ${chapters.size + 1} 章" }
                chapters += Chapter(path, chapterTitle, blocks)
            }
        }
        check(chapters.isNotEmpty()) { "EPUB 中没有可阅读的正文" }
        ParsedBook(title, author, chapters)
    }

    private fun ZipFile.read(path: String): ByteArray =
        getEntry(path)?.let { getInputStream(it).readBytes() } ?: error("EPUB 缺少 $path")

    private fun xml(bytes: ByteArray) = factory.newDocumentBuilder().parse(bytes.inputStream())

    private fun DocumentBuilderFactory.safeFeature(name: String, enabled: Boolean) {
        try {
            setFeature(name, enabled)
        } catch (_: ParserConfigurationException) {
            // Android's built-in XML parser does not expose every SAX feature on every API level.
        }
    }

    private fun resolve(base: String, href: String): String {
        val parts = (if (base.isBlank()) href else "$base/$href").split('/')
        val result = ArrayDeque<String>()
        parts.forEach {
            when (it) {
                "", "." -> Unit
                ".." -> if (result.isNotEmpty()) result.removeLast()
                else -> result.addLast(it)
            }
        }
        return result.joinToString("/")
    }
}
