package com.yichen.kandu.llm

import com.yichen.kandu.data.LlmConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 大模型客户端：支持 OpenAI 兼容接口（/chat/completions）与 Anthropic 原生接口（/v1/messages），
 * 并支持从 cc-switch 风格的 JSON 中提取配置。
 */
object LlmClient {

    class LlmException(message: String) : Exception(message)

    data class TestResult(val ok: Boolean, val latencyMs: Long, val reply: String, val error: String = "")

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ---------------- 请求 ----------------

    fun endpoint(cfg: LlmConfig): String {
        var base = cfg.baseUrl.trim().trimEnd('/')
        return when (cfg.provider) {
            LlmConfig.PROVIDER_ANTHROPIC -> when {
                base.endsWith("/v1/messages") -> base
                Regex("/v\\d+$").containsMatchIn(base) -> "$base/messages"
                else -> "$base/v1/messages"
            }
            else -> when {
                base.endsWith("/chat/completions") -> base
                Regex("/v\\d+$").containsMatchIn(base) -> "$base/chat/completions"
                else -> "$base/v1/chat/completions"
            }
        }
    }

    /** 流式请求的状态累计 */
    private class StreamState {
        val content = StringBuilder()
        var sawReasoning = false
        var lengthCut = false
    }

    /**
     * 发起聊天请求。优先流式（SSE）：长生成不会被读超时掐断，且可通过
     * onDelta 实时上报已接收正文长度供 UI 展示。
     * 推理模型烧额度 / 输出被 length 截断时自动加倍额度重试一次；
     * 重试后仍截断但有正文，则原样返回（由上层做 JSON 修补兜底）。
     */
    suspend fun chat(
        cfg: LlmConfig,
        system: String?,
        user: String,
        maxTokens: Int = 4000,
        temperature: Double = 0.3,
        onDelta: ((Int) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        if (!cfg.isComplete) throw LlmException("大模型配置不完整，请先在设置中填写 URL / API Key / 模型名")

        var budget = maxTokens
        for (attempt in 0..1) {
            val state = StreamState()
            val streamError = runCatching {
                streamOnce(cfg, system, user, budget, temperature, state)
            }.exceptionOrNull()

            var content = state.content.toString().trim()
            if (content.isEmpty() && streamError != null) {
                // 流式不被支持或失败 → 退回非流式
                val body = doRequest(cfg, system, user, budget, temperature)
                content = parseContent(cfg, body)?.trim() ?: ""
                if (looksTruncated(body)) state.lengthCut = true
            }
            onDelta?.invoke(state.content.length)

            if (content.isNotEmpty() && !state.lengthCut) {
                return@withContext stripThinkBlocks(content)
            }
            val burned = state.sawReasoning || state.lengthCut
            if (attempt == 0 && burned) {
                budget = (budget * 2).coerceAtMost(32768)
                continue
            }
            if (content.isNotEmpty()) {
                // 已重试仍被截断：返回截断文本，交由上层 JSON 修补
                return@withContext stripThinkBlocks(content)
            }
            throw LlmException(
                if (burned) "模型把输出额度耗在思考（reasoning）上且重试后仍无正文。建议换非推理模型（如 deepseek-chat）或稍后再试"
                else "响应里没有内容：${streamError?.message ?: "空响应"}"
            )
        }
        throw LlmException("响应里没有内容")
    }

    /** 流式读取一次请求，把正文累计进 state.content */
    private fun streamOnce(
        cfg: LlmConfig,
        system: String?,
        user: String,
        maxTokens: Int,
        temperature: Double,
        state: StreamState
    ) {
        val bodyJson = buildRequestBody(cfg, system, user, maxTokens, temperature, stream = true)
        val resp = runCatching { client.newCall(buildRequest(cfg, bodyJson)).execute() }
            .getOrElse { throw LlmException("网络请求失败：${it.message}") }

        resp.use { response ->
            if (!response.isSuccessful) {
                throw LlmException("HTTP ${response.code}：${firstLine(response.body?.string() ?: "")}")
            }
            val source = response.body?.source() ?: throw LlmException("响应没有内容")
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (line.isBlank() || line.startsWith(":")) continue
                if (!line.startsWith("data:")) continue
                val data = line.substring(5).trim()
                if (data == "[DONE]") break
                val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                when (cfg.provider) {
                    LlmConfig.PROVIDER_ANTHROPIC -> {
                        when (obj["type"]?.jsonPrimitive?.content) {
                            "content_block_delta" -> {
                                val delta = obj["delta"] as? JsonObject
                                strOrNull(delta?.get("text"))?.let {
                                    if (it.isNotEmpty()) state.content.append(it)
                                }
                            }
                            "message_delta" -> {
                                val delta = obj["delta"] as? JsonObject
                                if (strOrNull(delta?.get("stop_reason")) == "max_tokens") state.lengthCut = true
                            }
                            "error" -> throw LlmException("接口报错：${obj["error"].toString().take(200)}")
                        }
                    }
                    else -> {
                        val choice = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
                        val delta = choice?.get("delta") as? JsonObject
                        if (delta != null) {
                            strOrNull(delta["content"])?.let {
                                if (it.isNotEmpty()) state.content.append(it)
                            }
                            if (delta["reasoning_content"] != null &&
                                delta["reasoning_content"] !is kotlinx.serialization.json.JsonNull
                            ) state.sawReasoning = true
                        }
                        if (strOrNull(choice?.get("finish_reason")) == "length") state.lengthCut = true
                    }
                }
            }
        }
    }

    private fun stripThinkBlocks(text: String): String =
        text.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()

    /** 推理内容烧穿额度 / 被长度截断的迹象 */
    private fun looksTruncated(body: String): Boolean =
        body.contains("reasoning_content") ||
            body.contains("\"finish_reason\":\"length\"") ||
            body.contains("\"finish_reason\": \"length\"")

    private fun buildRequestBody(
        cfg: LlmConfig,
        system: String?,
        user: String,
        maxTokens: Int,
        temperature: Double,
        stream: Boolean
    ): String = buildString {
        append('{')
        append("\"model\":${JsonPrimitive(cfg.model)},")
        append("\"temperature\":$temperature,")
        append("\"max_tokens\":$maxTokens,")
        if (stream) append("\"stream\":true,")
        when (cfg.provider) {
            LlmConfig.PROVIDER_ANTHROPIC -> {
                if (!system.isNullOrBlank()) append("\"system\":${JsonPrimitive(system)},")
                append("\"messages\":[{\"role\":\"user\",\"content\":${JsonPrimitive(user)}}]")
            }
            else -> {
                append("\"messages\":[")
                var first = true
                if (!system.isNullOrBlank()) {
                    append("{\"role\":\"system\",\"content\":${JsonPrimitive(system)}}")
                    first = false
                }
                if (!first) append(',')
                append("{\"role\":\"user\",\"content\":${JsonPrimitive(user)}}")
                append(']')
            }
        }
        append('}')
    }

    private fun buildRequest(cfg: LlmConfig, bodyJson: String): Request {
        val reqB = Request.Builder().url(endpoint(cfg))
        val mediaType = "application/json; charset=utf-8".toMediaType()
        when (cfg.provider) {
            LlmConfig.PROVIDER_ANTHROPIC -> {
                reqB.header("x-api-key", cfg.apiKey)
                reqB.header("anthropic-version", "2023-06-01")
            }
            else -> reqB.header("Authorization", "Bearer ${cfg.apiKey}")
        }
        reqB.post(bodyJson.toRequestBody(mediaType))
        return reqB.build()
    }

    private fun doRequest(
        cfg: LlmConfig,
        system: String?,
        user: String,
        maxTokens: Int,
        temperature: Double
    ): String {
        val bodyJson = buildRequestBody(cfg, system, user, maxTokens, temperature, stream = false)
        val resp = runCatching { client.newCall(buildRequest(cfg, bodyJson)).execute() }
            .getOrElse { throw LlmException("网络请求失败：${it.message}") }

        resp.use { response ->
            val text = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw LlmException("HTTP ${response.code}：${firstLine(text)}")
            }
            return text
        }
    }

    private fun firstLine(s: String): String = s.lineSequence().firstOrNull()?.take(300) ?: ""

    /** 取字符串原语内容；JsonNull / 非字符串返回 null */
    private fun strOrNull(el: JsonElement?): String? {
        if (el == null || el is kotlinx.serialization.json.JsonNull) return null
        return (el as? JsonPrimitive)?.content
    }

    private fun parseContent(cfg: LlmConfig, body: String): String? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return body.take(2000)
        val obj = root as? JsonObject ?: return body.take(2000)

        // Anthropic: content: [{type:"text", text:"..."}]
        if (cfg.provider == LlmConfig.PROVIDER_ANTHROPIC) {
            (obj["content"] as? JsonArray)?.let { arr ->
                return arr.mapNotNull { (it as? JsonObject)?.get("text") }
                    .mapNotNull { strOrNull(it) }
                    .joinToString("").takeIf { it.isNotBlank() }
            }
        }
        // OpenAI: choices[0].message.content / choices[0].text
        (obj["choices"] as? JsonArray)?.let { choices ->
            val first = choices.firstOrNull() as? JsonObject ?: return@let
            (first["message"] as? JsonObject)?.get("content")?.let { c ->
                strOrNull(c)?.takeIf { it.isNotBlank() }?.let { return it }
            }
            strOrNull(first["text"])?.takeIf { it.isNotBlank() }?.let { return it }
        }
        // 错误信息
        (obj["error"] as? JsonObject)?.get("message")?.let { e ->
            throw LlmException("接口报错：${strOrNull(e)?.take(300) ?: firstLine(body)}")
        }
        return null
    }

    // ---------------- 连接测试 ----------------

    suspend fun test(cfg: LlmConfig): TestResult {
        val start = System.currentTimeMillis()
        return try {
            val reply = chat(
                cfg,
                system = "你是连通性测试助手。",
                user = "请只回复两个字母：ok",
                maxTokens = 2000,
                temperature = 0.0
            )
            TestResult(true, System.currentTimeMillis() - start, reply.take(80))
        } catch (e: Exception) {
            TestResult(false, System.currentTimeMillis() - start, "", e.message ?: e.toString())
        }
    }

    // ---------------- cc-switch / 通用 JSON 配置提取 ----------------

    /**
     * 从粘贴的 JSON（cc-switch 的 config.json、单条 provider 配置、或普通
     * {baseUrl, apiKey, model} 结构）中启发式提取 LlmConfig。
     * 返回 (配置, 说明)；解析失败返回 null。
     */
    fun parsePastedJson(text: String): Pair<LlmConfig, String>? {
        val root = runCatching { json.parseToJsonElement(text.trim()) }.getOrNull() ?: return null
        val urls = LinkedHashMap<String, Int>()   // 值 -> 优先级（越大越优）
        val keys = LinkedHashMap<String, Int>()
        val models = LinkedHashMap<String, Int>()

        fun keyPriority(k: String): Int? = when {
            Regex("(?i)anthropic_base_url|openai_base_url").containsMatchIn(k) -> 100
            Regex("(?i)^base_?url$|^api_?base$").containsMatchIn(k) -> 80
            Regex("(?i)base_?url").containsMatchIn(k) -> 60
            Regex("(?i)^url$|endpoint").containsMatchIn(k) -> 40
            Regex("(?i)anthropic_auth_token|openai_api_key").containsMatchIn(k) -> 100
            Regex("(?i)^api_?key$|^auth_?token$|^token$").containsMatchIn(k) -> 80
            Regex("(?i)api_?key|auth|token").containsMatchIn(k) -> 50
            Regex("(?i)^key$").containsMatchIn(k) -> 30
            Regex("(?i)^anthropic_model$|^openai_model$|^model_name$").containsMatchIn(k) -> 100
            Regex("(?i)^model$").containsMatchIn(k) -> 80
            Regex("(?i)model").containsMatchIn(k) -> 40
            else -> null
        }

        fun walk(el: JsonElement) {
            when (el) {
                is JsonObject -> {
                    for ((k, v) in el) {
                        val p = keyPriority(k)
                        if (p != null && v is JsonPrimitive && v.isString) {
                            val value = v.content.trim()
                            if (value.isBlank()) { walk(v); continue }
                            when {
                                keyPriority(k)!! >= 100 && Regex("(?i)base_url").containsMatchIn(k) -> urls[value] = p
                                keyPriority(k)!! >= 100 && Regex("(?i)model").containsMatchIn(k) -> models[value] = p
                                Regex("(?i)base_url|url|endpoint").containsMatchIn(k) -> urls[value] = p
                                Regex("(?i)model").containsMatchIn(k) -> models[value] = p
                                else -> keys[value] = p
                            }
                        } else walk(v)
                    }
                }
                is JsonArray -> el.forEach { walk(it) }
                else -> {}
            }
        }
        walk(root)

        val url = urls.maxByOrNull { it.value }?.key ?: return null
        val key = keys.maxByOrNull { it.value }?.key ?: ""
        val model = models.maxByOrNull { it.value }?.key ?: ""

        val provider = when {
            Regex("(?i)anthropic").containsMatchIn(url) ||
                Regex("(?i)claude|anthropic").containsMatchIn(model) -> LlmConfig.PROVIDER_ANTHROPIC
            else -> LlmConfig.PROVIDER_OPENAI
        }
        val note = buildString {
            if (key.isBlank()) append("未在 JSON 中找到 API Key，请手动补填。")
            if (model.isBlank()) append(" 未找到模型名，请手动补填。")
            if (key.isNotBlank() && model.isNotBlank()) append("已自动识别配置。")
        }
        return Pair(LlmConfig(provider, url, key, model), note)
    }

    /** 校验配置格式是否正确，返回错误说明；null 表示通过 */
    fun validate(cfg: LlmConfig): String? {
        if (cfg.baseUrl.isBlank()) return "URL 不能为空"
        val trimmed = cfg.baseUrl.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return "URL 需以 http:// 或 https:// 开头"
        if (cfg.apiKey.isBlank()) return "API Key 不能为空"
        if (cfg.model.isBlank()) return "模型名不能为空"
        return null
    }
}
