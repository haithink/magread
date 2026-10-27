package com.yichen.kandu.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * 刊物库：SAF 目录扫描、封面缩略图、正文解析缓存、摘要/生词/句解缓存。
 * 目录布局（filesDir 下）：
 *   books/<id>.<ext>          原文件缓存副本
 *   covers/<id>.jpg           封面缩略图
 *   cache/<id>/content.json   解析后的文章文本
 *   cache/<id>/digest.json    摘要
 *   cache/<id>/vocab_<n>_<level>.json   生词表
 *   cache/<id>/analysis_<sha>.json      句解
 */
object BookStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val BAD_ID = Regex("[^A-Za-z0-9._-]")

    // ---------------- 扫描 ----------------

    fun scan(context: Context, treeUri: String): List<BookMeta> {
        if (treeUri.isBlank()) return emptyList()
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return emptyList()
        return tree.listFiles().mapNotNull { df ->
            if (!df.isFile) return@mapNotNull null
            val name = df.name ?: return@mapNotNull null
            val lower = name.lowercase()
            val ext = when {
                lower.endsWith(".epub") -> "epub"
                lower.endsWith(".pdf") -> "pdf"
                else -> return@mapNotNull null
            }
            BookMeta(
                id = sanitizeId(name.removeSuffix(".epub").removeSuffix(".pdf")),
                fileName = name,
                ext = ext,
                size = df.length(),
                lastModified = df.lastModified(),
                uri = df.uri.toString()
            )
        }.sortedByDescending { it.fileName }
    }

    fun sanitizeId(name: String): String =
        BAD_ID.replace(name, "_").ifBlank { "book" }

    // ---------------- 目录工具 ----------------

    private fun booksDir(context: Context) = File(context.filesDir, "books").apply { mkdirs() }
    private fun coversDir(context: Context) = File(context.filesDir, "covers").apply { mkdirs() }
    private fun cacheDir(context: Context, id: String) =
        File(File(context.filesDir, "cache"), id).apply { mkdirs() }

    fun coverFile(context: Context, id: String) = File(coversDir(context), "$id.jpg")

    fun hasCover(context: Context, id: String) = coverFile(context, id).exists()

    // ---------------- 原文件副本 ----------------

    suspend fun ensureBookFile(context: Context, meta: BookMeta): File = withContext(Dispatchers.IO) {
        val f = File(booksDir(context), "${meta.id}.${meta.ext}")
        if (!f.exists() || f.length() != meta.size) {
            context.contentResolver.openInputStream(Uri.parse(meta.uri))?.use { input ->
                f.outputStream().use { output -> input.copyTo(output) }
            } ?: error("无法打开刊物文件：${meta.fileName}")
        }
        f
    }

    // ---------------- 封面 ----------------

    suspend fun ensureCover(context: Context, meta: BookMeta): File? = withContext(Dispatchers.IO) {
        val out = coverFile(context, meta.id)
        if (out.exists()) return@withContext out
        val bytes: ByteArray? = when (meta.ext) {
            "epub" -> context.contentResolver.openInputStream(Uri.parse(meta.uri))?.use {
                EpubParser.extractCoverStream(it)
            }
            else -> {
                val f = runCatching { ensureBookFile(context, meta) }.getOrNull()
                f?.let { pdf -> PdfParser.renderCover(pdf)?.toJpegBytes() }
            }
        }
        if (bytes != null) {
            out.outputStream().use { it.write(bytes) }
            out
        } else null
    }

    private fun Bitmap.toJpegBytes(): ByteArray {
        val bos = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, 85, bos)
        return bos.toByteArray()
    }

    // ---------------- 正文解析缓存 ----------------

    suspend fun ensureContent(context: Context, meta: BookMeta): BookContent = withContext(Dispatchers.IO) {
        val cf = File(cacheDir(context, meta.id), "content.json")
        if (cf.exists()) {
            runCatching {
                val cache = json.decodeFromString(ContentCache.serializer(), cf.readText())
                if (cache.bookSize == meta.size) {
                    return@withContext BookContent(
                        bookId = meta.id,
                        format = cache.format,
                        articles = cache.articles.map { it.toArticleText() }
                    )
                }
            }
        }
        val file = ensureBookFile(context, meta)
        val content = when (meta.ext) {
            "epub" -> {
                val data = EpubParser.parse(file)
                BookContent(meta.id, "epub", data.articles)
            }
            else -> {
                val pages = PdfParser.extractPages(file)
                val title = meta.id
                val paragraphs = if (pages.isEmpty()) listOf("(PDF 文本提取失败)") else pages
                BookContent(meta.id, "pdf", listOf(ArticleText(0, title, paragraphs)))
            }
        }
        runCatching {
            cf.writeText(
                json.encodeToString(
                    ContentCache.serializer(),
                    ContentCache(meta.size, content.format, content.articles.map { CachedArticle.of(it) })
                )
            )
        }
        content
    }

    // ---------------- 摘要缓存 ----------------

    fun loadDigest(context: Context, id: String): BookDigest? {
        val f = File(cacheDir(context, id), "digest.json")
        if (!f.exists()) return null
        return runCatching { json.decodeFromString(BookDigest.serializer(), f.readText()) }.getOrNull()
    }

    fun saveDigest(context: Context, digest: BookDigest) {
        File(cacheDir(context, digest.bookId), "digest.json")
            .writeText(json.encodeToString(BookDigest.serializer(), digest))
    }

    fun hasDigest(context: Context, id: String): Boolean =
        File(cacheDir(context, id), "digest.json").exists()

    // ---------------- 生词表缓存 ----------------

    fun loadVocab(context: Context, id: String, articleIndex: Int, level: String): ArticleVocab? {
        val f = File(cacheDir(context, id), "vocab_${articleIndex}_$level.json")
        if (!f.exists()) return null
        return runCatching { json.decodeFromString(ArticleVocab.serializer(), f.readText()) }.getOrNull()
    }

    fun saveVocab(context: Context, vocab: ArticleVocab, id: String) {
        File(cacheDir(context, id), "vocab_${vocab.articleIndex}_${vocab.level}.json")
            .writeText(json.encodeToString(ArticleVocab.serializer(), vocab))
    }

    // ---------------- 句解缓存 ----------------

    private fun analysisFile(context: Context, id: String, key: String): File =
        File(cacheDir(context, id), "analysis_$key.json")

    fun loadAnalysis(context: Context, id: String, text: String, articleIndex: Int): SentenceAnalysis? {
        val f = analysisFile(context, id, analysisKey(id, articleIndex, text))
        if (!f.exists()) return null
        return runCatching { json.decodeFromString(SentenceAnalysis.serializer(), f.readText()) }.getOrNull()
    }

    fun saveAnalysis(context: Context, id: String, text: String, articleIndex: Int, analysis: SentenceAnalysis) {
        analysisFile(context, id, analysisKey(id, articleIndex, text))
            .writeText(json.encodeToString(SentenceAnalysis.serializer(), analysis))
    }

    fun analysisKey(id: String, articleIndex: Int, text: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest("$id|$articleIndex|$text".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ---------------- 清理 ----------------

    /** 删除某本书的全部缓存（含正文缓存、摘要、生词、句解与副本） */
    fun purgeBook(context: Context, id: String) {
        cacheDir(context, id).deleteRecursively()
        File(booksDir(context), "$id.epub").delete()
        File(booksDir(context), "$id.pdf").delete()
        coverFile(context, id).delete()
    }

    /** 清空全部缓存与副本（不动设置与词库） */
    fun purgeAll(context: Context) {
        File(context.filesDir, "cache").deleteRecursively()
        booksDir(context).deleteRecursively()
        coversDir(context).deleteRecursively()
    }

    fun cacheSizeBytes(context: Context): Long =
        (File(context.filesDir, "cache").walk()
            .filter { it.isFile }.sumOf { it.length() }) +
            booksDir(context).walk().filter { it.isFile }.sumOf { it.length() } +
            coversDir(context).walk().filter { it.isFile }.sumOf { it.length() }
}
