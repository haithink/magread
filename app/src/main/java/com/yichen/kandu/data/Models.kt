package com.yichen.kandu.data

import kotlinx.serialization.Serializable

/** 大模型接口配置（OpenAI 兼容 / Anthropic 原生） */
@Serializable
data class LlmConfig(
    val provider: String = PROVIDER_OPENAI,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = ""
) {
    val isComplete: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    companion object {
        const val PROVIDER_OPENAI = "openai"
        const val PROVIDER_ANTHROPIC = "anthropic"
    }
}

/** 应用设置（settings.json） */
@Serializable
data class AppSettings(
    val vocabLevel: String = VocabLevels.CET4,
    val levelChosen: Boolean = false,
    val libraryUri: String = "",
    val llm: LlmConfig = LlmConfig(),
    val readerFontSizeSp: Float = 17f
)

/** 刊物文件（来自 SAF 目录扫描） */
@Serializable
data class BookMeta(
    val id: String,
    val fileName: String,
    val ext: String,
    val size: Long,
    val lastModified: Long,
    val uri: String
)

@Serializable
data class DigestArticle(
    val index: Int,
    val titleEn: String,
    val titleZh: String,
    val summaryZh: String
)

/** 整本刊物的文章摘要缓存 */
@Serializable
data class BookDigest(
    val bookId: String,
    val articles: List<DigestArticle>,
    val generatedAt: Long = 0
)

/** 单个生词词条（对齐生词表固定格式：音标/词性/释义/原文短语/词根拆解/术语标记） */
@Serializable
data class VocabEntry(
    val word: String,
    val phonetic: String = "",
    val pos: String = "",
    val sense: String,
    val inText: String = "",
    val root: String = "",
    val term: Boolean = false
)

/** 熟词僻义：常见义 vs 本文义 */
@Serializable
data class IdiomEntry(val word: String, val common: String, val inText: String)

/** 逐段生词 */
@Serializable
data class ParagraphVocab(
    val index: Int,
    val startWords: String,
    val entries: List<VocabEntry>
)

/** 一篇文章的生词表缓存 */
@Serializable
data class ArticleVocab(
    val level: String,
    val articleIndex: Int,
    val titleEntries: List<VocabEntry> = emptyList(),
    val paragraphs: List<ParagraphVocab> = emptyList(),
    val idioms: List<IdiomEntry> = emptyList()
) {
    fun entriesFor(paragraph: Int): List<VocabEntry> =
        paragraphs.firstOrNull { it.index == paragraph }?.entries ?: emptyList()

    val total: Int
        get() = titleEntries.size + paragraphs.sumOf { it.entries.size }
}

@Serializable
data class AnalysisVocab(
    val word: String,
    val phonetic: String = "",
    val sense: String,
    val note: String = ""
)

@Serializable
data class GrammarPoint(val title: String = "", val text: String = "")

/** 句解（对齐生词表/句解固定格式：疑难词汇→语法图解→语境→参考译文） */
@Serializable
data class SentenceAnalysis(
    val vocab: List<AnalysisVocab> = emptyList(),
    val grammarDiagram: String = "",
    val grammarPoints: List<GrammarPoint> = emptyList(),
    val context: String = "",
    val translation: String = ""
)

// ---------- 运行时模型（不落盘） ----------

data class ArticleText(
    val index: Int,
    val title: String,
    val paragraphs: List<String>
) {
    val plainText: String get() = paragraphs.joinToString("\n\n")
}

data class BookContent(
    val bookId: String,
    val format: String,
    val articles: List<ArticleText>
)

/** content.json 缓存包装（记录对应源文件大小，用于失效判断） */
@Serializable
data class ContentCache(val bookSize: Long, val format: String, val articles: List<CachedArticle>)

@Serializable
data class CachedArticle(val index: Int, val title: String, val paragraphs: List<String>) {
    fun toArticleText() = ArticleText(index, title, paragraphs)

    companion object {
        fun of(a: ArticleText) = CachedArticle(a.index, a.title, a.paragraphs)
    }
}
