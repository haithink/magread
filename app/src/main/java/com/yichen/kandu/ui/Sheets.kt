package com.yichen.kandu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichen.kandu.data.AnalysisVocab
import com.yichen.kandu.data.ArticleVocab
import com.yichen.kandu.data.ParagraphVocab
import com.yichen.kandu.data.SentenceAnalysis
import com.yichen.kandu.data.VocabEntry

/** 生词词条卡片 */
@Composable
fun VocabEntryCard(entry: VocabEntry, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(Modifier.padding(10.dp)) {
            Row {
                Text(
                    entry.word,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                if (entry.phonetic.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "/${entry.phonetic}/",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                if (entry.pos.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(entry.pos, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                if (entry.term) {
                    Spacer(Modifier.width(6.dp))
                    TagChip("术语")
                }
            }
            Text(entry.sense, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 3.dp))
            if (entry.inText.isNotBlank()) {
                Text(
                    "原文：${entry.inText}",
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            if (entry.root.isNotBlank()) {
                Text(
                    "拆词：${entry.root}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
    }
}

/** 逐段生词区块 */
@Composable
fun ParagraphVocabSection(section: ParagraphVocab, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text(
            "第 ${section.index + 1} 段 ｜ ${section.startWords.take(24)}…",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
        )
        if (section.entries.isEmpty()) {
            Text(
                "本段无生词",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        } else {
            section.entries.forEach { VocabEntryCard(it) }
        }
    }
}

/** 整篇生词表内容（标题生词 → 熟词僻义 → 逐段），供全篇/段落弹层复用 */
@Composable
fun VocabContent(vocab: ArticleVocab, onlyParagraph: Int? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (onlyParagraph == null) {
            if (vocab.titleEntries.isNotEmpty()) {
                SectionHeader("〇 · 标题生词")
                vocab.titleEntries.forEach { VocabEntryCard(it) }
            }
            if (vocab.idioms.isNotEmpty()) {
                SectionHeader("〇 · 熟词僻义（常见义 → 本文义）")
                vocab.idioms.forEach {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ) {
                        Column(Modifier.padding(8.dp)) {
                            Text(it.word, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                            Text("常见义：${it.common}", style = MaterialTheme.typography.bodySmall)
                            Text("本文义：${it.inText}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        val sections = if (onlyParagraph != null) {
            vocab.paragraphs.filter { it.index == onlyParagraph }
        } else {
            vocab.paragraphs
        }
        if (onlyParagraph == null) SectionHeader("逐段生词")
        sections.forEach { ParagraphVocabSection(it) }
        if (onlyParagraph != null && sections.firstOrNull()?.entries?.isEmpty() == true) {
            Text("本段没有收录生词", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
        }
    }
}

/** 句解内容：疑难词汇 → 语法对齐图解 → 语法点 → 语境 → 参考译文 */
@Composable
fun AnalysisContent(analysis: SentenceAnalysis, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (analysis.vocab.isNotEmpty()) {
            SectionHeader("一、疑难词汇")
            analysis.vocab.forEach { v ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    AnalysisVocabRow(v)
                }
            }
        }
        if (analysis.grammarDiagram.isNotBlank()) {
            SectionHeader("二、语法图解")
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Text(
                    analysis.grammarDiagram,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp)
                )
            }
            if (analysis.grammarPoints.isNotEmpty()) {
                analysis.grammarPoints.forEachIndexed { i, p ->
                    Column(Modifier.padding(top = 8.dp)) {
                        Text(
                            "${i + 1}. ${p.title}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(p.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        if (analysis.context.isNotBlank()) {
            SectionHeader("三、语境")
            Text(analysis.context, style = MaterialTheme.typography.bodyMedium)
        }
        if (analysis.translation.isNotBlank()) {
            SectionHeader("参考译文")
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            ) {
                Text(
                    analysis.translation,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AnalysisVocabRow(v: AnalysisVocab) {
    Row {
        Text(
            v.word,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        if (v.phonetic.isNotBlank()) {
            Spacer(Modifier.width(6.dp))
            Text("/${v.phonetic}/", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
    Text(v.sense, style = MaterialTheme.typography.bodyMedium)
    if (v.note.isNotBlank()) {
        Text(
            v.note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}
