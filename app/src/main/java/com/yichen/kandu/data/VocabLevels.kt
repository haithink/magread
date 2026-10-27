package com.yichen.kandu.data

import android.content.Context
import java.io.File

/**
 * 词汇级别定义。当前仅内置 CET-4；其余级别待后续版本补充词库后开放。
 * 词表文件在首次启动时从 assets 拷到 filesDir/vocab/，便于日后在线更新词库。
 */
object VocabLevels {
    const val CET4 = "cet4"

    data class Level(
        val id: String,
        val label: String,
        val fileName: String,
        val available: Boolean
    )

    val all: List<Level> = listOf(
        Level(CET4, "大学英语四级 CET-4", "cet4_words.txt", true),
        Level("cet6", "大学英语六级 CET-6", "cet6_words.txt", false),
        Level("toefl", "托福词汇 TOEFL", "toefl_words.txt", false),
        Level("gre", " GRE 词汇", "gre_words.txt", false)
    )

    fun labelOf(level: String): String =
        all.firstOrNull { it.id == level }?.label ?: level

    private fun targetFile(context: Context, level: Level): File {
        val dir = File(context.filesDir, "vocab").apply { mkdirs() }
        return File(dir, level.fileName)
    }

    /** 首次启动时把内置词表拷到内部存储 */
    fun ensureWordlists(context: Context) {
        for (level in all.filter { it.available }) {
            val f = targetFile(context, level)
            if (f.exists()) continue
            runCatching {
                context.assets.open(level.fileName).use { input ->
                    f.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    /** 读取某级别词表（小写单词集合），读取失败返回空集合（此时只靠大模型判断） */
    fun wordSet(context: Context, level: String): Set<String> {
        val lvl = all.firstOrNull { it.id == level } ?: return emptySet()
        val f = targetFile(context, lvl)
        if (!f.exists()) return emptySet()
        return runCatching {
            f.readLines().mapNotNull { line ->
                val word = line.substringBefore('\t').trim().lowercase()
                word.ifBlank { null }
            }.toSet()
        }.getOrDefault(emptySet())
    }

    /** 词表规模（用于设置页展示），失败返回 -1 */
    fun wordCount(context: Context, level: String): Int = wordSet(context, level).size
}
