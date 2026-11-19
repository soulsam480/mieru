package cx.miruro.app.ui

import android.net.Uri
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import cx.miruro.app.data.embed.PlaybackHandoff
import cx.miruro.app.data.oneshows.MediaKind
import cx.miruro.app.ui.detail.DetailScreen
import cx.miruro.app.ui.list.ListScreen
import cx.miruro.app.ui.media.ResumeTarget
import cx.miruro.app.ui.media.ShowDetailScreen
import cx.miruro.app.ui.media.ShowsScreen
import cx.miruro.app.ui.player.PlayerScreen
import cx.miruro.app.ui.settings.SettingsScreen

private const val ROUTE_ANIME = "anime"
private const val ROUTE_SHOWS_TV = "shows/tv"
private const val ROUTE_SHOWS_MOVIE = "shows/movie"
private const val ROUTE_SHOW_DETAIL =
    "shows/{kind}/{id}?title={title}&season={season}&episode={episode}&resume={resume}"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_DETAIL = "detail/{id}?title={title}"
private const val ROUTE_PLAYER = "player/{id}/{ep}?title={title}"
private const val ROUTE_EMBED_PLAYER = "embed-player"

private data class Section(val route: String, val label: String, val icon: ImageVector)

private val sections =
    listOf(
        Section(ROUTE_ANIME, "Anime", Icons.Filled.PlayArrow),
        Section(ROUTE_SHOWS_TV, "TV", Icons.AutoMirrored.Filled.List),
        Section(ROUTE_SHOWS_MOVIE, "Movies", Icons.Filled.Star)
    )

@Composable
fun MiruroNavHost(app: MiruroApp) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showSections =
        route == ROUTE_ANIME || route == ROUTE_SHOWS_TV || route == ROUTE_SHOWS_MOVIE
    val selectedSection =
        when {
            route == ROUTE_ANIME || route == ROUTE_SHOWS_TV || route == ROUTE_SHOWS_MOVIE -> route

            // A detail page keeps its section highlighted.
            route == ROUTE_SHOW_DETAIL ->
                if (entry?.arguments?.getString("kind") == "MOVIE") {
                    ROUTE_SHOWS_MOVIE
                } else {
                    ROUTE_SHOWS_TV
                }

            else -> null
        }

    Scaffold(
        // The inner screens own the status bar inset; this scaffold only reserves room for tabs.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showSections) {
                NavigationBar {
                    sections.forEach { section ->
                        NavigationBarItem(
                            selected = section.route == selectedSection,
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
            modifier = Modifier.padding(bottom = padding.calculateBottomPadding())
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

            composable(ROUTE_SHOWS_TV) {
                ShowsScreen(
                    api = app.oneShows,
                    kind = MediaKind.TV,
                    progress = app.progress,
                    onResume = { item ->
                        nav.navigate(
                            showDetailRoute(item.animeId, "TV", item.title) +
                                "&season=${item.season}&episode=${item.episode}" +
                                "&resume=${item.positionMs}"
                        )
                    },
                    onOpen = { card ->
                        nav.navigate(showDetailRoute(card.id, "TV", card.title))
                    }
                )
            }

            composable(ROUTE_SHOWS_MOVIE) {
                ShowsScreen(
                    api = app.oneShows,
                    kind = MediaKind.MOVIE,
                    progress = app.progress,
                    onResume = { item ->
                        nav.navigate(
                            showDetailRoute(item.animeId, "MOVIE", item.title) +
                                "&season=${item.season}&episode=${item.episode}" +
                                "&resume=${item.positionMs}"
                        )
                    },
                    onOpen = { card ->
                        nav.navigate(showDetailRoute(card.id, "MOVIE", card.title))
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
                    },
                    navArgument("season") {
                        type = NavType.IntType
                        defaultValue = 0
                    },
                    navArgument("episode") {
                        type = NavType.IntType
                        defaultValue = 0
                    },
                    navArgument("resume") {
                        type = NavType.LongType
                        defaultValue = 0L
                    }
                )
            ) { backStackEntry ->
                val args = backStackEntry.arguments
                val resumeEpisode = args?.getInt("episode") ?: 0
                ShowDetailScreen(
                    api = app.oneShows,
                    mediaId = args?.getString("id").orEmpty(),
                    kind = kindOf(args?.getString("kind")),
                    fallbackTitle = args?.getString("title").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onPlay = { request ->
                        PlaybackHandoff.offer(request)
                        nav.navigate(ROUTE_EMBED_PLAYER)
                    },
                    resume =
                        if (resumeEpisode > 0) {
                            ResumeTarget(
                                season = args?.getInt("season") ?: 1,
                                episode = resumeEpisode,
                                startMs = args?.getLong("resume") ?: 0L
                            )
                        } else {
                            null
                        }
                )
            }

            composable(ROUTE_EMBED_PLAYER) {
                // Claimed once: reading the handoff per-composition would lose it on recompose.
                val pending = remember { PlaybackHandoff.take() }
                if (pending == null) {
                    LaunchedEffect(Unit) { nav.popBackStack() }
                } else {
                    PlayerScreen(
                        api = app.api,
                        progress = app.progress,
                        styles = app.styles,
                        prefs = app.prefs,
                        autoSkip = app.autoSkip,
                        animeId = pending.mediaId,
                        animeTitle = pending.title,
                        startEpisode = pending.episode,
                        onBack = { nav.popBackStack() },
                        // The detail page sits directly beneath, so Info is just Back.
                        onOpenInfo = { nav.popBackStack() },
                        direct = pending
                    )
                }
            }

            composable(ROUTE_SETTINGS) {
                SettingsScreen(
                    api = app.api,
                    sources = app.sources,
                    updates = app.updates,
                    prefs = app.prefs,
                    autoSkip = app.autoSkip,
                    oneShowsSource = app.oneShowsSource,
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
        restoreState = true
    }
}

private fun kindOf(raw: String?): MediaKind = when (raw?.uppercase()) {
    "MOVIE" -> MediaKind.MOVIE
    else -> MediaKind.TV
}

/** Detail routes carry the title for instant headers, plus the section the page belongs to. */
private fun showDetailRoute(id: String, kind: String, title: String): String =
    "shows/$kind/$id?title=${Uri.encode(title)}"
