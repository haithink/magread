package com.yichen.kandu.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yichen.kandu.data.BookDigest
import com.yichen.kandu.data.BookMeta
import com.yichen.kandu.data.BookStore
import com.yichen.kandu.data.SettingsStore
import com.yichen.kandu.llm.GenService
import com.yichen.kandu.llm.LlmClient
import kotlinx.coroutines.launch

/**
 * 目录页：展示某本刊物每篇文章的中英文标题与中文摘要。
 * 摘要结果缓存到内部文件，重复打开不再调用大模型。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DigestScreen(
    meta: BookMeta,
    onBack: () -> Unit,
    onOpenArticle: (Int) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var digest by remember { mutableStateOf<BookDigest?>(null) }
    var generating by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf("") }
    var genChars by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var preparing by remember { mutableStateOf(true) }

    suspend fun generate() {
        val cfg = SettingsStore.load(context).llm
        val invalid = LlmClient.validate(cfg)
        if (invalid != null) {
            error = "$invalid（到设置里配置）"
            return
        }
        generating = true
        error = null
        genChars = 0
        try {
            BookStore.ensureContent(context, meta) // 预解析正文（进度提示）
            digest = GenService.generateDigest(
                context, meta, cfg,
                onProgress = { done, total -> progressText = "生成中… 第 $done/$total 批" },
                onChars = { genChars = it }
            )
        } catch (e: Exception) {
            error = e.message ?: e.toString()
            // 已完成的批次已落盘，展示部分结果而不是空白
            digest = BookStore.loadDigest(context, meta.id)
        } finally {
            generating = false
        }
    }

    LaunchedEffect(meta.id) {
        digest = BookStore.loadDigest(context, meta.id)
        preparing = false
        if (digest == null) generate()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(meta.fileName.removeSuffix(".${meta.ext}"), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    IconButton(onClick = { if (!generating) scope.launch { generate() } }, enabled = !generating) {
                        Icon(Icons.Filled.Refresh, contentDescription = "重新生成摘要")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (preparing) {
                CenterLoading("解析刊物…")
            } else {
                if (generating) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            buildString {
                                append(progressText.ifBlank { "解析刊物正文…" })
                                if (genChars > 0) append(" · 已接收 $genChars 字")
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                }
                error?.let {
                    ErrorBanner(it, Modifier.padding(16.dp))
                }
                digest?.let { d ->
                    if (d.articles.isEmpty()) {
                        Text(
                            "没有解析出文章列表",
                            Modifier.padding(24.dp),
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(d.articles, key = { it.index }) { article ->
                            Card(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                                    .clickable { onOpenArticle(article.index) }
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(
                                        article.titleEn,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (article.titleZh.isNotBlank()) {
                                        Text(
                                            article.titleZh,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                    }
                                    if (article.summaryZh.isNotBlank()) {
                                        Text(
                                            article.summaryZh,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 6.dp)
                                        )
                                    }
                                }
                            }
                        }
                        item { Spacer(Modifier.height(32.dp)) }
                    }
                }
            }
        }
    }
}
