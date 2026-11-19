package cx.miruro.app

import cx.miruro.app.data.oneshows.MediaKind
import cx.miruro.app.data.oneshows.OneShowsJson
import cx.miruro.app.data.oneshows.SearchDto
import cx.miruro.app.data.oneshows.SeasonDetailDto
import cx.miruro.app.data.oneshows.SeasonInfo
import cx.miruro.app.data.oneshows.TrendingDto
import cx.miruro.app.data.oneshows.TvDetailDto
import cx.miruro.app.data.oneshows.toCard
import cx.miruro.app.data.oneshows.toDetail
import cx.miruro.app.data.oneshows.toEpisode
import cx.miruro.app.ui.media.defaultSeason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OneShowsMappingTest {

    private val searchJson = """
        {
          "page": 1,
          "results": [
            {
              "id": 1396,
              "media_type": "tv",
              "name": "Breaking Bad",
              "overview": "A chemistry teacher turns to crime.",
              "poster_path": "/anFx9aTOOYqgS3v7x3R84Kz67ly.jpg",
              "first_air_date": "2008-01-20",
              "vote_average": 8.959
            },
            {
              "id": 559969,
              "media_type": "movie",
              "title": "El Camino",
              "overview": "",
              "poster_path": null,
              "release_date": "2019-10-11",
              "vote_average": 7.055
            },
            {
              "id": 17419,
              "media_type": "person",
              "name": "Bryan Cranston"
            }
          ],
          "total_pages": 1
        }
    """.trimIndent()

    private val tvDetailJson = """
        {
          "id": 1396,
          "name": "Breaking Bad",
          "overview": "A chemistry teacher turns to crime.",
          "poster_path": "/anFx9aTOOYqgS3v7x3R84Kz67ly.jpg",
          "backdrop_path": "/tsRy63Mu5cu8etL1X7ZLyf7UP1M.jpg",
          "first_air_date": "2008-01-20",
          "vote_average": 8.959,
          "genres": [
            { "id": 18, "name": "Drama" },
            { "id": 80, "name": "Crime" }
          ],
          "number_of_seasons": 5,
          "episode_run_time": [],
          "seasons": [
            {
              "air_date": "2009-02-17",
              "episode_count": 9,
              "id": 3577,
              "name": "Specials",
              "season_number": 0
            },
            {
              "air_date": "2008-01-20",
              "episode_count": 7,
              "id": 3572,
              "name": "Season 1",
              "season_number": 1
            }
          ]
        }
    """.trimIndent()

    private val seasonJson = """
        {
          "id": 3572,
          "episodes": [
            {
              "air_date": "2008-01-20",
              "episode_number": 1,
              "name": "Pilot",
              "overview": "The first cook.",
              "runtime": 59,
              "still_path": "/ydlY3iPfeOAvu8gVqrxPoMvzNCn.jpg"
            },
            {
              "air_date": "2008-01-27",
              "episode_number": 2,
              "name": "Cat's in the Bag...",
              "overview": "",
              "still_path": null
            }
          ]
        }
    """.trimIndent()

    private val trendingJson = """
        {
          "success": true,
          "tmdbIds": ["5920", "95350", "2190"]
        }
    """.trimIndent()

    @Test
    fun search_rows_map_to_cards_and_unknown_media_types_are_dropped() {
        val page = OneShowsJson.decodeFromString(SearchDto.serializer(), searchJson)

        assertEquals(3, page.results.size)

        val cards = page.results.mapNotNull { it.toCard() }
        assertEquals(2, cards.size)

        val show = cards.first()
        assertEquals("1396", show.id)
        assertEquals(MediaKind.TV, show.kind)
        assertEquals("Breaking Bad", show.title)
        assertEquals(
            "https://image.tmdb.org/t/p/w500/anFx9aTOOYqgS3v7x3R84Kz67ly.jpg",
            show.posterUrl
        )
        assertEquals(2008, show.year)
        assertEquals(8.959, show.score!!, 0.0001)
    }

    @Test
    fun a_missing_poster_path_yields_no_image_url() {
        val page = OneShowsJson.decodeFromString(SearchDto.serializer(), searchJson)

        val movie = page.results.mapNotNull { it.toCard() }.last()

        assertEquals(MediaKind.MOVIE, movie.kind)
        assertEquals("El Camino", movie.title)
        assertNull(movie.posterUrl)
        assertEquals(2019, movie.year)
    }

    @Test
    fun tv_detail_maps_genres_seasons_and_leaves_runtime_null() {
        val detail =
            OneShowsJson.decodeFromString(TvDetailDto.serializer(), tvDetailJson)
                .toDetail("1396")

        assertEquals("1396", detail.id)
        assertEquals(MediaKind.TV, detail.kind)
        assertEquals("Breaking Bad", detail.title)
        assertEquals("A chemistry teacher turns to crime.", detail.overview)
        assertEquals(
            "https://image.tmdb.org/t/p/w500/anFx9aTOOYqgS3v7x3R84Kz67ly.jpg",
            detail.posterUrl
        )
        assertEquals(
            "https://image.tmdb.org/t/p/w1280/tsRy63Mu5cu8etL1X7ZLyf7UP1M.jpg",
            detail.backdropUrl
        )
        assertEquals(2008, detail.year)
        assertEquals(8.959, detail.score!!, 0.0001)
        assertEquals(listOf("Drama", "Crime"), detail.genres)
        assertEquals(2, detail.seasons.size)
        assertEquals(0, detail.seasons.first().number)
        assertEquals("Season 1", detail.seasons.last().name)
        assertEquals(7, detail.seasons.last().episodeCount)
        assertNull(detail.runtimeMinutes)
    }

    @Test
    fun season_rows_map_to_episodes_and_a_missing_runtime_stays_null() {
        val episodes =
            OneShowsJson.decodeFromString(SeasonDetailDto.serializer(), seasonJson)
                .episodes
                .map { it.toEpisode() }

        assertEquals(2, episodes.size)

        val pilot = episodes.first()
        assertEquals(1, pilot.number)
        assertEquals("Pilot", pilot.title)
        assertEquals("The first cook.", pilot.overview)
        assertEquals(
            "https://image.tmdb.org/t/p/w500/ydlY3iPfeOAvu8gVqrxPoMvzNCn.jpg",
            pilot.stillUrl
        )
        assertEquals("2008-01-20", pilot.airDate)
        assertEquals(59, pilot.runtimeMinutes)

        val second = episodes.last()
        assertEquals(2, second.number)
        assertNull(second.stillUrl)
        assertNull(second.runtimeMinutes)
        assertTrue(second.overview.isEmpty())
    }

    @Test
    fun trending_ids_hydrate_into_cards_with_the_requested_identity() {
        val feed = OneShowsJson.decodeFromString(TrendingDto.serializer(), trendingJson)

        assertTrue(feed.success)
        assertEquals(listOf("5920", "95350", "2190"), feed.tmdbIds)

        val detail =
            OneShowsJson.decodeFromString(TvDetailDto.serializer(), tvDetailJson)
                .toDetail(feed.tmdbIds.first())
        val card = detail.toCard()

        assertEquals("5920", card.id)
        assertEquals(MediaKind.TV, card.kind)
        assertEquals("Breaking Bad", card.title)
        assertEquals(2008, card.year)
    }

    @Test
    fun the_default_season_is_one_unless_it_is_empty() {
        val specials = SeasonInfo(number = 0, name = "Specials", episodeCount = 9)
        val seasonOne = SeasonInfo(number = 1, name = "Season 1", episodeCount = 7)
        val emptyOne = SeasonInfo(number = 1, name = "Season 1", episodeCount = 0)
        val seasonTwo = SeasonInfo(number = 2, name = "Season 2", episodeCount = 5)

        assertEquals(seasonOne, defaultSeason(listOf(specials, seasonOne)))
        assertEquals(seasonTwo, defaultSeason(listOf(emptyOne, seasonTwo)))
        assertEquals(specials, defaultSeason(listOf(specials)))
        assertNull(defaultSeason(emptyList()))
    }
}
