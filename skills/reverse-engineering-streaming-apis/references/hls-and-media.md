# HLS and media CDN behaviour

The API gives you a *description* of media. Everything that makes playback fail lives one level
down, at the CDN, and almost all of it is about two headers.

## 1. The shape you must model

```
/tracks[]──track:"sub"|"ssub"|"dub"
   └──providers[]──provider:"kickassanime"|…
        ├──servers[]──server:"Vid"
        │     ├──headers: { "Referer": "https://krussdomi.com/" }
        │     └──streams[]: { url, format:"hls"|"mp4"|"webm"|"dash", quality?, embed? }
        ├──subtitles[]: { file, language, label, format:"vtt"|"srt", default? }
        ├──thumbnails: { vtt }
        └──intro/outro: { start, end }
```

Selection rules that survive provider churn:

- keep only `format in {hls, mp4}` — an `embed.url` is an iframe, not a stream; a native player
  cannot open it;
- skip a server with no usable stream and continue to the next server, then the next provider —
  an empty `streams` array is normal, not an error;
- dedupe by stream URL (providers often funnel to the same file);
- build **one header map per resolved stream** and keep it attached to that stream: its `Referer`
  belongs to that provider, and reusing it on another provider's host is a 403.

## 2. `Referer` is mandatory; `Origin` is the one everybody forgets

The payload's `Referer` is the provider's own site. The media CDN checks it. The **segment** CDN
additionally checks `Origin`, which is not in the payload — derive it:

```kotlin
val referer = server.headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value
val origin  = referer?.let { URI(it).let { u -> "${u.scheme}://${u.authority}" } }   // scheme+host only
val headers = server.headers + mapOf("Origin" to origin)
```

Verified on miruro (`recon/verify_e2e.sh`, steps 5–8):

```
stream  = https://hls.krussdomi.com/manifest/<id>/master.m3u8
referer = https://krussdomi.com/          origin = https://krussdomi.com

master  (Referer only)                    200 application/vnd.apple.mpegurl   732 B, 3 renditions
variant (Referer only)                    200                                 23 228 B, 237 segments
segment (Referer + Origin)                200
segment (Referer, no Origin)              403  text/html 4561 B
```

That 4561-byte body is `<title>Attention Required! | Cloudflare</title>` — `recon/seg0.bin`. It is
a WAF rule, not a missing file: the URL is correct and one header flips it. This is the single most
common "plays in the browser, not in the app" cause, and it is worth a control request in your test
suite so a future refactor cannot silently drop `Origin`.

Per-site header matrices, all measured:

| target | required | 403 when |
|---|---|---|
| miruro media, `*.krussdomi.com`, `st1.advancedairesearchlab.xyz` | `Referer: <payload>` + `Origin: <that referer's origin>` | either missing |
| anikoto media, `fetch.nexabloom.top`, `35b55.stellarfrontier.space` | `Referer: https://megaplay.buzz/` (on master *and* segments) | referer is `anikoto.cz`, `vidtube.site`, or absent |
| re:anime media, `vault-96.rundowncdn.top` | `Referer: https://flixcloud.cc/` (or `https://fetch10.flixcloud.cc/`) | referer is `reanime.cz`, or absent |
| any, ranged requests | nothing extra | — (`206` works once the referer is right) |

Do not hardcode the host: the segment host is often **not** the playlist host
(`hls.krussdomi.com` → `st1.advancedairesearchlab.xyz`; `fetch.nexabloom.top` →
`35b55.stellarfrontier.space`). Read the playlist, resolve, repeat.

## 3. URI resolution

```
#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="stereo",NAME="Japanese",LANGUAGE="jpn",URI="6ac67e1491be1e0a95bb0c63/playlist.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=8094866,RESOLUTION=1920x1080,AUDIO="stereo"
6ac67e1491be1e0a95bb0c5d/playlist.m3u8
```

- Lines may be **relative** (`6ac67e…/playlist.m3u8`), **absolute** (`https://…`) or
  **protocol-relative** (`//st1.advancedairesearchlab.xyz/…/000.jpg`) — the last one appears in real
  miruro variants, 237 of them. Resolve with `URI(base).resolve(child)` and special-case `//` →
  `"https:" + child` for clients that do not.
- Recurse master → variant (bound the depth; the web client uses 2 for the simple path, 4 for the
  rewrite path).
- `#EXT-X-MEDIA` audio/subtitle groups are separate playlists; drop them and the audio rendition is
  never loaded.

```kotlin
private fun resolve(base: String, child: String) = when {
    child.startsWith("//") -> "https:$child"
    child.startsWith("http") -> child
    else -> URI(base).resolve(child).toString()
}
```

## 4. The miruro HLS proxy (usually avoidable)

The web app routes playlists through `s1/s2.keeply.top` only to dodge browser CORS. A native client
does not need it — set `Referer` yourself. If you do need the exact scheme:

```
<A|B>/<base64url_nopad(utf8(target) XOR key)>[~<base64url_nopad(utf8(referer) XOR key)>][~anon]
        /pl.m3u8 | /sub.vtt | /seg.jpg
key = hex a54d389c18527d9fd3e7f0643e27edbe          (16 bytes, repeating)
```

Shard choice is FNV-1a32 of `"<romajiTitle>|<episodeNumber>"`, parity → A/B (both edges work).
Decoding: take the first path component up to `~`; if it matches `^https?%3a%2f%2f` URL-decode it,
else base64url-decode and XOR. `~anon` is appended when the provider config says `proxy.rotate` —
semantics opaque, harmless to copy.

Playlist rewriting rules the app applies, worth copying when a provider demands them: substitute
the configured segment origin host, inject `hls.query.params` into every URI (deleting `token`
first when `skip` is set), and refuse playlists that are not `#EXTM3U` or lack
`#EXT-X-STREAM-INF`/`#EXT-X-ENDLIST`. Its own guards are 32 distinct playlists, depth 4, 2 MiB per
playlist — reasonable bounds for any client.

## 5. Disguised extensions and file types

Segments are routinely named `.jpg`, `.png`, `.webp` and served as `image/jpeg` / `image/png`:

```
seg-1-f1-v1-a1.jpg   200  436 536 B  image/jpeg      (anikoto/megaplay)
seg-0-f1-v1-a0.png   200   81 040 B  image/png       (re:anime)
000.jpg              200                             (miruro)
```

ExoPlayer's HLS parser classifies by position in the playlist (`#EXTINF` → segment), not by
extension or content-type, so this is a non-issue **as long as you never "fix" the URL** and never
filter segments by suffix. Do not infer media type from the path anywhere in your code.

`Range` requests work (`206`), which is what seeking needs.

## 6. Encryption can live inside the playlist

An "obfuscated" API can still terminate in standard HLS encryption — check the variant before
assuming the bytes are opaque:

```
#EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0xc5f60fecf08fc0b717ba3f8011ef1bfd
#EXTINF:6.006000,
https://vault-96.rundowncdn.top/_v7/<id>/seg-0-f1-v1-a0.png
```

`key.bin` is 16 bytes, fetched with the **same `?token=`** as the playlist; segments are ordinary
AES-128-CBC MPEG-TS (first plaintext byte `0x47`). ExoPlayer handles AES-128 itself and applies the
same `DefaultHttpDataSource` headers to the key request — which is why that factory must carry the
full header map, not just a UA.

## 7. ExoPlayer / Media3 wiring

```kotlin
val dataSource = DefaultHttpDataSource.Factory()
    .setUserAgent(Http.UA)                          // a browser UA; the library default is a fingerprint
    .setDefaultRequestProperties(stream.headers)    // applies to playlist, variant, segments, keys, subs
    .setAllowCrossProtocolRedirects(true)           // http→https CDN redirects

val item = MediaItem.Builder()
    .setUri(stream.url)
    .setMimeType(if (stream.isProgressive) MimeTypes.VIDEO_MP4 else MimeTypes.APPLICATION_M3U8)
    .setSubtitleConfigurations(stream.subtitles.map(::subtitleConfiguration))
    .build()

DefaultMediaSourceFactory(dataSource).createMediaSource(item)
```

Three hard-won points:

1. **Never construct `HlsMediaSource` yourself when you have side-loaded subtitles.** Doing so
   bypasses `DefaultMediaSourceFactory`, which is the component that wraps the HLS source in a
   `MergingMediaSource` with `MediaItem.subtitleConfigurations`. The result is not an error — the
   subtitles simply never appear. This was a real shipped bug.
2. **Subtitles inherit the stream's headers**, not their own host's origin. A subtitle on
   `subst.krussdomi.com` is fetched with the `Referer` of `krussdomi.com`. Verify by curling the
   `.vtt` with the stream header map.
3. **ExoPlayer swallows non-fatal load failures** (a subtitle that 403s, one segment that times
   out). Attach an `AnalyticsListener.onLoadError` that logs `loadEventInfo.uri` +
   `mediaLoadData.trackType`; otherwise your only symptom is "no subtitles" with a clean logcat.

Also needed to make a text track actually show up: `setPreferredTextLanguage("en")`,
`setSelectUndeterminedTextLanguage(true)`, `setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)`.

## 8. Verification recipe

Curling is the only way to know whether a failure is your player or the CDN:

```bash
MASTER=… ; REF=… ; ORIGIN=$(printf '%s' "$REF" | sed -E 's#^(https?://[^/]+)/?.*#\1#')

curl -s -D - -o master.m3u8 -H "referer: $REF" "$MASTER"          # 200 + #EXTM3U
VAR=$(rg -o '^[A-Za-z0-9]+/playlist\.m3u8' master.m3u8 | head -1)
curl -s -o var.m3u8 -H "referer: $REF" "$(dirname "$MASTER")/$VAR" # #EXTINF lines
SEG=$(rg '^//' var.m3u8 | head -1)
curl -s -o /dev/null -w "%{http_code} %{content_type} %{size_download}\n" \
     "https:$SEG" -H "referer: $REF" -H "origin: $ORIGIN"          # 200/206
curl -s -o /dev/null -w "%{http_code}\n" "https:$SEG" -H "referer: $REF"   # 403 — the control
```

Then assert the same facts in a JVM test, over several sources, because providers differ between
calls:

- master: `200`, body starts `#EXTM3U`, contains `#EXT-X-STREAM-INF`;
- variant: `200`, contains `#EXTINF`;
- first segment: `200` **or** `206`, body non-empty;
- one subtitle: `200` and body contains `WEBVTT`.
