package com.yichen.kandu.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yichen.kandu.data.AppSettings
import com.yichen.kandu.data.BookMeta
import com.yichen.kandu.data.BookStore
import com.yichen.kandu.data.SettingsStore
import kotlinx.coroutines.launch

/** 书架：扫描刊物目录，展示封面网格 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenBook: (BookMeta) -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(SettingsStore.load(context)) }
    var books by remember { mutableStateOf<List<BookMeta>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var coversReady by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    fun saveSettings(transform: (AppSettings) -> AppSettings) {
        settings = transform(settings)
        SettingsStore.save(context, settings)
    }

    val treePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // 持久化目录读权限
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            saveSettings { it.copy(libraryUri = uri.toString()) }
            scanning = true
            scope.launch {
                val found = BookStore.scan(context, uri.toString())
                books = found
                found.forEach { meta ->
                    BookStore.ensureCover(context, meta)
                    coversReady++
                }
                scanning = false
            }
        }
    }

    fun rescan(uri: String) {
        if (uri.isBlank()) return
        scanning = true
        coversReady = 0
        scope.launch {
            val found = BookStore.scan(context, uri)
            books = found
            found.forEach { meta ->
                BookStore.ensureCover(context, meta)
                coversReady++
            }
            scanning = false
        }
    }

    // 首次进入自动扫描
    LaunchedEffect(settings.libraryUri) {
        if (settings.libraryUri.isNotBlank() && books.isEmpty() && !scanning) {
            rescan(settings.libraryUri)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("刊读 · 书架") },
                actions = {
                    IconButton(onClick = { rescan(settings.libraryUri) }, enabled = settings.libraryUri.isNotBlank()) {
                        Icon(Icons.Filled.Refresh, contentDescription = "重新扫描")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (settings.llm.let { !it.isComplete } ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "还没配置大模型接口，生成摘要/生词/句解前请先到设置里配置",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = null) }
                    }
                }
            }

            if (settings.libraryUri.isBlank()) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Filled.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "选择存放刊物文件的目录\n（支持 EPUB / PDF）",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                    Button(onClick = { treePicker.launch(null) }) {
                        Text("选择目录")
                    }
                }
            } else {
                if (scanning) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "扫描与提取封面中…（${coversReady}/${books.size}）",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(books, key = { it.id }) { meta ->
                        BookCard(meta, hasDigest = BookStore.hasDigest(context, meta.id)) {
                            onOpenBook(meta)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookCard(meta: BookMeta, hasDigest: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.72f)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            ) {
                CoverImage(
                    BookStore.coverFile(LocalContext.current, meta.id).takeIf { it.exists() },
                    contentDescription = meta.fileName
                )
                if (hasDigest) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "已有摘要",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                    )
                }
            }
            Text(
                meta.fileName.removeSuffix(".${meta.ext}"),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}
