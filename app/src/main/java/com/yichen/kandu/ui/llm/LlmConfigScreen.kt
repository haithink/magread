package com.yichen.kandu.ui.llm

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yichen.kandu.data.AppSettings
import com.yichen.kandu.data.BookStore
import com.yichen.kandu.data.SettingsStore
import com.yichen.kandu.data.VocabLevels
import com.yichen.kandu.ui.SectionHeader

/** 设置页：大模型配置 + 词库信息 + 缓存管理 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlmConfigScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(SettingsStore.load(context)) }
    var confirmPurge by remember { mutableStateOf(false) }

    fun update(transform: (AppSettings) -> AppSettings) {
        settings = transform(settings)
        SettingsStore.save(context, settings)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            SectionHeader("大模型接口", Modifier.padding(horizontal = 16.dp))
            LlmConfigEditor(
                initial = settings.llm,
                onSave = { cfg -> update { it.copy(llm = cfg) } },
                saveLabel = "保存配置"
            )

            SectionHeader("词汇级别", Modifier.padding(horizontal = 16.dp))
            Text(
                "当前：${VocabLevels.labelOf(settings.vocabLevel)}（词表 ${VocabLevels.wordCount(context, settings.vocabLevel)} 词，已内置离线词库）",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Text(
                "其他级别（CET-6 / 托福 / GRE）将在后续版本提供内置词库",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            SectionHeader("缓存", Modifier.padding(horizontal = 16.dp))
            val sizeMb = remember { BookStore.cacheSizeBytes(context) / 1024.0 / 1024.0 }
            Text(
                "刊物副本、摘要、生词表与句解缓存：%.1f MB".format(sizeMb),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Row(Modifier.padding(16.dp)) {
                OutlinedButton(onClick = { confirmPurge = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Spacer(Modifier.padding(start = 4.dp))
                    Text("清空全部缓存")
                }
            }
            Text(
                "清空后重新打开刊物会重新解析与生成（大模型会重新调用）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(Modifier.padding(bottom = 32.dp))
        }
    }

    if (confirmPurge) {
        AlertDialog(
            onDismissRequest = { confirmPurge = false },
            title = { Text("清空全部缓存？") },
            text = { Text("将删除所有刊物副本、封面、摘要、生词表和句解缓存。设置不会丢失。") },
            confirmButton = {
                TextButton(onClick = {
                    BookStore.purgeAll(context)
                    confirmPurge = false
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { confirmPurge = false }) { Text("取消") } }
        )
    }
}
