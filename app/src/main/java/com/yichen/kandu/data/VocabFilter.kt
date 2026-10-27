package com.yichen.kandu.data

/**
 * 本地候选生词粗筛：把文章分词后与级别词表比对，不在词表里的词作为候选，
 * 交给大模型最终判断（避免每篇都让模型从零猜词表）。
 */
object VocabFilter {

    private val TOKEN = Regex("[A-Za-z][A-Za-z'-]*")

    /** 极常见但词书常不收的功能词/缩写，直接排除 */
    private val ALWAYS_KNOWN = setOf(
        "mr", "mrs", "ms", "dr", "st", "jr", "sr", "ok", "okay", "tv", "ai",
        "don't", "doesn't", "didn't", "won't", "wouldn't", "couldn't", "shouldn't",
        "isn't", "aren't", "wasn't", "weren't", "hasn't", "haven't", "hadn't",
        "can't", "cannot", "mustn't", "needn't", "ain't", "let's", "that's",
        "there's", "it's", "he's", "she's", "i'm", "i've", "i'll", "i'd",
        "you're", "you've", "you'll", "we're", "we've", "we'll", "they're",
        "they've", "they'll", "gonna", "wanna", "www", "http", "https", "com",
        "org", "pdf", "epub", "app", "vs"
    )

    /** 生成粗略原形变体，命中任意一个即视为「词表内」 */
    fun lemmaForms(word: String): List<String> {
        val w = word.lowercase()
        val forms = ArrayList<String>()
        forms += w
        if (w.endsWith("'s")) forms += w.removeSuffix("'s")
        if (w.endsWith("s") && !w.endsWith("ss")) forms += w.dropLast(1)
        if (w.endsWith("es")) forms += w.dropLast(2)
        if (w.endsWith("ies")) forms += w.dropLast(3) + "y"
        if (w.endsWith("ed")) {
            forms += w.dropLast(1)
            forms += w.dropLast(2)
            if (w.endsWith("ied")) forms += w.dropLast(3) + "y"
        }
        if (w.endsWith("ing")) {
            forms += w.dropLast(3)
            forms += w.dropLast(3) + "e"
            if (w.endsWith("ying")) forms += w.dropLast(4) + "ie"
        }
        return forms.distinct()
    }

    /**
     * 返回每段中「疑似超纲」的候选词（原形，小写，去重，保持出现顺序，每段上限 50 个）。
     * paragraphTexts 的下标即段落编号。
     */
    fun candidates(paragraphTexts: List<String>, dict: Set<String>): List<String> {
        val out = LinkedHashSet<String>()
        for (text in paragraphTexts) {
            for (m in TOKEN.findAll(text)) {
                val raw = m.value.trim('\'', '-')
                if (raw.isEmpty() || raw.length < 3) continue
                if (raw.lowercase() in ALWAYS_KNOWN) continue
                if (lemmaForms(raw).any { it in dict }) continue
                val base = lemmaForms(raw).minByOrNull { it.length } ?: raw.lowercase()
                if (out.add(base) && out.size >= 50) return out.toList()
            }
        }
        return out.toList()
    }
}
