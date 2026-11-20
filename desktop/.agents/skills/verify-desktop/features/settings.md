# Settings

The Miruro mirror list, the 1shows site, playback preferences, subtitle style,
and about links.

## Sub-features

- Choose a Miruro mirror, add one, test it, reset to defaults.
- Change the 1shows site; the `db.` metadata host follows it.
- Preferred audio (Sub/Dub).
- Auto-skip intro and outro.
- Subtitle size, colour, background, outline.
- Open the data folder, GitHub and issues links.

## How to get to it (user POV)

Sidebar **Settings**. Values write immediately, except the two URL fields, which
have **Add** and **Save**.

## Driving it with mygo-automation

```sh
mygo-automation press "Settings" --settle
mygo-automation snapshot -i
mygo-automation press "Dub" --settle
mygo-automation press "XL" --settle
mygo-automation press "Test" --settle --timeout 25s
```

The **Test** result reads `<base> answers` or `<base> did not answer`.

## Gotchas

- Changing the mirror marks the anime, TV and movie pages for reload; their
  grids reload on the next visit.
- The settings file is the user's real `settings.json`. Put back a mirror or
  style you changed during a drive.
- **Reset** restores every default, including the site and the style.
