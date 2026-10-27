# 刊读 · MagRead

基于大模型的英文外刊（经济学人 / Wired / 纽约客 / 大西洋月刊等）安卓精读辅助 App。

## 功能

1. **词汇水平** — 首次启动选择（当前内置 CET-4 词库 3739 词 + 音标，离线筛超纲词；CET-6/托福/GRE 预留）
2. **书架** — 选择目录后自动扫描 EPUB / PDF，生成封面缩略图（EPUB 读内嵌封面，PDF 渲染首页）
3. **文章摘要** — 打开刊物自动分批调用大模型，生成每篇文章中英文标题 + 中文摘要；结果缓存本地
4. **精读** —
   - 顶栏「生词表」：整篇生词（标题生词单列 → 熟词僻义 → 逐段，词条含音标/词性/释义/原文短语/词根拆解/术语标记）
   - 每段前缀徽标：显示本段生词
   - **双击句子**：句解 = 疑难词汇 → 语法对齐图解 → 语法点 → 语境 → 参考译文
   - **长按段落**：整段句解
   - 生词表/句解均按 (书, 文章, 级别, 文本) 缓存，避免重复调用
5. **大模型配置** — 支持 OpenAI 兼容接口与 Anthropic 原生接口；可手动填 URL / API Key / 模型名，或直接粘贴 cc-switch 风格 JSON 自动识别；带连通性测试

## 构建

- 要求 JDK 11（工程已按 AGP 7.2.2 + Gradle 7.6.4 配置，兼容本机 Android Studio Chipmunk）
- 命令行：`gradlew.bat assembleDebug`，产物在 `app/build/outputs/apk/debug/`
- Android Studio：File → Open 打开本目录即可（`local.properties` 已指向本机 SDK）

## 使用

1. 首次启动：选词汇水平 → 配置大模型（建议点「测试连接」）→ 进入书架
2. 书架：点「选择目录」挑选存放 `.epub` / `.pdf` 的文件夹（如 `I:\awesomeEnglish` 同步到手机的目录）
3. 点封面 → 自动生成文章摘要 → 点文章进入阅读
4. 阅读页先点「生成生词表」（逐段），随后双击任意句子做句解

## 技术要点

- Kotlin + Jetpack Compose (Material 3)，AGP 7.2.2 / compileSdk 33 / minSdk 26
- EPUB：自研解析（Zip + XmlPullParser + Jsoup），适配 awesome-english-ebooks 的 UUID 文件名期刊；自动剔除 zlibrary 水印行
- PDF：PdfRenderer 封面 + pdfbox-android 文本提取（双栏版式为粗略线性文本，v1 已知限制）
- 大模型：OkHttp；JSON 输出做围栏剥离 + 容错解析
- 缓存目录：`filesDir/{books,covers,cache,vocab}`，设置在 `filesDir/settings.json`
