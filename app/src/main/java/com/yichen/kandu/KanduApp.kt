package com.yichen.kandu

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.yichen.kandu.data.VocabLevels

class KanduApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 首次启动把内置 CET-4 词表从 assets 拷到内部存储（便于日后更新词库）
        VocabLevels.ensureWordlists(this)
        // pdfbox-android 需要初始化字体等资源
        PDFBoxResourceLoader.init(applicationContext)
    }
}
