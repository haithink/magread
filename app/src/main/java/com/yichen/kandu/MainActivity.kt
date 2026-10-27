package com.yichen.kandu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.yichen.kandu.data.BookMeta
import com.yichen.kandu.data.BookStore
import com.yichen.kandu.data.SettingsStore
import com.yichen.kandu.ui.KanduTheme
import com.yichen.kandu.ui.DigestScreen
import com.yichen.kandu.ui.LibraryScreen
import com.yichen.kandu.ui.OnboardingScreen
import com.yichen.kandu.ui.ReaderScreen
import com.yichen.kandu.ui.llm.LlmConfigScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startOnboarding = !SettingsStore.load(this).levelChosen
        setContent {
            KanduTheme {
                AppRoot(startOnboarding)
            }
        }
    }
}

@Composable
fun AppRoot(startOnboarding: Boolean) {
    val nav = rememberNavController()

    NavHost(
        navController = nav,
        startDestination = if (startOnboarding) "onboarding" else "library"
    ) {
        composable("onboarding") {
            val context = androidx.compose.ui.platform.LocalContext.current
            OnboardingScreen(context = context) {
                nav.navigate("library") {
                    popUpTo("onboarding") { inclusive = true }
                }
            }
        }
        composable("library") {
            LibraryScreen(
                onOpenBook = { meta -> nav.navigate("digest/${meta.id}") },
                onOpenSettings = { nav.navigate("llmconfig") }
            )
        }
        composable("llmconfig") {
            LlmConfigScreen(onBack = { nav.popBackStack() })
        }
        composable(
            route = "digest/{bookId}",
            arguments = listOf(navArgument("bookId") { type = NavType.StringType })
        ) { entry ->
            val bookId = entry.arguments?.getString("bookId") ?: return@composable
            val meta = entry.findBookMeta(bookId)
            if (meta == null) {
                nav.popBackStack()
                return@composable
            }
            DigestScreen(
                meta = meta,
                onBack = { nav.popBackStack() },
                onOpenArticle = { idx -> nav.navigate("reader/$bookId/$idx") }
            )
        }
        composable(
            route = "reader/{bookId}/{articleIndex}",
            arguments = listOf(
                navArgument("bookId") { type = NavType.StringType },
                navArgument("articleIndex") { type = NavType.IntType }
            )
        ) { entry ->
            val bookId = entry.arguments?.getString("bookId") ?: return@composable
            val idx = entry.arguments?.getInt("articleIndex") ?: 0
            val meta = entry.findBookMeta(bookId)
            if (meta == null) {
                nav.popBackStack()
                return@composable
            }
            ReaderScreen(meta = meta, articleIndex = idx, onBack = { nav.popBackStack() })
        }
    }
}

/** 导航参数里只有 bookId，需要从设置里的刊物目录重新扫出元数据 */
@Composable
private fun androidx.navigation.NavBackStackEntry.findBookMeta(bookId: String): BookMeta? {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = SettingsStore.load(context)
    return remember(bookId, settings.libraryUri) {
        BookStore.scan(context, settings.libraryUri).firstOrNull { it.id == bookId }
    }
}
