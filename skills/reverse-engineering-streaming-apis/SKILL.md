---
name: reverse-engineering-streaming-apis
description: Reverse-engineer a third-party streaming web app's private API and build a native client on top of it (Android/Media3-ExoPlayer, or any HTTP stack). Triggers include "reverse engineer this site's API", "build a native client for X", "can we make this a native app", "the stream plays in the browser but not in my app", "figure out how this web app talks to its backend", or a native app that gets 403 / a black screen where the browser plays fine.
---

# Reverse-engineering a streaming API and shipping a native client

The browser is already a working reference implementation of the protocol you need. Port that
protocol — do not scrape a page or reimplement the UI: read the client bundle until you can write
the decoder by hand, then prove it with curl.

## When to use / when not to

Use when a web app's data comes from an undocumented same-origin API and you want a native UI, when
media plays in the browser but 403s or black-screens in your app, or when you must decide whether a
site can be a native client at all. Skip it when the API is documented, when DRM is in the path (a
native client cannot decrypt Widevine), or when the only gate is a JS challenge — see "Deciding".

## The loop (inspect → capture → decode → verify)

1. **Pin the live host.** Enumerate TLDs and follow redirects: `anikoto.cz` is the only live anikoto
   TLD; `animepahe.com` 301s to a challenge page; `reanime.to` 301s to `reanime.cz`.
2. **Inspect from a real browser, then capture the client code**: find the request whose response is
   not JSON but that the UI parsed, then find where its bytes become JSON (`DecompressionStream`,
   `crypto.subtle`, `eval(function`, `atob(`) — that snippet *is* the spec. Reproduce it offline
   against one recorded response; never infer an algorithm from the plaintext (Step 2).
3. **Bisect the edge requirements** — protocol version first, then one header at a time — until
   plain curl gets 200 (Step 4). One unknown per experiment: header gates, TLS gates and JS
   challenges look identical when you change two variables at once.
4. **Walk the media chain to a real segment byte** (not just a playlist URL), then **prove it twice**:
   curl, then a JVM test asserting a playlist *and* a segment *and* a subtitle body (Steps 5–6).
5. **Write the contract down before app code** — endpoints, decode, headers, media rules — with `file:line` evidence and a confidence tag ([C] in code, [I] inferred, [?] unknown). `API-NOTES.md` (889 lines) is this artefact; it is what makes the next session cheap.

## Step 1: capture the traffic

```bash
curl -sS -o /dev/null -w "%{http_code} %{url_effective}\n" -L https://site.tld/
curl -sS -D - -o /dev/null --http2 "https://site.tld/api/v1/x"      # status + headers, no body
```

Read `content-type` first: `application/octet-stream` on a JSON-shaped endpoint means an encoded
payload; `text/html` on an API path means a WAF or an error page, not an empty result. When curl
cannot reproduce a request (CORS, cookies, a challenge), fetch it **inside the page** and write
base64 to disk — `recon/fetch_one.sh` does that with `playwright-cli eval` + `fetch` + `btoa`, which
is how the 396 762-byte entry chunk and 74 lazy chunks were captured.

## Step 2: get the client code (download bundles, unminify mentally, grep)

- Download the entry chunk plus every `<link rel="modulepreload">` / `assets/*` chunk; lazy chunks hold the interesting code (miruro: `Button-BTdICcI9.js` decoder, `WatchRoute-CaatKnsX.js` HLS proxy).
- Grab `env2.js` / `window.env` and the SSR blob (`window.__SSR_DATA__`) — that is where keys, proxy hosts and client ids are injected, and it reuses the same decoder: a free test vector.
- Grep the primitives, not the endpoints: `grep -o 'DecompressionStream([^)]*)'`, `grep -c 'crypto.subtle'`, `grep -o 'eval(function(p,a,c,k,e,d)'`.
- Chunks are 1–5 physical lines of thousands of bytes: record **line + byte offset** (`Button-BTdICcI9.js:1 @10640`). A citation you cannot re-find is a rumour.
- Read every *call site* of the function you found; they define the contract (legal params, optionals, response envelope) better than the function does.

## Step 3: find the payload encoding

Four shapes cover everything seen so far. Recipes: `references/payload-encoding.md`.

| first bytes / headers | encoding | confirm with |
|---|---|---|
| `application/octet-stream`, no magic, high entropy | repeating-key XOR, then gzip | un-XOR, expect `1f 8b` |
| JSON with a long base64url `enc` field | AES-256-CBC, key+IV lifted from the player JS | decrypt → `{"file":"https://…m3u8"}` |
| JSON with `w_payload` + `obfuscation_seed` + `cd_*`/`ad_*` keys | seed→SHA-256 field names→wasm→PBKDF2→AES-CBC | run their JS/wasm |
| HTML containing `eval(function(p,a,c,k,e,d)` | Dean-Edwards packer | unpack → grep `source=` |

## Step 4: find the auth/edge requirements

Bisect with a throwaway helper; `recon/matrix.sh` is the same shape applied to query params:

```bash
t(){ printf "%-46s " "$1"; shift; curl -s -o /dev/null -w "%{http_code}\n" --http2 "$@" "$URL"; }
t "+referer"       -H "user-agent: $UA" -H "referer: $BASE/"
t "+sec-fetch"     -H "user-agent: $UA" -H "referer: $BASE/" -H "sec-fetch-site: same-origin"
t "http1.1 + all"  --http1.1 -H "user-agent: $UA" -H "referer: $BASE/" -H "sec-fetch-site: same-origin"
```

For miruro: HTTP/1.1 → `403`; `--http2` + `Sec-Fetch-Site: same-origin` + a matching `Referer`
(`https://www.miruro.cx/`) → `200`, repeatedly, with no cookie, token or signed request. That pair
plus HTTP/2 *is* the gate, and OkHttp speaks HTTP/2 by default — this constrains library choice only.
Make that base URL a user setting: derive `Referer`/`Sec-Fetch-Site` from it per request so a mirror
just works, and bump a revision on change so screens reload (`EndpointStore`).

| symptom | gate | how to tell | portable? |
|---|---|---|---|
| 403 on HTTP/1.1, 200 on h2 + 2 headers | protocol/header gate | bisect | yes |
| 403 for every header set, 200 with `curl_cffi --impersonate chrome` | TLS fingerprint | impersonate | only with TLS shaping |
| `<title>Just a moment...</title>` on every permutation | JS challenge | `curl_cffi` still 403 | **no — not from OkHttp** |
| HTTP 200 with `{"status":500,"result":"Bad request"}` | app-level header gate (`X-Requested-With`) | add it | yes |
| `403` + 4561 B `Attention Required!` HTML, only on the **media** host | hotlink/WAF rule | add `Referer`/`Origin` | yes |

The last row is `recon/seg0.bin`: a Cloudflare block page where a JPEG should be.

## Step 5: resolve media

`/play` returns `provider → servers[] → streams[]`, each server carrying `headers` (a `Referer`).
Send that `Referer` and derive `Origin` from it — the segment CDN 403s without `Origin` even though
the playlist is fine. Segments are routinely named `.jpg`/`.png` and served as `image/jpeg`; HLS
parsers ignore the extension. Manifests and segment URIs may be protocol-relative (`//host/...`):
resolve against the playlist URL and re-read hosts from the playlist every time. Matrices and Media3
wiring: `references/hls-and-media.md`.

Also cache `/api/config` — `providerOrder` and per-provider `proxy`/`hls` flags are server-driven, not code.

## Step 6: prove it outside the browser (curl, then a JVM test)

```bash
curl -s -o /dev/null -w "%{http_code} %{content_type}\n" -H "referer: $REF" "$MASTER"
# segment, with and without the derived Origin — the difference IS the proof
curl -s -o /dev/null -w "with=%{http_code}\n"    -H "referer: $REF" -H "origin: $ORIGIN" "$SEG"
curl -s -o /dev/null -w "without=%{http_code}\n" -H "referer: $REF" "$SEG"
```

`recon/verify_e2e.sh` prints this eight-step chain end to end. Then port it to a live unit test: an
offline `PayloadTest` over a checked-in real response (`src/test/resources/play.bin`), live tests
asserting `#EXTM3U`, `#EXT-X-STREAM-INF`, `#EXTINF`, a segment returning `200`/`206` with bytes, and
a subtitle body containing `WEBVTT`. **Try several sources** — providers change between calls. Never
conclude from one request.

## Traps that cost real time

| trap | what actually happens | fix |
|---|---|---|
| Reusing the API client for media | its interceptor stamps the site `Referer` onto every CDN request, overwriting the provider's | a bare client for media (`ApiE2ETest` keeps `okhttp3.OkHttpClient()` separate) |
| `Referer` without `Origin` on segments | playlist 200, every segment 403 HTML | `Origin` = scheme+host of the payload `Referer`, derived, never hardcoded |
| Building `HlsMediaSource` directly | `MediaItem.subtitleConfigurations` dropped silently — subtitles "missing", no error | let `DefaultMediaSourceFactory` build it; it merges side-loaded subs |
| Trusting `.vtt`, or giving subtitles their own origin | one provider lists `.jpeg` paths returning 403 HTML; subs 403 while video plays | filter to real `.vtt`; reuse the **stream's** header map for subtitles |
| Empty `streams` treated as an error | episode looks unplayable, the next server would have worked | `continue` to next server, then next provider |
| Assuming provider order is stable | works today, fails tomorrow for the same episode | preference list + fallback on player error + source sheet |
| Assuming a season list means TV seasons | `order_release` is the whole franchise; movies/OVAs come back with 0 episodes | hydrate ids, skip empty episode lists, never index by ordinal |
| Parsing the body as gzip first | `not in gzip format` — the key was wrong, not the compression | un-XOR, **check `1f 8b`**, print 24 hex bytes |
| Sending `page=` to a cursor API | `400 Unsupported catalog request` (problem+json) | `limit` + opaque `cursor`; read `has_more`/`next_cursor` |
| Hardcoding the host or CDN host | TLD renames, CDN subdomains rotate | base URL is a setting; hosts come from payload/playlist |

## Deciding "can this be a native app at all?"

- Header/protocol gate → **yes**, ship it (miruro).
- Portable crypto (XOR, AES, SHA-256, PBKDF2, even a 357-byte wasm) → **yes, but keys are config**;
  expect silent breakage when the scheme rotates (anikoto, re:anime).
- IP-bound tokens → only on stable egress; a VPN or shared proxy hop breaks playback (re:anime).
- Cloudflare managed challenge / Turnstile on every path → **no**: not from OkHttp, not with
  browser-ish headers, not with TLS impersonation. Beating it needs a solver or a `cf_clearance`
  farm — the browser you were trying to avoid (animepahe).
- Only an embed iframe / third-party player API with no direct URL → **no**, unless that player's
  own chain is portable.

State the verdict with the evidence behind it: "VIABLE-WITH-PROXY" is a different product from "VIABLE".

## References

- `references/payload-encoding.md` — the four encodings, byte-level, with decoders.
- `references/hls-and-media.md` — HLS/CDN behaviour, header matrices, Media3 wiring.
- `references/case-studies.md` — miruro, anikoto, re:anime, animepahe: what worked, what didn't.
