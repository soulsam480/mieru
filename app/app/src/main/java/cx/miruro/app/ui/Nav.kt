package cx.miruro.app.ui

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import cx.miruro.app.MiruroApp
import cx.miruro.app.data.oneshows.MediaKind
import cx.miruro.app.ui.detail.DetailScreen
import cx.miruro.app.ui.list.ListScreen
import cx.miruro.app.ui.media.ShowDetailScreen
import cx.miruro.app.ui.media.ShowsScreen
import cx.miruro.app.ui.player.PlayerScreen
import cx.miruro.app.ui.settings.SettingsScreen

private const val ROUTE_ANIME = "anime"
private const val ROUTE_SHOWS = "shows/{kind}"
private const val ROUTE_MOVIES = "movies"
private const val ROUTE_SHOW_DETAIL = "shows/{kind}/{id}?title={title}"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_DETAIL = "detail/{id}?title={title}"
private const val ROUTE_PLAYER = "player/{id}/{ep}?title={title}"

private data class Section(val route: String, val label: String, val icon: ImageVector)

private val sections =
    listOf(
        Section(ROUTE_ANIME, "Anime", Icons.Filled.PlayArrow),
        Section("shows/TV", "TV", Icons.AutoMirrored.Filled.List),
        Section("movies", "Movies", Icons.Filled.Star)
    )

@Composable
fun MiruroNavHost(app: MiruroApp) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showSections = route == ROUTE_ANIME || route == ROUTE_SHOWS || route == ROUTE_MOVIES

    Scaffold(
        bottomBar = {
            if (showSections) {
                NavigationBar {
                    sections.forEach { section ->
                        NavigationBarItem(
                            selected = route == section.route ||
                                (section.route == ROUTE_SHOWS && route == ROUTE_SHOWS),
                            onClick = { nav.switchSection(section.route) },
                            icon = { Icon(section.icon, contentDescription = null) },
                            label = { Text(section.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = ROUTE_ANIME,
            modifier = Modifier.padding(padding)
        ) {
            composable(ROUTE_ANIME) {
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

            composable(ROUTE_MOVIES) {
                ListScreen(
                    api = app.api,
                    progress = app.progress,
                    catalogFormat = "MOVIE",
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

            composable(
                route = ROUTE_SHOWS,
                arguments = listOf(navArgument("kind") { type = NavType.StringType })
            ) { backStackEntry ->
                val kind = kindOf(backStackEntry.arguments?.getString("kind"))
                ShowsScreen(
                    api = app.oneShows,
                    kind = kind,
                    onOpen = { card ->
                        nav.navigate(
                            "shows/${kind.name}/${card.id}?title=${Uri.encode(card.title)}"
                        )
                    }
                )
            }

            composable(
                route = ROUTE_SHOW_DETAIL,
                arguments = listOf(
                    navArgument("kind") { type = NavType.StringType },
                    navArgument("id") { type = NavType.StringType },
                    navArgument("title") {
                        type = NavType.StringType
                        defaultValue = ""
                    }
                )
            ) { backStackEntry ->
                ShowDetailScreen(
                    api = app.oneShows,
                    mediaId = backStackEntry.arguments?.getString("id").orEmpty(),
                    kind = kindOf(backStackEntry.arguments?.getString("kind")),
                    fallbackTitle = backStackEntry.arguments?.getString("title").orEmpty(),
                    onBack = { nav.popBackStack() }
                )
            }

            composable(ROUTE_SETTINGS) {
                SettingsScreen(
                    api = app.api,
                    sources = app.sources,
                    updates = app.updates,
                    prefs = app.prefs,
                    autoSkip = app.autoSkip,
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
            ) { backStackEntry ->
                val id = backStackEntry.arguments?.getString("id").orEmpty()
                DetailScreen(
                    api = app.api,
                    progress = app.progress,
                    franchises = app.franchises,
                    animeId = id,
                    fallbackTitle = backStackEntry.arguments?.getString("title").orEmpty(),
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
            ) { backStackEntry ->
                val playerAnimeId = backStackEntry.arguments?.getString("id").orEmpty()
                val playerTitle = backStackEntry.arguments?.getString("title").orEmpty()
                PlayerScreen(
                    api = app.api,
                    progress = app.progress,
                    styles = app.styles,
                    prefs = app.prefs,
                    autoSkip = app.autoSkip,
                    animeId = playerAnimeId,
                    animeTitle = playerTitle,
                    startEpisode = backStackEntry.arguments?.getInt("ep") ?: 1,
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
}

private fun NavHostController.switchSection(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun kindOf(raw: String?): MediaKind = when (raw?.uppercase()) {
    "MOVIE" -> MediaKind.MOVIE
    else -> MediaKind.TV
}
