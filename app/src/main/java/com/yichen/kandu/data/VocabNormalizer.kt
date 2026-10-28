package com.yichen.kandu.data

/**
 * 生词表规范化：分块生成时模型可能按块内顺序重新编号段落，导致同一 index
 * 出现多个小节（重复）。段落的 startWords 是可靠锚点——用它反查真实段落号
 * 重新编号，同段合并去重。对旧缓存数据同样生效（读取时调用即可自愈）。
 */
object VocabNormalizer {

    fun normalize(vocab: ArticleVocab, article: ArticleText): ArticleVocab {
        if (vocab.paragraphs.isEmpty() || article.paragraphs.isEmpty()) return vocab

        fun norm(s: String): String = s.lowercase().replace(Regex("\\s+"), " ").trim()
        val paraNorm = article.paragraphs.map { norm(it) }
        val n = article.paragraphs.size

        // 为每个模型返回的小节定位真实段落号
        data class Trial(val section: ParagraphVocab, val target: Int)

        val trials = vocab.paragraphs.map { pv ->
            val sw = norm(pv.startWords).trimEnd(',', ':', '.', '!', '?', '…', ';')
            var target = -1
            var bestScore = 0
            if (sw.length >= 6) {
                for ((i, p) in paraNorm.withIndex()) {
                    val score = when {
                        p.startsWith(sw) -> sw.length + 1000 // 整段开头精确一致：最优先
                        p.take(150).contains(sw) -> sw.length
                        else -> 0
                    }
                    if (score > bestScore) {
                        bestScore = score
                        target = i
                    }
                }
            }
            // 定位失败：沿用原编号（收敛到合法范围）
            Trial(pv, if (target >= 0) target else pv.index.coerceIn(0, n - 1))
        }

        // 按真实段落号合并；同段词条按 word 去重（保留先出现的）
        val mergedEntries = LinkedHashMap<Int, MutableList<VocabEntry>>()
        val mergedStartWords = HashMap<Int, String>()
        for (t in trials) {
            val list = mergedEntries.getOrPut(t.target) { mutableListOf() }
            if (!mergedStartWords.containsKey(t.target)) {
                mergedStartWords[t.target] = t.section.startWords
            }
            for (e in t.section.entries) {
                if (list.none { it.word == e.word }) list.add(e)
            }
        }

        val merged = mergedEntries.map { (idx, entries) ->
            ParagraphVocab(
                index = idx,
                startWords = mergedStartWords[idx] ?: "",
                entries = entries
            )
        }.sortedBy { it.index }

        return vocab.copy(paragraphs = merged)
    }
}
