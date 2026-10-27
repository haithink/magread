package com.yichen.kandu.data

import android.net.Uri
import android.util.Xml
import org.jsoup.Jsoup
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * EPUB 解析：适配 awesome-english-ebooks 下载的期刊（ebook-lib/calibre 转换，
 * 文章为 UUID 命名的 XHTML，spine 顺序即文章顺序）。
 */
object EpubParser {

    private val WATERMARK = Regex("(?i)zlibrary|this (article|file) was downloaded by|中商海外高资")
    private val SKIP_NAME = listOf("cover", "toc", "ad_page", "masthead", "titlepage", "title_page", "colophon", "nav")

    /** 封面图字节数据 + 按 spine 顺序解析出的文章列表 */
    class EpubData(val coverBytes: ByteArray?, val articles: List<ArticleText>)

    // ---------------- 整本解析（用于阅读/摘要） ----------------

    fun parse(file: File): EpubData = ZipFile(file).use { zip ->
        val opfPath = findOpfPath(zip)
        val opf = readEntry(zip, opfPath) ?: error("EPUB 中找不到 OPF：$opfPath")
        val info = parseOpf(opf)
        val opfDir = opfPath.substringBeforeLast('/', "")

        // 封面
        val coverHref = info.coverHref
        val coverBytes = coverHref?.let { readEntry(zip, joinPath(opfDir, it)) }

        // 文章
        val articles = ArrayList<ArticleText>()
        for (href in info.spineHrefs) {
            if (isSkippable(href)) continue
            val bytes = readEntry(zip, joinPath(opfDir, href)) ?: continue
            val article = parseArticleHtml(articles.size, bytes) ?: continue
            articles += article
        }
        EpubData(coverBytes, articles)
    }

    // ---------------- 轻量封面提取（扫描时用流式，不落盘） ----------------

    fun extractCoverStream(input: InputStream): ByteArray? {
        var containerBytes: ByteArray? = null
        val opfCandidates = HashMap<String, ByteArray>()
        val imageCandidates = LinkedHashMap<String, ByteArray>()

        ZipInputStream(input.buffered()).use { zis ->
            var e: ZipEntry? = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory) {
                    val name = e.name
                    val lower = name.lowercase()
                    when {
                        lower == "meta-inf/container.xml" ->
                            containerBytes = zis.readBytes()
                        lower.endsWith(".opf") && e.size < 2_000_000 ->
                            opfCandidates[name] = zis.readBytes()
                        isImageName(lower) && e.size < 1_200_000 && nameContainsCover(lower) ->
                            imageCandidates[name] = zis.readBytes()
                    }
                }
                e = zis.nextEntry
            }
        }

        // 确定 OPF 与封面 href
        val container = containerBytes
        var opfPath: String? = null
        if (container != null) opfPath = parseContainer(container)
        val opfBytes: ByteArray? = opfPath?.let { path -> opfCandidates.entries.firstOrNull { it.key == path }?.value }
            ?: opfCandidates.entries.firstOrNull()?.value
        val coverHref = opfBytes?.let { parseOpfCover(it) }

        if (coverHref != null) {
            imageCandidates[coverHref]?.let { return it }
            imageCandidates[Uri.decode(coverHref)]?.let { return it }
            imageCandidates.entries.firstOrNull { it.key.endsWith(coverHref.substringAfterLast('/'), true) }
                ?.let { return it.value }
        }
        // 兜底：名字带 cover 的最大图
        return imageCandidates.values.maxByOrNull { it.size }
    }

    // ---------------- 内部工具 ----------------

    private fun findOpfPath(zip: ZipFile): String {
        val container = readEntry(zip, "META-INF/container.xml")
        if (container != null) {
            val parsed = parseContainer(container)
            if (!parsed.isNullOrBlank()) return parsed
        }
        return zip.entries().asSequence().firstOrNull { it.name.endsWith(".opf") }?.name
            ?: "EPUB/content.opf"
    }

    private fun parseContainer(bytes: ByteArray): String? {
        var result: String? = null
        walkXml(bytes) { parser, event ->
            if (event == XmlPullParser.START_TAG && parser.name == "rootfile") {
                val path = attr(parser, "full-path")
                if (!path.isNullOrBlank() && result == null) result = path
            }
        }
        return result
    }

    private class ManifestItem(val href: String, val mediaType: String, val properties: String)

    private class OpfInfo(val coverHref: String?, val spineHrefs: List<String>)

    private fun parseOpf(bytes: ByteArray): OpfInfo {
        val manifest = HashMap<String, ManifestItem>()
        val spine = ArrayList<String>()
        var coverId: String? = null

        walkXml(bytes) { parser, event ->
            if (event != XmlPullParser.START_TAG) return@walkXml
            when (parser.name) {
                "item" -> {
                    val id = attr(parser, "id") ?: return@walkXml
                    manifest[id] = ManifestItem(
                        href = attr(parser, "href") ?: "",
                        mediaType = attr(parser, "media-type") ?: "",
                        properties = attr(parser, "properties") ?: ""
                    )
                }
                "meta" -> {
                    if (attr(parser, "name") == "cover") coverId = attr(parser, "content")
                }
                "itemref" -> {
                    val idref = attr(parser, "idref")
                    if (idref != null) spine += idref
                }
            }
        }

        var coverHref: String? = coverId?.let { manifest[it]?.href }
            ?: manifest.values.firstOrNull { it.properties.split(Regex("\\s+")).contains("cover-image") }?.href
            ?: manifest.values.firstOrNull {
                it.mediaType.startsWith("image/") && it.href.lowercase().contains("cover")
            }?.href

        val hrefs = spine.mapNotNull { id -> manifest[id]?.href?.takeIf { it.isNotBlank() } }
        if (coverHref == null) {
            coverHref = hrefs.firstOrNull { it.lowercase().contains("cover") }
        }
        return OpfInfo(coverHref, hrefs)
    }

    private fun parseOpfCover(bytes: ByteArray): String? {
        val manifest = HashMap<String, ManifestItem>()
        var coverId: String? = null
        walkXml(bytes) { parser, event ->
            if (event != XmlPullParser.START_TAG) return@walkXml
            when (parser.name) {
                "item" -> {
                    val id = attr(parser, "id") ?: return@walkXml
                    manifest[id] = ManifestItem(
                        href = attr(parser, "href") ?: "",
                        mediaType = attr(parser, "media-type") ?: "",
                        properties = attr(parser, "properties") ?: ""
                    )
                }
                "meta" -> {
                    if (attr(parser, "name") == "cover") coverId = attr(parser, "content")
                }
            }
        }
        return coverId?.let { manifest[it]?.href }
            ?: manifest.values.firstOrNull {
                it.properties.split(Regex("\\s+")).contains("cover-image")
            }?.href
            ?: manifest.values.firstOrNull {
                it.mediaType.startsWith("image/") && it.href.lowercase().contains("cover")
            }?.href
    }

    private fun walkXml(bytes: ByteArray, onEvent: (XmlPullParser, Int) -> Unit) {
        runCatching {
            val parser = Xml.newPullParser()
            parser.setInput(ByteArrayInputStream(bytes), null)
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                onEvent(parser, event)
                event = parser.next()
            }
        }
    }

    private fun attr(parser: XmlPullParser, name: String): String? {
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeName(i) == name) return parser.getAttributeValue(i)
        }
        return null
    }

    private fun joinPath(dir: String, href: String): String {
        val raw = Uri.decode(href)
        val combined = if (dir.isBlank()) raw else "$dir/$raw"
        // 规范化 ../
        val stack = ArrayDeque<String>()
        for (seg in combined.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(seg)
            }
        }
        return stack.joinToString("/")
    }

    private fun readEntry(zip: ZipFile, name: String): ByteArray? {
        val entry = zip.getEntry(name) ?: zip.getEntry(Uri.encode(name)) ?: return null
        return zip.getInputStream(entry).use { it.readBytes() }
    }

    private fun isSkippable(href: String): Boolean {
        val name = href.substringAfterLast('/').lowercase()
        return SKIP_NAME.any { name.contains(it) }
    }

    private fun isImageName(lower: String): Boolean =
        lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp")

    private fun nameContainsCover(lower: String): Boolean = lower.contains("cover")

    /** 解析单篇 XHTML；不构成文章（封面/目录/广告/过短）时返回 null */
    private fun parseArticleHtml(index: Int, bytes: ByteArray): ArticleText? {
        val html = String(bytes, Charsets.UTF_8)
        val doc = Jsoup.parse(html)

        val title = listOf("h1", "h2", "h3").firstNotNullOfOrNull { sel ->
            doc.select(sel).firstOrNull { it.text().isNotBlank() }?.text()?.trim()
        } ?: ""

        val paragraphs = doc.select("p").mapNotNull { p ->
            val text = p.text().replace('\u00A0', ' ').trim()
            if (text.isEmpty()) null
            else if (WATERMARK.containsMatchIn(text)) null
            else if (text.length == 1 && !text[0].isLetter()) null
            else text
        }

        val totalLen = paragraphs.sumOf { it.length }
        if (paragraphs.isEmpty()) return null
        if (totalLen < 30) return null
        if (title.isBlank() && paragraphs.size < 2) return null

        val finalTitle = title.ifBlank {
            paragraphs.first().take(60)
        }
        return ArticleText(index, finalTitle, paragraphs)
    }
}
