@file:OptIn(ExperimentalMaterial3Api::class)

package com.yichen.kandu.ui.llm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yichen.kandu.data.LlmConfig
import com.yichen.kandu.llm.LlmClient
import kotlinx.coroutines.launch

/**
 * 大模型配置表单（设置页与首次引导共用）：
 * 手动填写 URL / API Key / 模型名，或粘贴 cc-switch 风格 JSON 自动识别，
 * 填完可一键测试连通性。
 */
@Composable
fun LlmConfigEditor(
    initial: LlmConfig,
    onSave: (LlmConfig) -> Unit,
    saveLabel: String = "保存配置",
    modifier: Modifier = Modifier
) {
    var provider by remember { mutableStateOf(initial.provider) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var model by remember { mutableStateOf(initial.model) }
    var pasteText by remember { mutableStateOf("") }
    var showPaste by remember { mutableStateOf(false) }
    var pasteNote by remember { mutableStateOf("") }
    var testState by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun currentCfg() = LlmConfig(provider, baseUrl.trim(), apiKey.trim(), model.trim())

    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        // 接口类型
        Text("接口类型", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            FilterChip(
                selected = provider == LlmConfig.PROVIDER_OPENAI,
                onClick = { provider = LlmConfig.PROVIDER_OPENAI },
                label = { Text("OpenAI 兼容") }
            )
            FilterChip(
                selected = provider == LlmConfig.PROVIDER_ANTHROPIC,
                onClick = { provider = LlmConfig.PROVIDER_ANTHROPIC },
                label = { Text("Anthropic 原生") }
            )
        }
        Text(
            if (provider == LlmConfig.PROVIDER_ANTHROPIC)
                "请求 {URL}/v1/messages，头 x-api-key（Claude 系列或其代理）"
            else
                "请求 {URL}/v1/chat/completions，头 Authorization: Bearer（GPT/DeepSeek/GLM 等大多数服务）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )

        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("API 地址 Base URL") },
            placeholder = { Text(if (provider == LlmConfig.PROVIDER_ANTHROPIC) "https://api.anthropic.com" else "https://api.openai.com/v1") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API Key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("模型名 Model") },
            placeholder = { Text(if (provider == LlmConfig.PROVIDER_ANTHROPIC) "claude-sonnet-5-5" else "gpt-4o / deepseek-chat / glm-4 …") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // cc-switch 粘贴
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { showPaste = !showPaste }) {
                Text(if (showPaste) "收起粘贴框" else "从 cc-switch JSON 粘贴配置")
            }
        }
        if (showPaste) {
            OutlinedTextField(
                value = pasteText,
                onValueChange = { pasteText = it },
                label = { Text("粘贴 cc-switch 配置 JSON 或含 baseUrl/apiKey/model 的 JSON") },
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth()
            )
            Row(modifier = Modifier.padding(top = 6.dp)) {
                OutlinedButton(onClick = {
                    val parsed = LlmClient.parsePastedJson(pasteText)
                    if (parsed == null) {
                        pasteNote = "无法解析：不是有效的 JSON 或缺少 URL 字段"
                    } else {
                        provider = parsed.first.provider
                        baseUrl = parsed.first.baseUrl
                        apiKey = parsed.first.apiKey
                        model = parsed.first.model
                        pasteNote = parsed.second
                    }
                }) { Text("解析并填入") }
                if (pasteText.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { pasteText = ""; pasteNote = "" }) { Text("清空") }
                }
            }
            if (pasteNote.isNotBlank()) {
                Text(pasteNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }

        // 测试与保存
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = {
                val cfg = currentCfg()
                val invalid = LlmClient.validate(cfg)
                if (invalid != null) {
                    testState = null
                    errorMsg = invalid
                } else {
                    errorMsg = null
                    testing = true
                    testState = null
                    scope.launch {
                        val r = LlmClient.test(cfg)
                        testing = false
                        testState = if (r.ok) "连接成功（${r.latencyMs} ms）：${r.reply}"
                        else "连接失败：${r.error}"
                    }
                }
            }) {
                if (testing) {
                    CircularProgressIndicator(Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text("测试连接")
            }
            Button(onClick = {
                val cfg = currentCfg()
                val invalid = LlmClient.validate(cfg)
                if (invalid != null) {
                    errorMsg = invalid
                } else {
                    errorMsg = null
                    onSave(cfg)
                }
            }) { Text(saveLabel) }
        }
        testState?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (it.startsWith("连接成功")) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        errorMsg?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(16.dp))
    }
}
