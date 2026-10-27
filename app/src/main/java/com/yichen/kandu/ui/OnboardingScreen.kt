package com.yichen.kandu.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yichen.kandu.data.AppSettings
import com.yichen.kandu.data.LlmConfig
import com.yichen.kandu.data.SettingsStore
import com.yichen.kandu.data.VocabLevels
import com.yichen.kandu.ui.llm.LlmConfigEditor

/**
 * 首次引导：第 1 步选择词汇掌握水平（当前仅四级可选），第 2 步配置大模型。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    context: android.content.Context,
    onDone: () -> Unit
) {
    var step by remember { mutableStateOf(1) }
    var level by remember { mutableStateOf(VocabLevels.CET4) }
    var llm by remember { mutableStateOf<LlmConfig?>(null) }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(top = 48.dp)
    ) {
        Text(
            "刊读",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        Text(
            "导入经济学人、Wired 等英文原刊，大模型辅助精读：每篇中文摘要、逐段生词表、双击句解。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        )

        if (step == 1) {
            SectionHeader("第一步 · 选择你的词汇水平", Modifier.padding(horizontal = 16.dp))
            VocabLevels.all.forEach { lvl ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .selectable(selected = level == lvl.id, onClick = { if (lvl.available) level = lvl.id }),
                    colors = CardDefaults.cardColors(
                        containerColor = if (level == lvl.id) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                    border = if (lvl.available) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                    enabled = lvl.available,
                    onClick = { if (lvl.available) level = lvl.id }
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = level == lvl.id && lvl.available, onClick = { if (lvl.available) level = lvl.id })
                        Spacer(Modifier.width(4.dp))
                        Column {
                            Text(lvl.label, style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (lvl.available) "词表已内置，可离线筛词" else "后续版本提供",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
            Button(
                onClick = { step = 2 },
                modifier = Modifier.padding(24.dp).fillMaxWidth()
            ) { Text("下一步：配置大模型") }
        } else {
            SectionHeader("第二步 · 配置大模型接口", Modifier.padding(horizontal = 16.dp))
            Text(
                "填写 API 地址、Key 与模型名；也可以直接粘贴 cc-switch 的配置 JSON。建议点「测试连接」确认可用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            LlmConfigEditor(
                initial = AppSettings().llm,
                onSave = { cfg ->
                    SettingsStore.save(
                        context,
                        AppSettings(vocabLevel = level, levelChosen = true, llm = cfg)
                    )
                    onDone()
                },
                saveLabel = "完成，进入书架"
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
