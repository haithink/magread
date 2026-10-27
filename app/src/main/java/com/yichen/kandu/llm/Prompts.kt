package com.yichen.kandu.llm

import kotlinx.serialization.json.Json

/**
 * 提示词构建。输出格式对齐用户的固定精读格式：
 * - 生词表：标题生词单列 → 熟词僻义 → 逐段（节头带段落开头词定位），词条带音标/词性/释义/原文短语/词根拆解/术语标记
 * - 句解：疑难词汇 → 语法对齐图解 → 语境 → 参考译文
 */
object Prompts {

    val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 从模型输出里提取 JSON 文本（剥掉 ``` 围栏、前后杂话） */
    fun extractJsonBlock(text: String): String {
        var t = text.trim()
        val fence = Regex("```(?:json)?\\s*(.*?)```", RegexOption.DOT_MATCHES_ALL)
        fence.find(t)?.let { t = it.groupValues[1].trim() }
        val start = t.indexOfFirst { it == '{' || it == '[' }
        if (start > 0) t = t.substring(start)
        val endObj = t.lastIndexOf('}')
        val endArr = t.lastIndexOf(']')
        val end = maxOf(endObj, endArr)
        if (end in 0 until t.length - 1) t = t.substring(0, end + 1)
        return t.trim()
    }

    // ---------------- 摘要 ----------------

    fun digestSystem(): String =
        "你是资深外刊编辑和译者。用户会给你一本英文杂志中若干篇文章的标题和开头文本，" +
            "请为每一篇生成：1) titleEn：文章英文标题（若给出标题原样保留；若没有标题则根据开头拟出）；" +
            "2) titleZh：地道、简洁的中文标题（意译，不要直译腔）；3) summaryZh：100～150 字的中文内容摘要，" +
            "概括文章核心论点与关键信息，不用套话。只输出 JSON 数组，不要输出任何其他文字，不要展示思考过程。"

    fun digestUser(bookName: String, items: List<Triple<Int, String, String>>): String = buildString {
        appendLine("杂志文件：$bookName。以下是 ${items.size} 篇文章，编号 index 如下。")
        appendLine("只输出 JSON 数组，元素格式：{\"index\":编号,\"titleEn\":\"...\",\"titleZh\":\"...\",\"summaryZh\":\"...\"}")
        for ((index, title, head) in items) {
            appendLine()
            appendLine("### 文章 index=$index")
            if (title.isNotBlank()) appendLine("标题：$title")
            appendLine(head)
        }
    }

    // ---------------- 逐段生词表 ----------------

    fun vocabSystem(levelLabel: String): String =
        "你是英语外刊精读助教。学生的词汇量基准是「$levelLabel」：" +
            "四级及更常用的词一律不算生词，不要收录；只收录超纲词、专业术语、熟词僻义（常见词的非常用义项）。" +
            "专有名词（人名/地名/机构名）不收录。缩略语如 IMF、NATO 收录为术语。" +
            "每个词条给出：word（原形，小写）、phonetic（美式音标，没有把握就留空串）、" +
            "pos（词性，如 n. / v. / adj.）、sense（中文释义，取「本文语境下的义项」，不超过 25 字；" +
            "熟词僻义须写明「常见义 X；本文义 Y」）、inText（该词在原文中出现的短语，含前后 2～4 个词，不超过 40 字符）、" +
            "root（词根词缀拆解或词源线索，如「im- 不 + penetr 穿入 + -able」，不超过 40 字；没有词根故事的给空串，不强加）、" +
            "term（是否术语，布尔）。务必控制总输出长度，宁可精简也不要截断。" +
            "只输出 JSON 对象，格式：" +
            "{\"paragraphs\":[{\"index\":段落编号,\"startWords\":\"该段开头前几个单词\",\"entries\":[{...}]}]," +
            "\"titleEntries\":[标题生词，格式同 entries]," +
            "\"idioms\":[{\"word\":\"熟词僻义词\",\"common\":\"常见义\",\"inText\":\"本文义\"}]}" +
            "没有生词的段落也要有对应元素，entries 为空数组。不要输出 JSON 以外的任何文字，不要展示思考过程。"

    fun vocabUser(
        title: String,
        titleIndex: Int,
        units: List<Pair<Int, String>>,
        candidates: Map<Int, List<String>>
    ): String = buildString {
        appendLine("以下是文章《${title.take(80)}》的标题和逐段文本。段落编号如下。")
        appendLine()
        appendLine("### 标题（段落编号 $titleIndex）")
        appendLine(title)
        for ((index, text) in units) {
            appendLine()
            appendLine("### 段落 $index")
            appendLine(text.take(3500))
        }
        appendLine()
        appendLine("提示：以下是我按四级词表初步筛出的候选超纲词，供参考；请自行判断增删（尤其注意熟词僻义不在此列）：")
        for ((index, words) in candidates) {
            if (words.isEmpty()) continue
            appendLine("段落 $index：${words.joinToString(", ")}")
        }
    }

    // ---------------- 句解 ----------------

    fun analysisSystem(): String =
        "你是英语语法精讲老师，给词汇量 CET-4～6 的中国学生逐句精讲外刊。按固定格式输出 JSON：" +
            "1) vocab：疑难词汇数组 [{\"word\":\"\",\"phonetic\":\"美式音标\",\"sense\":\"中文释义；" +
            "熟词僻义须写「常见义 X；本文义 Y」\",\"note\":\"词根词缀拆解或说明，可空串\"}]，" +
            "只收学生可能不懂的词（四级内常见词不收），专有名词不收；" +
            "2) grammarDiagram：语法对齐图解——把英文原句按成分用空格排开（可多行），每个成分正下方对齐写中文成分名" +
            "（主语/谓语/宾语/形式主语/定语从句等），短语和从句用方括号 [] 框住，从属说明用 └── 前缀；" +
            "换行用 \\n；图解行与标注行必须上下对齐；" +
            "3) grammarPoints：编号语法点数组 [{\"title\":\"如「定语从句」\",\"text\":\"结合本句讲解\"}]；" +
            "4) context：该句在文章中的功能、与上下文的呼应、作者修辞意图，1～2 句；" +
            "5) translation：一两句自然的中文参考译文。只输出 JSON 对象，不要输出任何其他文字，不要展示思考过程。"

    fun analysisUser(sentence: String, contextBefore: String, contextAfter: String, title: String): String =
        buildString {
            appendLine("文章标题：${title.take(80)}")
            if (contextBefore.isNotBlank()) appendLine("上句：${contextBefore.take(200)}")
            appendLine("待解析句子：$sentence")
            if (contextAfter.isNotBlank()) appendLine("下句：${contextAfter.take(200)}")
        }
}
