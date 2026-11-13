# Case studies — four sites, four verdicts

All probing was plain `curl` / `curl_cffi` / `python3`, 2026-11-13, plus offline reading of
downloaded bundles. Numbers are measured, not estimated.

| site | data API | gate | media chain | verdict |
|---|---|---|---|---|
| **miruro.cx** | same-origin `/api/v1`, XOR+gzip | HTTP/2 + `Sec-Fetch-Site` + `Referer` | direct HLS + `Referer`+`Origin` | **shipped as v1** |
| **anikoto.cz** | HTML-in-JSON `/ajax/*` | `X-Requested-With` | 4 hops, AES-256-CBC, `Referer` | viable |
| **reanime.cz** | clean JSON `/api/v1` | none (CF passive only) | embed → wasm+PBKDF2+AES, IP-bound JWT | viable, fragile |
| **animepahe.pw** | clean JSON `/api?m=…` | Cloudflare JS challenge, every path | packed JS → CDN also 403 | **not viable natively** |

---

## 1. Miruro — the one we shipped

**What it took.** Downloaded the production bundle instead of instrumenting a browser: entry chunk
`index-DyxIBWZg.js` = 396 762 bytes on 234 physical lines, plus 74 lazy chunks (the captured notes).
Everything came from reading it; no network capture was needed to find the API.

**Numbers.**

- API base is same-origin `/api/...` — there is no `VITE_API_URL`, the client prefixes `/api/`
  itself (`assets/Button-BTdICcI9.js:1 @11936`). `env2.js` only exposes `VITE_PROXY_A/B`,
  `VITE_PROXY_OBF_KEY`, `VITE_PLAYER_ORIGIN`.
- Payload: 14-byte XOR key `miruro/catalog` then gzip. Same key for every endpoint.
- Gate: `curl --http1.1` → `403`; `curl --http2` + `Sec-Fetch-Site: same-origin` +
  `Referer: https://www.miruro.cx/` → `200`, repeatedly, no cookie or token.
- Pagination: `limit` + opaque `cursor`. Adding `page=1` → `400 Unsupported catalog request`
  (problem+json); `limit` must be a value the server recognises — the client sends 12/15 for
  catalog rails (5, 12, 15, 18, 100, 200, 10000 appear across the bundle).
- Media: master playlist 732 B, 3 video renditions (1920x1080 `avc1.4D4C28`, 1280x720, 640x360)
  plus one audio group; variant 23 228 B with 237 segment lines, `TARGETDURATION:6`,
  `#EXTINF:6.006011`; segment host differs from playlist host
  (`hls.krussdomi.com` → `st1.advancedairesearchlab.xyz`), segments named `.jpg`.
- Segment CDN: `Referer` + `Origin` → `200`; `Referer` only → `403` with a 4561-byte
  `Attention Required! | Cloudflare` page (the captured notes). Subtitle `.vtt` → `200`, ~20 KB.
- Progress is localStorage-only; there is no `/progress` or `/sync` endpoint — which is why a
  native client can track watch time locally with no account.

**What went wrong (and became the app's bug history).**

1. The default `HlsMediaSource` build dropped every side-loaded subtitle. Root cause:
   `DefaultMediaSourceFactory` is what merges them; constructing the source by hand bypassed it.
   Symptom was silence, not an exception.
2. One provider (`icarus`) listed `.jpeg` paths as subtitles; they returned 403 HTML. Filtering to
   real `.vtt` was necessary but not sufficient — see 3.
3. Subtitles live on a different host (`subst.krussdomi.com`) than the stream
   (`hls.krussdomi.com`) but need the **stream's** `Referer`. A per-host header map is wrong.
4. Reusing the API `OkHttpClient` for media stamped the miruro `Referer` over the provider's. Media
   requests get their own bare client.
5. A "no playable stream" error was shown while video played underneath — a Kotlin elvis-operator
   bug in the view model's error branch, not a network problem. Worth remembering when a symptom
   contradicts the logs.

**Outcome:** one backend, three screens, no WebView, no server. the captured notes (889 lines)
is the contract; the captured notes is the reference client; the captured notes proves the chain.

---

## 2. Anikoto — four hops and one AES call

**Numbers.**

- Domains: `anikoto.cz` 200 (24 609 B); `.to` timeout, `.bz` NXDOMAIN, `.in`/`.ru` TLS error.
- Gate is not Cloudflare, it is the app: without `X-Requested-With: XMLHttpRequest` every AJAX
  endpoint returns HTTP **200** with `{"status":500,"result":"Bad request"}`. Guest CSRF token is
  empty (`content=""`), and the `vrf` parameter is not validated.
- No JSON model API: `/ajax/anime/search` is 3 444 B of HTML inside `{"status":200,"result":"<html>…"}`;
  `/filter?page=` is a 113 KB page whose cards carry `data-tip="4587"` (the numeric id); the watch
  page is 83 069 B.
- Episode list: `POST /ajax/episode/list/{id}` → anchors with numeric episode id `114623` and a
  `data-ids` opaque token of 230–256 base64 chars, passed verbatim to the server list.
- Four hops: `data-ids` → `/ajax/server/list` → `data-link-id` → `/ajax/server?get=` →
  `https://megaplay.buzz/stream/s-2/131394/sub?s=tcdn` → embed page (4 773 B, `data-id="2649"`) →
  `/stream/getSources?id=2649` (403 without `Referer: https://megaplay.buzz/`) → `enc`.
- `enc` → AES-256-CBC, key `i?LMTAx0Q6,:}50U` zero-padded to 32 bytes, fixed IV, ~15 lines of
  Kotlin.
- Media: master needs `Referer: https://megaplay.buzz/` (`anikoto.cz` → 403, `vidtube.site` → 403,
  none → 403); variant 50 352 B, `#EXTINF:3.84`; segment 436 536 B served as `image/jpeg` with a
  `.jpg` name.

**Lesson:** the crypto was the cheap part. The cost was three HTML parses and four sequential
requests per episode, with rotating domains and CDN hosts. Every one of those is a maintenance
item; the AES key is the only part people worry about.

---

## 3. Re:Anime — beautiful metadata, hostile media layer

**Numbers.**

- Canonical host `reanime.cz` (`reanime.to` 301s there); `reanime.bz`/`.wtf` same app; `.org` dead;
  `reanime.pro` is an unrelated WordPress site. A duplicate API origin, `api.reanime.cz`, serves the
  same `/api/v1/*` — but **not** `/api/flix/...` (404).
- Cloudflare in front of everything, yet plain curl works: `HTTP/2 200`, no cookie, no
  `cf_clearance`, default UA. The `/cdn-cgi/challenge-platform/.../jsd/main.js` in the HTML is
  passive telemetry only.
- Data: search 1 630 B; detail 30 418 B / 60 flat keys; episodes 17 244 B. No auth anywhere.
- `/api/flix/{anilist_id}/{ep}?mal=&tmdb=&season=` returns **879 B of embed iframes**, not media:
  `{"success":true,"servers":[{"serverName":"HD-1","dataLink":"https://flixcloud.cc/e/<id>?v=1",…}]}`.
- Embed page 25 150 B; wasm module **357 bytes** exporting `memory,_s,_r,_c`; per-request
  `obfuscation_seed`; field names hashed from the seed; `/api/m3u8/{token}` **single-use**
  (`410 {"error":"invalid_or_used_token"}` on the second call); master URL JWT is HS256 with
  `client_ip`, 6 h TTL, reusable.
- Playlist bodies are base64(XOR) with a constant 32-byte key from `_c()`. Segments: 81 040 B PNG,
  `206` ranged OK, `Referer: https://flixcloud.cc/` required (`reanime.cz` → 403); `key.bin`
  16 bytes, same `?token=` as the playlist; plaintext starts `47 40 11 10` (MPEG-TS sync).
- Rate limits were not the issue: 12 sequential search + 10 `/api/flix` calls ~0.3 s apart all 200.

**What worked:** the captured notes executes the real wasm and prints a decrypted
master URL (with a 1920x1080 rendition) every run. No guesswork, no reimplementation of `_s/_r/_c`.

**What did not:** anything stable. Field names change per load, the bootstrap token is one-shot,
the media JWT pins the client IP, and `metadata.version:"2.1"` says the scheme is versioned. Best
metadata of the three; worst thing to depend on.

---

## 4. Animepahe — where the answer is "no"

**Numbers.**

- `animepahe.com`/`.org` → 301 → `animepahe.pw` → **403, 5 379 B, `<title>Just a moment...</title>`**.
  `.ru` timeout, `.si` HTTP/2 `PROTOCOL_ERROR`.
- Every vantage failed identically: curl h2 with browser UA; `--http1.1` + full `Sec-Fetch-*`;
  `curl_cffi` impersonating chrome/chrome124/safari17_0/firefox133; `r.jina.ai` returned 200 with
  *"Performing security verification"*; public proxies returned 520/522. Wayback crawls from
  Apr–May 2026 also captured the challenge page.
- The JSON behind the wall is clean and would have been trivial: `?m=search&q=` 8/page (total 1000),
  `?m=airing` 12/page (total 6240), `?m=release&id=<uuid>` 30/page. Laravel paginator, UUID
  sessions, no auth.
- The packer was solved offline: 4 real kwik pages, 4 clean m3u8 URLs, base62 Dean-Edwards unpacker.
- The media CDN (`vault-04.uwucdn.top`) also 403s, but with `Attention Required!` / "you have been
  blocked" — a WAF rule, *not* the JS challenge. Whether a fresh token + browser headers would pass
  is **unproven**, because a fresh token cannot be minted without executing the challenge.

**Verdict:** not viable for a no-WebView native client. The honest framing matters: this is not
"hard", it is "requires the browser machinery the design forbids". Anything that passes it ends up
being a solver service or a `cf_clearance` farm, i.e. the relay the project decided not to run.

---

## Cross-cutting lessons

1. **The gate decides the product, not the crypto.** The hardest cipher here (re:anime) is a
   weekend port; the simplest site (animepahe) is impossible.
2. **Cheap JSON is not the same as playable media.** Three of four APIs hand you provider lists and
   then stop. Budget most of the work for the last two hops.
3. **Numbers beat adjectives in the report.** "23 228 B variant, 237 segments, 403 without Origin"
   is reusable; "HLS works" is not.
4. **Every host rotates.** `fetch.nexabloom.top`, `35b55.stellarfrontier.space`,
   `vault-96.rundowncdn.top`, `vault-13.owocdn.top` — read them from the payload or playlist, every
   time, and make the API base a user-editable setting.
5. **Keep one extension point.** Every alternate maps onto the same `ResolvedStream { url,
   mimeHint, headers, subtitles, provider, server }`; adding a backend is one resolver, no UI change
   (the captured notes). Two of the three are still worth adding if the primary dies.
