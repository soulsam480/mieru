# Library and watchlist

Everything in progress, across all three catalogues, then the titles the viewer
saved.

## Sub-features

- Continue watching (up to 20, newest first, hides 95% finished).
- Watchlist grid, newest first, with **Remove** per card.
- Bookmark toggle on both detail screens writes the same store the library reads.
- Share on both detail screens copies the canonical page URL.

## How to get to it (user POV)

Sidebar **Library**. Save a title from a detail page with **Add to watchlist**;
the button then reads **Remove from watchlist**.

## Driving it with mygo-automation

```sh
mygo-automation press "Library" --settle
mygo-automation snapshot -i
mygo-automation press "Add to watchlist" --settle
mygo-automation press "Library" --settle
mygo-automation press "Remove" --settle
```

The proof is the store, not just the screen: after **Remove**, the card is gone
and the empty state returns when nothing else is saved.

## Gotchas

- The store is the user's real `watchlist.json`. Restore a title you removed
  during a drive.
- The watchlist grid and the continue strip are separate; an empty one still
  leaves the other visible.
