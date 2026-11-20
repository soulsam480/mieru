# Browse anime

The grid of anime from Miruro, with the four feeds, genre filters, search, and
the continue-watching strip.

## Sub-features

- Latest (recently aired episodes), Popular, Top rated, A–Z.
- Genre chips, which only appear outside Latest.
- Debounced search across the whole catalogue.
- Infinite paging with a **Load more** tile.
- Continue watching on Latest when something is in progress.

## How to get to it (user POV)

Start the app, or press **Anime** in the sidebar. The page title is `Anime`;
the search field says `Search anime`.

## Driving it with mygo-automation

```sh
mygo-automation press "Popular" --settle --timeout 20s
mygo-automation press "Action" --settle --timeout 20s
mygo-automation fill "Search anime" "dandadan" --settle --timeout 20s
mygo-automation press "A–Z" --settle --timeout 20s
```

`fill` targets the field by its placeholder. After search, the grid shows
matching titles; clearing the field returns the catalogue feed.

## Gotchas

- Latest makes two calls (schedule, then hydrate), so give it 20 s.
- The chips scroll horizontally; a chip off screen still has an element.
- **Load more** is the last grid tile, not a footer.
