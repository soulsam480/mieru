package cx.miruro.app.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import cx.miruro.app.MiruroApp
import cx.miruro.app.ui.detail.DetailScreen
import cx.miruro.app.ui.list.ListScreen
import cx.miruro.app.ui.player.PlayerScreen
import cx.miruro.app.ui.settings.SettingsScreen

private const val ROUTE_LIST = "list"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_DETAIL = "detail/{id}?title={title}"
private const val ROUTE_PLAYER = "player/{id}/{ep}?title={title}"

@Composable
fun MiruroNavHost(app: MiruroApp) {
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = ROUTE_LIST) {
        composable(ROUTE_LIST) {
            ListScreen(
                api = app.api,
                progress = app.progress,
                onOpenSettings = { nav.navigate(ROUTE_SETTINGS) },
                updates = app.updates,
                onOpenAnime = { anime ->
                    nav.navigate("detail/${anime.id}?title=${Uri.encode(anime.title.best())}")
                },
                onResume = { item ->
                    nav.navigate(
                        "player/${item.animeId}/${item.episode}?title=${Uri.encode(item.title)}"
                    )
                }
            )
        }

        composable(ROUTE_SETTINGS) {
            SettingsScreen(
                api = app.api,
                endpoints = app.endpoints,
                updates = app.updates,
                prefs = app.prefs,
                onBack = { nav.popBackStack() }
            )
        }

        composable(
            route = ROUTE_DETAIL,
            arguments = listOf(
                navArgument("id") { type = NavType.StringType },
                navArgument("title") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            DetailScreen(
                api = app.api,
                progress = app.progress,
                franchises = app.franchises,
                animeId = id,
                fallbackTitle = entry.arguments?.getString("title").orEmpty(),
                onBack = { nav.popBackStack() },
                onPlay = { playId, episode, title ->
                    nav.navigate("player/$playId/$episode?title=${Uri.encode(title)}")
                }
            )
        }

        composable(
            route = ROUTE_PLAYER,
            arguments = listOf(
                navArgument("id") { type = NavType.StringType },
                navArgument("ep") { type = NavType.IntType },
                navArgument("title") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { entry ->
            val playerAnimeId = entry.arguments?.getString("id").orEmpty()
            val playerTitle = entry.arguments?.getString("title").orEmpty()
            PlayerScreen(
                api = app.api,
                progress = app.progress,
                styles = app.styles,
                prefs = app.prefs,
                animeId = playerAnimeId,
                animeTitle = playerTitle,
                startEpisode = entry.arguments?.getInt("ep") ?: 1,
                onBack = { nav.popBackStack() },
                onOpenInfo = {
                    // Swap the player for the show page, so Back from there returns to the list.
                    nav.navigate("detail/$playerAnimeId?title=${Uri.encode(playerTitle)}") {
                        popUpTo(ROUTE_PLAYER) { inclusive = true }
                    }
                }
            )
        }
    }
}
