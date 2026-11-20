# Player

Native video for anime and for TV/movie mirrors, with the full control surface.

## Sub-features

- HLS or MP4 through the loopback proxy, so Referer/Origin/Cookie reach the CDN.
- Play/pause, seek, ±10 s, volume, mute, speed, fullscreen.
- Sources panel: anime subtracks and resolved embed mirrors; alternates resolve
  in the background.
- Subtitle panel: embedded VTT for anime, OpenSubtitles for TV/movies, one track
  per language, style from Settings.
- Auto-skip intro/outro, up-next card, progress saved every 5 s.

## How to get to it (user POV)

From an anime detail, press **Watch EP n** or an episode row. From TV, press an
episode row; from a movie, **Play movie**. The window turns black with the
video and a top bar (`‹ Back`, title, `EP n`, `Info`, `Full`).

## Driving it with mygo-automation

```sh
mygo-automation press "Watch EP 1" --settle --timeout 90s
mygo-automation snapshot -i          # Play/Pause, -10, +10, CC, Sources, Speed
mygo-automation press "Sources 1" --settle
mygo-automation press "CC on" --settle
mygo-automation press "Pause" --settle
```

A proof reads the time label twice, ten seconds apart, and confirms it moved.

## Gotchas

- The embed resolver opens hidden mygo windows; a TV/movie play can take 30–60 s
  per mirror before one answers.
- Embed sources do not carry intro/outro markers, so the skip pill is anime only.
- Changing source keeps position and speed; it reopens the proxy session.
- Fullscreen changes the real window; toggle it back before cleanup.
