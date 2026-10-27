package com.yichen.kandu.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.graphics.pdf.PdfRenderer
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

/**
 * PDF 解析：封面用系统 PdfRenderer 渲染首页，文本用 pdfbox-android 逐页提取。
 * PDF 版式（尤其双栏）提取结果是粗略的线性文本，v1 按页切分段落。
 */
object PdfParser {

    private val WATERMARK = Regex("(?i)zlibrary|this (article|file) was downloaded by")

    /** 逐页提取文本；每页作为一个粗略段落 */
    fun extractPages(file: File): List<String> {
        PDDocument.load(file).use { doc ->
            val stripper = PDFTextStripper().apply { sortByPosition = true }
            val pages = ArrayList<String>(doc.numberOfPages)
            for (i in 1..doc.numberOfPages) {
                stripper.startPage = i
                stripper.endPage = i
                val text = stripper.getText(doc)
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString(" ")
                    .trim()
                if (text.isNotEmpty() && !WATERMARK.containsMatchIn(text)) {
                    pages += text
                }
            }
            return pages
        }
    }

    /** 渲染首页为封面缩略图 */
    fun renderCover(file: File, targetWidth: Int = 480): Bitmap? = runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                if (renderer.pageCount == 0) return@runCatching null
                renderer.openPage(0).use { page ->
                    val ratio = page.height.toFloat() / page.width.toFloat()
                    val w = targetWidth
                    val h = (targetWidth * ratio).toInt().coerceIn(1, 4096)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    canvas.drawColor(Color.WHITE)
                    // null transform：页面自动等比适配位图
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        }
    }.getOrNull()
}
