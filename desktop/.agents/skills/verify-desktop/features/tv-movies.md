# TV and movies

The 1shows catalogue: trending grids, search, and a detail page with seasons and
episodes.

## Sub-features

- Trending TV and trending movies.
- Debounced search, shared by both sections.
- Detail: backdrop, poster, score, genres, overview.
- Anime detail: franchise season switcher and released-episode rules.
- TV detail: season chips, episode list with air dates and watched percentages.
- Watchlist and Share actions in the detail bar.

## How to get to it (user POV)

Sidebar **TV Shows** (field says `Search tv shows`) or **Movies** (`Search
movies`). Click a card to open its detail page.

## Driving it with mygo-automation

```sh
mygo-automation press "TV Shows" --settle --timeout 25s
mygo-automation snapshot -i
mygo-automation press @e3 --settle --timeout 25s   # a card from the snapshot
mygo-automation press "Share" --settle
```

Share writes the canonical page URL to the clipboard and shows `Link copied`.

## Gotchas

- Trending needs one detail request per id; first paint can take 10–20 s.
- Cards are `ButtonBase`, so the label comes from the first text inside them.
- Anime detail comes from `/anime/{id}`, TV and movies from
  `/shows/{kind}/{id}`; the sidebar still highlights TV Shows or Movies.
