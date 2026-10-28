package com.yichen.kandu.data

/**
 * 生词表规范化：分块生成时模型可能按块内顺序重新编号段落，导致同一 index
 * 出现多个小节（重复）；模型给的小节开头词也可能不准。
 *
 * 定位策略（优先级从高到低）：
 * 1. 词条级：词条的 inText 是原文短语，直接在正文中反查其所在段落；
 * 2. 小节级：小节 startWords 与段落开头做前缀/包含匹配；
 * 3. 兜底：模型原编号（收敛到合法范围）。
 *
 * 输出的小节开头词一律取正文中该段的真实开头，保证与阅读页对得上。
 * 对旧缓存数据同样生效（读取时调用即可自愈）。
 */
object VocabNormalizer {

    fun normalize(vocab: ArticleVocab, article: ArticleText): ArticleVocab {
        if (vocab.paragraphs.isEmpty() || article.paragraphs.isEmpty()) return vocab

        val n = article.paragraphs.size

        fun norm(s: String): String = s.lowercase().replace(Regex("\\s+"), " ").trim()
        val paraNorm = article.paragraphs.map { norm(it) }

        // 小节级锚点：startWords 前缀/包含匹配
        fun sectionAnchor(pv: ParagraphVocab): Int {
            val sw = norm(pv.startWords).trimEnd(',', ':', '.', '!', '?', '…', ';')
            if (sw.length < 6) return pv.index.coerceIn(0, n - 1)
            var best = -1
            var bestScore = 0
            for ((i, p) in paraNorm.withIndex()) {
                val score = when {
                    p.startsWith(sw) -> sw.length + 1000 // 整段开头精确一致：最优先
                    p.take(150).contains(sw) -> sw.length
                    else -> 0
                }
                if (score > bestScore) {
                    bestScore = score
                    best = i
                }
            }
            return if (best >= 0) best else pv.index.coerceIn(0, n - 1)
        }

        // 词条级锚点：inText 原文短语反查所在段落
        fun entryAnchor(inText: String, claimed: Int, anchor: Int): Int? {
            val t = norm(inText)
            if (t.length < 10) return null // 短语太短容易撞多处
            val hits = ArrayList<Int>()
            for ((i, p) in paraNorm.withIndex()) {
                if (p.contains(t)) hits.add(i)
            }
            if (hits.isEmpty()) return null
            if (hits.size == 1) return hits[0]
            if (hits.contains(claimed)) return claimed
            if (hits.contains(anchor)) return anchor
            return hits.first()
        }

        data class Placement(val paragraph: Int, val entry: VocabEntry)

        val placed = ArrayList<Placement>()
        for (pv in vocab.paragraphs) {
            val anchor = sectionAnchor(pv)
            val claimed = pv.index.coerceIn(0, n - 1)
            for (e in pv.entries) {
                placed.add(Placement(entryAnchor(e.inText, claimed, anchor) ?: anchor, e))
            }
        }

        // 按段落归组，同段词条按 word 去重（保留先出现的）
        val grouped = LinkedHashMap<Int, MutableList<VocabEntry>>()
        for (p in placed) {
            val list = grouped.getOrPut(p.paragraph) { mutableListOf() }
            if (list.none { it.word == p.entry.word }) list.add(p.entry)
        }

        val merged = grouped.map { (idx, entries) ->
            ParagraphVocab(
                index = idx,
                startWords = article.paragraphs[idx].take(30).trim(), // 真实正文开头
                entries = entries
            )
        }.sortedBy { it.index }

        return vocab.copy(paragraphs = merged)
    }
}
