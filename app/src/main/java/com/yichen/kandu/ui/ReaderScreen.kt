package com.yichen.kandu.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichen.kandu.data.AppSettings
import com.yichen.kandu.data.ArticleText
import com.yichen.kandu.data.ArticleVocab
import com.yichen.kandu.data.BookContent
import com.yichen.kandu.data.BookMeta
import com.yichen.kandu.data.BookStore
import com.yichen.kandu.data.SentenceAnalysis
import com.yichen.kandu.data.SettingsStore
import com.yichen.kandu.data.VocabNormalizer
import com.yichen.kandu.llm.GenService
import com.yichen.kandu.llm.LlmClient
import com.yichen.kandu.ui.sentence.neighbors
import com.yichen.kandu.ui.sentence.sentenceAt
import com.yichen.kandu.ui.sentence.splitSentences
import kotlinx.coroutines.launch

/** 阅读页弹层 */
private sealed interface Sheet {
    object None : Sheet
    object FullVocab : Sheet
    data class ParagraphVocab(val paragraph: Int) : Sheet
    data class Analysis(val paragraph: Int, val text: String, val before: String, val after: String) : Sheet
}

/**
 * 阅读页：
 * - 顶栏「全篇生词」；每段前缀按钮展示本段生词
 * - 双击句子 → 句解（词汇表/语法对齐图解/语境/参考译文）
 * - 长按段落 → 整段句解
 * - 生词表结果缓存本地，反复打开不再调用大模型
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    meta: BookMeta,
    articleIndex: Int,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(SettingsStore.load(context)) }
    var content by remember { mutableStateOf<BookContent?>(null) }
    var article by remember { mutableStateOf<ArticleText?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    var vocab by remember { mutableStateOf<ArticleVocab?>(null) }
    var vocabRunning by remember { mutableStateOf(false) }
    var vocabProgress by remember { mutableStateOf("") }
    var vocabChars by remember { mutableStateOf(0) }
    var vocabError by remember { mutableStateOf<String?>(null) }

    var sheet by remember { mutableStateOf<Sheet>(Sheet.None) }
    var analysis by remember { mutableStateOf<SentenceAnalysis?>(null) }
    var analysisRunning by remember { mutableStateOf(false) }
    var analysisError by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()

    fun saveSettings(transform: (AppSettings) -> AppSettings) {
        settings = transform(settings)
        SettingsStore.save(context, settings)
    }

    // 加载正文与缓存生词
    LaunchedEffect(meta.id, articleIndex) {
        try {
            val c = BookStore.ensureContent(context, meta)
            content = c
            val art = c.articles.firstOrNull { it.index == articleIndex }
            article = art
            if (art == null) {
                loadError = "找不到文章 #$articleIndex"
            } else {
                // normalize 兼顾旧缓存：分块生成的重复编号在读取时自动修复
                vocab = BookStore.loadVocab(context, meta.id, articleIndex, settings.vocabLevel)
                    ?.let { VocabNormalizer.normalize(it, art) }
            }
        } catch (e: Exception) {
            loadError = e.message ?: e.toString()
        }
    }

    // 弹层打开时执行句解（带缓存）
    LaunchedEffect(sheet) {
        val target = sheet as? Sheet.Analysis ?: return@LaunchedEffect
        analysis = null
        analysisError = null
        val art = article ?: return@LaunchedEffect
        val cached = BookStore.loadAnalysis(context, meta.id, target.text, art.index)
        if (cached != null) {
            analysis = cached
            return@LaunchedEffect
        }
        val cfg = SettingsStore.load(context).llm
        val invalid = LlmClient.validate(cfg)
        if (invalid != null) {
            analysisError = "$invalid（到设置里配置）"
            return@LaunchedEffect
        }
        analysisRunning = true
        try {
            analysis = GenService.analyze(context, meta, art, target.text, target.before, target.after, cfg)
        } catch (e: Exception) {
            analysisError = e.message ?: e.toString()
        } finally {
            analysisRunning = false
        }
    }

    fun generateVocab() {
        val art = article ?: return
        val cfg = SettingsStore.load(context).llm
        val invalid = LlmClient.validate(cfg)
        if (invalid != null) {
            vocabError = "$invalid（到设置里配置）"
            return
        }
        vocabRunning = true
        vocabError = null
        vocabChars = 0
        scope.launch {
            try {
                vocab = GenService.generateVocab(
                    context, meta, art, settings.vocabLevel, cfg,
                    onProgress = { done, total -> vocabProgress = "第 $done/$total 块" },
                    onChars = { vocabChars = it }
                )
            } catch (e: Exception) {
                vocabError = e.message ?: e.toString()
            } finally {
                vocabRunning = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            article?.title?.take(40) ?: "加载中…",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1
                        )
                        Text(
                            meta.fileName.removeSuffix(".${meta.ext}"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    IconButton(onClick = { saveSettings { it.copy(readerFontSizeSp = (it.readerFontSizeSp + 1f).coerceAtMost(28f)) } }) {
                        Icon(Icons.Filled.Add, contentDescription = "放大字号")
                    }
                    IconButton(onClick = { saveSettings { it.copy(readerFontSizeSp = (it.readerFontSizeSp - 1f).coerceAtLeast(12f)) } }) {
                        Icon(Icons.Filled.Remove, contentDescription = "缩小字号")
                    }
                    // 全篇生词入口
                    TextButton(onClick = {
                        if (vocab != null) sheet = Sheet.FullVocab
                        else if (!vocabRunning) generateVocab()
                    }) {
                        Icon(Icons.Filled.School, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(vocab?.let { "生词 ${it.total}" } ?: "生成生词表")
                    }
                }
            )
        }
    ) { padding ->
        val art = article
        if (loadError != null) {
            ErrorBanner(loadError!!, Modifier.padding(16.dp))
            return@Scaffold
        }
        if (art == null) {
            CenterLoading("解析刊物…")
            return@Scaffold
        }

        Box(Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                        Text(
                            art.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "${art.paragraphs.size} 段 · 双击句子看句解 · 长按整段解析 · 点段前按钮看本段生词",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
                if (vocabRunning) {
                    item {
                        Column(Modifier.padding(horizontal = 20.dp)) {
                            Text(
                                buildString {
                                    append("生词表生成中… ${vocabProgress.ifBlank { "准备中" }}")
                                    if (vocabChars > 0) append(" · 已接收 $vocabChars 字")
                                },
                                style = MaterialTheme.typography.labelSmall
                            )
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                        }
                    }
                }
                vocabError?.let {
                    item { ErrorBanner(it, Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) }
                }
                itemsIndexed(art.paragraphs, key = { i, _ -> "p$i" }) { idx, para ->
                    ParagraphRow(
                        paragraph = idx,
                        text = para,
                        fontSizeSp = settings.readerFontSizeSp,
                        vocabCount = vocab?.entriesFor(idx)?.size ?: 0,
                        onShowParagraphVocab = { sheet = Sheet.ParagraphVocab(idx) },
                        onAnalyzeSentence = { sentence, before, after ->
                            sheet = Sheet.Analysis(idx, sentence, before, after)
                        },
                        onAnalyzeParagraph = {
                            val t = para.take(2200)
                            sheet = Sheet.Analysis(idx, t, "", "")
                        }
                    )
                }
                item { Spacer(Modifier.height(48.dp)) }
            }
        }
    }

    // ---------------- 弹层 ----------------
    if (sheet != Sheet.None) {
        ModalBottomSheet(
            onDismissRequest = { sheet = Sheet.None },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            val title = when (val s = sheet) {
                is Sheet.ParagraphVocab -> "第 ${s.paragraph + 1} 段生词"
                Sheet.FullVocab -> "本篇生词表（${com.yichen.kandu.data.VocabLevels.labelOf(settings.vocabLevel)}）"
                is Sheet.Analysis -> "句解"
                else -> ""
            }
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    if (sheet == Sheet.FullVocab) {
                        TextButton(onClick = {
                            sheet = Sheet.None
                            generateVocab()
                        }) { Text("重新生成") }
                    }
                }
                when (val s = sheet) {
                    is Sheet.ParagraphVocab -> {
                        val v = vocab
                        if (v == null) {
                            Text(
                                "还没生成本篇生词表，点右上角「生成生词表」",
                                Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.outline
                            )
                        } else {
                            Column(
                                Modifier
                                    .heightIn(max = 520.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                VocabContent(v, onlyParagraph = s.paragraph)
                            }
                        }
                    }
                    Sheet.FullVocab -> {
                        val v = vocab
                        if (v == null) {
                            CenterLoading("")
                        } else {
                            Column(
                                Modifier
                                    .heightIn(max = 560.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                VocabContent(v)
                            }
                        }
                    }
                    is Sheet.Analysis -> {
                        Column(
                            Modifier
                                .heightIn(max = 560.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                            ) {
                                Text(
                                    s.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                            when {
                                analysisRunning -> CenterLoading("解析中…")
                                analysisError != null -> ErrorBanner(analysisError!!, Modifier.padding(16.dp))
                                analysis != null -> AnalysisContent(analysis!!)
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
    }
}

/** 段落渲染：双击取句、长按整段、段前生词按钮 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParagraphRow(
    paragraph: Int,
    text: String,
    fontSizeSp: Float,
    vocabCount: Int,
    onShowParagraphVocab: () -> Unit,
    onAnalyzeSentence: (sentence: String, before: String, after: String) -> Unit,
    onAnalyzeParagraph: () -> Unit
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val sentences = remember(text) { splitSentences(text) }

    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
        if (vocabCount > 0) {
            Box(Modifier.padding(top = 2.dp)) {
                Badge(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .padding(2.dp)
                        .pointerInput(paragraph) { detectTapGestures(onTap = { onShowParagraphVocab() }) }
                ) {
                    Text("$vocabCount")
                }
            }
        } else {
            Spacer(Modifier.width(32.dp))
        }
        Text(
            text = text,
            fontSize = fontSizeSp.sp,
            lineHeight = (fontSizeSp * 1.55f).sp,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = fontSizeSp.sp),
            onTextLayout = { layout = it },
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .pointerInput(text) {
                    detectTapGestures(
                        onDoubleTap = { pos ->
                            val l = layout ?: return@detectTapGestures
                            val offset = l.getOffsetForPosition(pos)
                            val sent = sentenceAt(sentences, offset) ?: return@detectTapGestures
                            val (before, after) = neighbors(sentences, sent)
                            onAnalyzeSentence(sent.text, before, after)
                        },
                        onLongPress = { onAnalyzeParagraph() }
                    )
                }
        )
    }
}
