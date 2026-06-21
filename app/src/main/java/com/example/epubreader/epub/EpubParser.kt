package com.example.epubreader.epub

import com.example.epubreader.data.Chapter
import com.example.epubreader.data.BookCover
import com.example.epubreader.data.ParsedBook
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.w3c.dom.Element
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

class EpubParser {
    private val whitespace = Regex("\\s+")
    private val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
        safeFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        safeFeature("http://xml.org/sax/features/external-general-entities", false)
        safeFeature("http://xml.org/sax/features/external-parameter-entities", false)
        safeFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        safeFeature("http://apache.org/xml/features/nonvalidating/load-external-schema", false)
        setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
        setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
    }

    fun parse(file: File): ParsedBook {
        if (file.length() > MAX_FILE_BYTES) {
            error("EPUB 文件过大（超过 ${MAX_FILE_BYTES / (1024 * 1024)} MB）")
        }
        return ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().toList()
            if (entries.isEmpty()) error("EPUB 文件为空")
            if (entries.size > MAX_ZIP_ENTRIES) {
                error("EPUB 包含过多文件（超过 $MAX_ZIP_ENTRIES 个）")
            }

            if (zip.getEntry("META-INF/container.xml") == null) {
                error("EPUB 缺少 META-INF/container.xml")
            }

            if (zip.getEntry("META-INF/encryption.xml") != null) {
                error("暂不支持加密或 DRM 保护的 EPUB")
            }

            val container = xml(zip.read("META-INF/container.xml"))
            val packagePath = container.getElementsByTagNameNS("*", "rootfile")
                .item(0)?.attributes?.getNamedItem("full-path")?.nodeValue
                ?: error("EPUB 缺少内容清单")

            if (zip.getEntry(packagePath) == null) {
                error("EPUB 内容清单文件 $packagePath 不存在")
            }

            val opf = xml(zip.read(packagePath))
            val base = packagePath.substringBeforeLast('/', "")

            val metadata = opf.getElementsByTagNameNS("*", "metadata").item(0) as? Element
            val title = metadata?.getElementsByTagNameNS("*", "title")?.item(0)?.textContent
                ?.clean().orEmpty().ifBlank { file.nameWithoutExtension }
            val author = metadata?.getElementsByTagNameNS("*", "creator")?.item(0)?.textContent
                ?.clean().orEmpty().ifBlank { "未知作者" }

            val manifest = mutableMapOf<String, ManifestItem>()
            val items = opf.getElementsByTagNameNS("*", "item")

            for (i in 0 until items.length) {
                val item = items.item(i) as Element
                val id = item.getAttribute("id")
                val href = item.getAttribute("href")
                if (id.isBlank() || href.isBlank()) continue

                manifest[id] = ManifestItem(
                    id = id,
                    href = resolve(base, href.substringBefore('#')),
                    mediaType = item.getAttribute("media-type"),
                    properties = item.getAttribute("properties")
                )
            }

            if (manifest.isEmpty()) error("EPUB 内容清单为空")

            val cover = findCover(metadata, manifest)
            val navTitles = parseNcxTitles(zip, opf, base, manifest) + parseNavTitles(zip, manifest)

            val chapters = mutableListOf<Chapter>()
            val spine = opf.getElementsByTagNameNS("*", "spine")
            if (spine.length == 0) error("EPUB 缺少 spine")
            val refs = opf.getElementsByTagNameNS("*", "itemref")
            if (refs.length == 0) error("EPUB spine 中没有引用")

            var totalContentBytes = 0L

            for (i in 0 until refs.length) {
                val ref = refs.item(i) as Element

                if (ref.getAttribute("linear").equals("no", ignoreCase = true)) {
                    continue
                }

                val item = manifest[ref.getAttribute("idref")] ?: continue

                if (!item.mediaType.contains("xhtml", ignoreCase = true) &&
                    !item.mediaType.contains("html", ignoreCase = true)
                ) {
                    continue
                }

                val entry = zip.getEntry(item.href) ?: continue

                if (entry.size > MAX_ENTRY_BYTES) {
                    error("章节文件 ${item.href} 过大（超过 ${MAX_ENTRY_BYTES / (1024 * 1024)} MB）")
                }

                val content = zip.readEntry(item.href, MAX_ENTRY_BYTES)
                totalContentBytes += content.size
                if (totalContentBytes > MAX_TOTAL_CONTENT_BYTES) {
                    error("全部章节内容总大小超过限制（${MAX_TOTAL_CONTENT_BYTES / (1024 * 1024)} MB）")
                }

                val document = Jsoup.parse(content.decodeToString(), "")

                document.select("script,style,nav,svg,noscript").remove()

                val blocks = document
                    .select("h1,h2,h3,h4,h5,h6,p,li,blockquote,pre")
                    .map { it.text().clean() }
                    .filter { it.isNotBlank() }
                    .ifEmpty {
                        val bodyText = document.body()?.text()?.clean().orEmpty()
                        when {
                            bodyText.isNotBlank() -> listOf(bodyText)
                            document.select("img").isNotEmpty() -> listOf("[图片章节]")
                            else -> emptyList()
                        }
                    }

                if (blocks.isNotEmpty()) {
                    val chapterTitle =
                        navTitles[item.href]
                            ?: document.selectFirst("h1,h2,h3")?.text()?.clean()
                            ?: "第 ${chapters.size + 1} 章"

                    chapters += Chapter(item.href, chapterTitle, blocks)
                }
            }

            check(chapters.isNotEmpty()) { "EPUB 中没有可阅读的正文" }
            ParsedBook(title, author, chapters, cover)
        }
    }

    private fun findCover(
        metadata: Element?,
        manifest: Map<String, ManifestItem>,
    ): BookCover? {
        val propertyCover = manifest.values.firstOrNull { item ->
            item.properties.split(whitespace).any { prop -> prop == "cover-image" } &&
                item.mediaType.startsWith("image/", ignoreCase = true)
        }
        if (propertyCover != null) {
            return BookCover(propertyCover.href, propertyCover.mediaType)
        }

        val metaCoverId = metadata
            ?.getElementsByTagNameNS("*", "meta")
            ?.let { metas ->
                (0 until metas.length)
                    .map { metas.item(it) as Element }
                    .firstOrNull { it.getAttribute("name").equals("cover", ignoreCase = true) }
                    ?.getAttribute("content")
                    ?.takeIf { it.isNotBlank() }
            }
        val metaCover = manifest[metaCoverId]
        return metaCover
            ?.takeIf { it.mediaType.startsWith("image/", ignoreCase = true) }
            ?.let { BookCover(it.href, it.mediaType) }
    }

    private fun parseNavTitles(
        zip: ZipFile,
        manifest: Map<String, ManifestItem>
    ): Map<String, String> {
        val nav = manifest.values.firstOrNull {
            it.properties.split(whitespace).any { prop -> prop == "nav" }
        } ?: return emptyMap()

        val entry = zip.getEntry(nav.href) ?: return emptyMap()
        val content = zip.readEntry(nav.href, MAX_ENTRY_BYTES)
        val document = Jsoup.parse(content.decodeToString(), "")

        return document.select("a[href]")
            .mapNotNull { link ->
                val href = link.attr("href").substringBefore('#')
                val title = link.text().clean()
                if (href.isBlank() || title.isBlank()) {
                    null
                } else {
                    resolve(nav.href.substringBeforeLast('/', ""), href) to title
                }
            }
            .toMap()
    }

    private fun parseNcxTitles(
        zip: ZipFile,
        opf: org.w3c.dom.Document,
        base: String,
        manifest: Map<String, ManifestItem>
    ): Map<String, String> {
        val spine = opf.getElementsByTagNameNS("*", "spine").item(0) as? Element
            ?: return emptyMap()

        val tocId = spine.getAttribute("toc")
        val ncxItem = manifest[tocId] ?: manifest.values.firstOrNull {
            it.mediaType.contains("ncx", ignoreCase = true)
        } ?: return emptyMap()

        val entry = zip.getEntry(ncxItem.href) ?: return emptyMap()
        val content = zip.readEntry(ncxItem.href, MAX_ENTRY_BYTES)
        val ncx = xml(content)

        val result = mutableMapOf<String, String>()
        val navPoints = ncx.getElementsByTagNameNS("*", "navPoint")

        for (i in 0 until navPoints.length) {
            val navPoint = navPoints.item(i) as Element

            val label = navPoint
                .getElementsByTagNameNS("*", "text")
                .item(0)
                ?.textContent
                ?.clean()
                ?.removePrefix("#")
                ?.replace(Regex("\\.{2,}\\s*\\d+$"), "")
                ?.trim()
                .orEmpty()

            val content = navPoint
                .getElementsByTagNameNS("*", "content")
                .item(0) as? Element ?: continue

            val src = content.getAttribute("src")
                .substringBefore('#')
                .takeIf { it.isNotBlank() } ?: continue

            val path = resolve(base, src)

            if (label.isNotBlank()) {
                result[path] = label
            }
        }

        return result
    }

    private fun ZipFile.read(path: String): ByteArray =
        readEntry(path, MAX_ENTRY_BYTES)

    private fun ZipFile.readEntry(path: String, maxBytes: Long): ByteArray {
        val entry = getEntry(path) ?: error("EPUB 缺少 $path")
        return getInputStream(entry).use { input ->
            val total = entry.size
            if (total > maxBytes && total >= 0) {
                error("EPUB 文件 $path 过大（超过 ${maxBytes / (1024 * 1024)} MB）")
            }
            input.readBytes(maxBytes)
        }
    }

    private fun InputStream.readBytes(maxBytes: Long): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val buf = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val n = read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) {
                error("文件解压后大小超过限制（${maxBytes / (1024 * 1024)} MB）")
            }
            buffer.write(buf, 0, n)
        }
        return buffer.toByteArray()
    }

    private fun xml(bytes: ByteArray) = factory.newDocumentBuilder().parse(bytes.inputStream())

    private fun DocumentBuilderFactory.safeFeature(name: String, enabled: Boolean) {
        try {
            setFeature(name, enabled)
        } catch (_: ParserConfigurationException) {
        } catch (_: Exception) {
        }
    }

    private fun String.clean(): String =
        replace('\u00A0', ' ')
            .replace(whitespace, " ")
            .trim()

    private fun resolve(base: String, href: String): String {
        val cleanHref = href.substringBefore('#')
        val parts = (if (base.isBlank()) cleanHref else "$base/$cleanHref").split('/')
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

    private data class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: String
    )

    companion object {
        private const val MAX_FILE_BYTES = 200L * 1024 * 1024
        private const val MAX_ZIP_ENTRIES = 4096
        private const val MAX_ENTRY_BYTES = 10L * 1024 * 1024
        private const val MAX_TOTAL_CONTENT_BYTES = 100L * 1024 * 1024
    }
}
