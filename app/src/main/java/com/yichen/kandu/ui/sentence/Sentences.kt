package com.yichen.kandu.ui.sentence

/** 句子在段落文本中的范围 */
data class Sent(val start: Int, val end: Int, val text: String)

private val ENDERS = ".!?".toSet()
private val CLOSERS = "\"')]”’".toSet()
private val ABBREV = setOf(
    "mr", "mrs", "ms", "dr", "prof", "st", "jr", "sr", "vs", "etc",
    "inc", "ltd", "no", "fig", "u.s", "u.k", "d.c", "al"
)

/**
 * 把段落文本切成句子（含偏移量）。对 U.S. / Mr. 等常见缩写做了简单豁免，
 * 精度足够「双击取句」使用。
 */
fun splitSentences(text: String): List<Sent> {
    val out = ArrayList<Sent>()
    var start = 0
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c in ENDERS) {
            var j = i + 1
            while (j < text.length && (text[j] in ENDERS || text[j] in CLOSERS)) j++
            var isEnd = true
            if (j < text.length && text[j] == ' ' && j + 1 < text.length && text[j + 1].isLowerCase()) {
                // 空格后接小写，疑似缩写：检查句点前的 token
                val tokenStart = (text.lastIndexOf(' ', i - 1).takeIf { it >= 0 } ?: -1) + 1
                val token = text.substring(tokenStart, j).trim()
                val stripped = token.trimEnd('.', '!', '?').lowercase()
                if (stripped in ABBREV || Regex("^[a-z]([.-][a-z])[.]?$", RegexOption.IGNORE_CASE).matches(stripped)) {
                    isEnd = false
                }
            }
            if (isEnd && j > start) {
                val piece = text.substring(start, j).trim()
                if (piece.isNotBlank()) out += Sent(start, j, piece)
                start = j
            }
            i = j
        } else {
            i++
        }
    }
    if (start < text.length) {
        val piece = text.substring(start).trim()
        if (piece.isNotBlank()) out += Sent(start, text.length, piece)
    }
    return out
}

/** 找到偏移所在/最近的句子 */
fun sentenceAt(sents: List<Sent>, offset: Int): Sent? {
    if (sents.isEmpty()) return null
    return sents.firstOrNull { offset >= it.start && offset < it.end }
        ?: if (offset <= sents.first().start) sents.first() else sents.last()
}

/** 双击/长按取上下文时用：前后句文本 */
fun neighbors(sents: List<Sent>, sent: Sent): Pair<String, String> {
    val idx = sents.indexOfFirst { it.start == sent.start && it.end == sent.end }
    val before = if (idx > 0) sents[idx - 1].text else ""
    val after = if (idx in 0 until sents.size - 1) sents[idx + 1].text else ""
    return before to after
}
