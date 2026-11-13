package cx.miruro.app.data

/**
 * Seasons of the same show are separate anime ids, and each id reports its own `order_release`
 * list — those lists are not always identical. That makes a season row change shape (and lose the
 * selected chip) as you move between seasons.
 *
 * This keeps one group per franchise, keyed by every member id, so any page of the same show
 * shows the same chips.
 */
class FranchiseStore {

    private val groupByAnime = mutableMapOf<String, MutableSet<String>>()

    @Synchronized
    fun seasonsOf(animeId: String): List<String>? = groupByAnime[animeId]?.toList()

    @Synchronized
    fun register(animeId: String, seasonIds: List<String>) {
        val merged = (groupByAnime[animeId] ?: mutableSetOf()).apply {
            addAll(seasonIds)
            add(animeId)
        }

        // Absorb any group that overlaps this one, so the franchise stays a single set.
        val overlapping = groupByAnime.filterKeys { key ->
            key in merged ||
                groupByAnime.getValue(key).any { it in merged }
        }
        overlapping.keys.forEach { key -> merged.addAll(groupByAnime.getValue(key)) }

        merged.forEach { member -> groupByAnime[member] = merged }
    }
}
