package com.yichen.kandu.llm

import android.content.Context
import com.yichen.kandu.data.AnalysisVocab
import com.yichen.kandu.data.ArticleText
import com.yichen.kandu.data.ArticleVocab
import com.yichen.kandu.data.BookMeta
import com.yichen.kandu.data.BookDigest
import com.yichen.kandu.data.BookStore
import com.yichen.kandu.data.DigestArticle
import com.yichen.kandu.data.GrammarPoint
import com.yichen.kandu.data.IdiomEntry
import com.yichen.kandu.data.LlmConfig
import com.yichen.kandu.data.ParagraphVocab
import com.yichen.kandu.data.SentenceAnalysis
import com.yichen.kandu.data.VocabEntry
import com.yichen.kandu.data.VocabFilter
import com.yichen.kandu.data.VocabLevels
import com.yichen.kandu.data.VocabNormalizer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 生成任务编排：摘要 → 生词表 → 句解，全部结果落盘缓存 */
object GenService {

    class GenException(message: String) : Exception(message)

    /** 带一次自动重试的聊天调用：网络抖动 / 推理模型偶发无正文时更稳 */
    private suspend fun chatRetry(
        cfg: LlmConfig,
        system: String,
        user: String,
        maxTokens: Int,
        onChars: ((Int) -> Unit)? = null
    ): String {
        var last: Exception? = null
        repeat(2) { i ->
            try {
                return LlmClient.chat(cfg, system, user, maxTokens, onDelta = onChars)
            } catch (e: Exception) {
                last = e
                if (i == 0) kotlinx.coroutines.delay(1500)
            }
        }
        throw last ?: GenException("调用失败")
    }

    // ---------------- 摘要 ----------------

    /**
     * 生成整本刊物摘要。分批（每批 6 篇）请求，逐批落盘，
     * onProgress(current, total) 中的 total 为批数。
     */
    suspend fun generateDigest(
        context: Context,
        meta: BookMeta,
        cfg: LlmConfig,
        onProgress: suspend (Int, Int) -> Unit,
        onChars: ((Int) -> Unit)? = null
    ): BookDigest {
        val content = BookStore.ensureContent(context, meta)
        val articles = content.articles
        if (articles.isEmpty()) throw GenException("该文件没有解析出文章")

        val batches = articles.chunked(6)
        val collected = ArrayList<DigestArticle>()
        val existing = BookStore.loadDigest(context, meta.id)?.articles?.associateBy { it.index } ?: emptyMap()

        for ((bi, batch) in batches.withIndex()) {
            onProgress(bi, batches.size)
            val items = batch.map { Triple(it.index, it.title, it.plainText.take(1600)) }
            val user = Prompts.digestUser(meta.fileName, items)
            val raw = chatRetry(cfg, Prompts.digestSystem(), user, maxTokens = 8000, onChars = onChars)
            val arr = parseJsonArray(raw)
            for (el in arr) {
                val o = el as? JsonObject ?: continue
                val index = o["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: continue
                val article = articles.firstOrNull { it.index == index } ?: continue
                collected += DigestArticle(
                    index = index,
                    titleEn = o.str("titleEn").ifBlank { article.title },
                    titleZh = o.str("titleZh"),
                    summaryZh = o.str("summaryZh")
                )
            }
            // 批内缺漏用已有缓存或空标题占位，保证列表完整
            val merged = (collected.map { it.index } + existing.keys).toSet()
            val missing = batch.filter { it.index !in merged }
            for (m in missing) {
                collected += DigestArticle(m.index, m.title, "", "（本篇摘要生成失败，可点此篇后再试）")
            }
            val digest = BookDigest(
                bookId = meta.id,
                articles = (collected + existing.values)
                    .distinctBy { it.index }
                    .sortedBy { it.index },
                generatedAt = System.currentTimeMillis()
            )
            BookStore.saveDigest(context, digest)
            onProgress(bi + 1, batches.size)
        }
        return BookStore.loadDigest(context, meta.id) ?: throw GenException("摘要生成失败")
    }

    // ---------------- 逐段生词表 ----------------

    /**
     * 生成某篇文章的生词表：先用本地 CET-4 词表粗筛候选，再分块交给大模型
     * 确认/补充/释义。标题作为独立单元（index = -1），对齐固定生词表格式。
     */
    suspend fun generateVocab(
        context: Context,
        meta: BookMeta,
        article: ArticleText,
        level: String,
        cfg: LlmConfig,
        onProgress: suspend (Int, Int) -> Unit,
        onChars: ((Int) -> Unit)? = null
    ): ArticleVocab {
        val dict = VocabLevels.wordSet(context, level)
        val levelLabel = VocabLevels.labelOf(level)

        // 单元：标题(-1) + 段落(0..n-1)
        data class Seg(val index: Int, val text: String)
        val units = ArrayList<Seg>()
        units += Seg(-1, article.title)
        article.paragraphs.forEachIndexed { i, p -> units += Seg(i, p) }

        val candidates = if (dict.isEmpty()) emptyMap() else {
            buildMap {
                for (u in units) {
                    val list = if (u.index == -1) emptyList() else VocabFilter.candidates(listOf(u.text), dict)
                    put(u.index, list)
                }
            }
        }

        // 分块：标题块 + 按字符数分块的正文
        val chunks = ArrayList<List<Seg>>()
        var current = ArrayList<Seg>()
        var chars = 0
        for (u in units) {
            if (u.index == -1) {
                chunks += listOf(u)
                continue
            }
            if (chars + u.text.length > 4500 && current.isNotEmpty()) {
                chunks += current
                current = ArrayList()
                chars = 0
            }
            current += u
            chars += u.text.length
        }
        if (current.isNotEmpty()) chunks += current

        val paragraphsOut = ArrayList<ParagraphVocab>()
        val titleEntries = ArrayList<VocabEntry>()
        val idiomsOut = ArrayList<IdiomEntry>()
        val seen = HashSet<String>()

        for ((ci, chunk) in chunks.withIndex()) {
            onProgress(ci, chunks.size)
            val user = Prompts.vocabUser(article.title, -1, chunk.map { it.index to it.text }, candidates)
            onChars?.invoke(0)
            val raw = chatRetry(cfg, Prompts.vocabSystem(levelLabel), user, maxTokens = 16000, onChars = onChars)
            val obj = parseJsonObject(raw)

            for (p in obj.arr("paragraphs")) {
                val po = p as? JsonObject ?: continue
                val idx = po["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: continue
                val entries = po.arr("entries").mapNotNull { parseEntry(it, seen) }
                if (idx < 0) {
                    // 模型把标题也放进了 paragraphs：归入标题生词
                    titleEntries += entries
                    continue
                }
                val startWords = po.str("startWords").ifBlank {
                    article.paragraphs.getOrNull(idx)?.take(30) ?: ""
                }
                paragraphsOut += ParagraphVocab(idx, startWords, entries)
            }
            for (e in obj.arr("titleEntries")) {
                parseEntry(e, seen)?.let { titleEntries += it }
            }
            for (idiom in obj.arr("idioms")) {
                val io = idiom as? JsonObject ?: continue
                val w = io.str("word")
                if (w.isBlank()) continue
                idiomsOut += IdiomEntry(w, io.str("common"), io.str("inText"))
            }
            onProgress(ci + 1, chunks.size)
        }

        val result = VocabNormalizer.normalize(
            ArticleVocab(
                level = level,
                articleIndex = article.index,
                titleEntries = titleEntries,
                paragraphs = paragraphsOut.sortedBy { it.index },
                idioms = idiomsOut.distinctBy { it.word.lowercase() }
            ),
            article
        )
        BookStore.saveVocab(context, result, meta.id)
        return result
    }

    private fun parseEntry(el: kotlinx.serialization.json.JsonElement, seen: MutableSet<String>): VocabEntry? {
        val o = el as? JsonObject ?: return null
        val word = o.str("word").trim().lowercase()
        if (word.isBlank()) return null
        val dedup = "$word|${o.str("sense").take(20)}"
        if (!seen.add(dedup)) return null
        return VocabEntry(
            word = word,
            phonetic = o.str("phonetic"),
            pos = o.str("pos"),
            sense = o.str("sense").ifBlank { "（模型未给释义）" },
            inText = o.str("inText"),
            root = o.str("root"),
            term = o["term"]?.jsonPrimitive?.content == "true"
        )
    }

    // ---------------- 句解 ----------------

    suspend fun analyze(
        context: Context,
        meta: BookMeta,
        article: ArticleText,
        sentence: String,
        before: String,
        after: String,
        cfg: LlmConfig
    ): SentenceAnalysis {
        BookStore.loadAnalysis(context, meta.id, sentence, article.index)?.let { return it }
        val user = Prompts.analysisUser(sentence, before, after, article.title)
        val raw = chatRetry(cfg, Prompts.analysisSystem(), user, maxTokens = 8000)
        val obj = parseJsonObject(raw)
        val analysis = SentenceAnalysis(
            vocab = obj.arr("vocab").mapNotNull { v ->
                val vo = v as? JsonObject ?: return@mapNotNull null
                val w = vo.str("word").trim()
                if (w.isBlank()) null else AnalysisVocab(
                    word = w,
                    phonetic = vo.str("phonetic"),
                    sense = vo.str("sense"),
                    note = vo.str("note")
                )
            },
            grammarDiagram = obj.str("grammarDiagram").replace("\\\\n", "\n"),
            grammarPoints = obj.arr("grammarPoints").mapNotNull { gp ->
                val g = gp as? JsonObject ?: return@mapNotNull null
                GrammarPoint(g.str("title"), g.str("text"))
            },
            context = obj.str("context"),
            translation = obj.str("translation")
        )
        BookStore.saveAnalysis(context, meta.id, sentence, article.index, analysis)
        return analysis
    }

    // ---------------- JSON 工具 ----------------

    private fun parseJsonArray(raw: String): JsonArray {
        val text = Prompts.extractJsonBlock(raw)
        return runCatching {
            Prompts.lenientJson.parseToJsonElement(text).jsonArray
        }.recoverCatching {
            val repaired = repairTruncatedJson(text)
                ?: throw GenException("模型输出不是有效 JSON：${raw.take(200)}")
            Prompts.lenientJson.parseToJsonElement(repaired).jsonArray
        }.getOrElse {
            throw GenException("模型输出不是有效 JSON（可能被长度截断）：${raw.take(200)}")
        }
    }

    private fun parseJsonObject(raw: String): JsonObject {
        val text = Prompts.extractJsonBlock(raw)
        return runCatching {
            Prompts.lenientJson.parseToJsonElement(text).jsonObject
        }.recoverCatching {
            val repaired = repairTruncatedJson(text)
                ?: throw GenException("模型输出不是有效 JSON：${raw.take(200)}")
            Prompts.lenientJson.parseToJsonElement(repaired).jsonObject
        }.getOrElse {
            throw GenException("模型输出不是有效 JSON（可能被长度截断）：${raw.take(200)}")
        }
    }

    /**
     * 截断 JSON 的兜底修补：裁掉残缺字符串 / 悬空键值，补全未闭合的括号。
     * 只保住已完整生成的部分（例如生词表的前 N 条），比整块失败好。
     */
    private fun repairTruncatedJson(text: String): String? {
        var s = text.trim()
        if (s.length < 2) return null

        // 尾部挂在字符串中间：裁到上一个引号
        var inString = false
        var escape = false
        for (c in s) {
            if (escape) { escape = false; continue }
            when {
                c == '\\' && inString -> escape = true
                c == '"' -> inString = !inString
            }
        }
        if (inString) {
            val cut = s.lastIndexOf('"')
            if (cut <= 0) return null
            s = s.substring(0, cut)
        }
        s = s.trimEnd().trimEnd(',', ':').trimEnd()
        // 悬空的 "key"：值没生成
        s = s.replace(Regex(",?\\s*\"[^\"]*\"\\s*$"), "").trimEnd().trimEnd(',', ':')

        // 补全未闭合的 { 与 [
        val stack = ArrayDeque<Char>()
        inString = false; escape = false
        for (c in s) {
            if (escape) { escape = false; continue }
            when {
                c == '\\' && inString -> escape = true
                c == '"' -> inString = !inString
                c == '{' && !inString -> stack.addLast('}')
                c == '[' && !inString -> stack.addLast(']')
                c == '}' && !inString -> if (stack.isNotEmpty() && stack.last() == '}') stack.removeLast()
                c == ']' && !inString -> if (stack.isNotEmpty() && stack.last() == ']') stack.removeLast()
            }
        }
        while (stack.isNotEmpty()) s += stack.removeLast()
        return s
    }

    private fun JsonObject.str(key: String): String {
        val v = this[key] ?: return ""
        if (v is kotlinx.serialization.json.JsonNull) return ""
        return runCatching { v.jsonPrimitive.content }.getOrDefault("")
    }

    private fun JsonObject.arr(key: String): JsonArray {
        val v = this[key] ?: return JsonArray(emptyList())
        return runCatching { v.jsonArray }.getOrDefault(JsonArray(emptyList()))
    }
}
