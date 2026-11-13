# Obfuscated-payload catalogue

Every scheme below was recovered from a shipped client bundle and then re-implemented outside the
browser. The generic rule: **the decoder is always in the client, is always short, and is never
the hard part** — the hard part is noticing that the endpoint is encoded at all.

## 0. Triage: what are these first bytes?

```bash
curl -sS -o body.bin "https://host/path"; xxd -l 24 body.bin; file body.bin
```

| first bytes | meaning | next move |
|---|---|---|
| `1f 8b` | plain gzip, no obfuscation | gunzip, done |
| `7b 22` / `5b` (`{"`, `[`) | plain JSON | done |
| `3c 21 44 4f` (`<!DO`) | HTML where data should be — WAF, error page, or CF challenge | read `<title>` |
| base64/base64url alphabet only, decodes to non-text | encoded text (fixed-key cipher, or XOR'd document) | find the key in the bundle |
| high entropy, no magic, `application/octet-stream` | XOR/stream cipher over a compressed or text body | un-XOR, then re-check for `1f 8b` |
| JSON, but with odd field names (`enc`, `w_payload`, `cd_ab12cd34`) | crypto recipe + per-request salt | read the player chunk |

`content-type` is part of the contract. Miruro's client only runs the binary decoder when the
media type is exactly `application/octet-stream` (`Button-BTdICcI9.js:1 @11223`); if it is
`…json` it does `JSON.parse(text)`, otherwise it returns the raw text. Reproduce that dispatch or
you will decode error bodies and call it corruption.

---

## 1. Miruro — repeating-key XOR, then gzip

**Key:** the literal ASCII string `miruro/catalog`, 14 bytes, repeating from offset 0. No IV, no
salt, no per-request variation, no base64. gzip is RFC 1952.

```js
var Le = new TextEncoder().encode(`miruro/catalog`);
async function Re(e){ let t=e.slice();
  for (let i=0;i<t.length;i++) t[i] ^= Le[i % Le.length];
  let n = new Blob([t]).stream().pipeThrough(new DecompressionStream(`gzip`));
  return await new Response(n).text() }
```
`assets/Button-BTdICcI9.js:1 @10640` — [C]

```
bytes = response.body
for i in 0..len: bytes[i] ^= KEY[i % 14]
json = gunzip(bytes)
```

```python
KEY = b"miruro/catalog"
x = bytes(b ^ KEY[i % len(KEY)] for i, b in enumerate(raw))
assert x[:2] == b"\x1f\x8b", x[:24].hex()      # <- the check that saves an hour
json.loads(gzip.decompress(x))
```

```kotlin
private val KEY = "miruro/catalog".toByteArray(Charsets.UTF_8)
fun decodePayload(raw: ByteArray): String {
    val unxored = ByteArray(raw.size) { i -> (raw[i].toInt() xor KEY[i % KEY.size].toInt()).toByte() }
    return GZIPInputStream(ByteArrayInputStream(unxored)).use { it.readBytes().toString(Charsets.UTF_8) }
}
```

Facts worth keeping (all [C] from the bundle, checked by `rg` over the whole 396 762-byte chunk):

- no brotli/zstd/lz4/msgpack/protobuf/wasm anywhere in the client;
- every `/api/*` response with that media type uses the same key;
- the same decoder decodes the SSR bootstrap: `window.__SSR_DATA__` is
  `base64 → bytes → XOR → gunzip → JSON` (`index.js:2 @95468`) — a free offline test vector;
- the "other" key you will find in `env2.js` (`VITE_PROXY_OBF_KEY =
  a54d389c18527d9fd3e7f0643e27edbe`) has **nothing to do with API bodies**. It obfuscates HLS-proxy
  URL paths, with the *same* construction and a different key:

```
obf(s) = base64url_nopad( utf8(s) XOR hexToBytes(key) )      # key = 16 bytes, repeating
```

That is the entire proxy scheme — no signature, no timestamp, no header. See
`hls-and-media.md` §4 for the URL grammar.

**Trap:** if you gunzip before un-XORing (or with the wrong key) the error is `not in gzip format`.
That message tells you nothing about which of the two steps is wrong. Always assert the magic
bytes *after* the XOR and print 24 hex bytes on failure — the captured notes does exactly this and
returns `<not gzip: …>` instead of raising.

---

## 2. Anikoto — AES-256-CBC with a key lifted from the player bundle

The API is JSON all the way down; only the *last* hop is encrypted. `GET
https://megaplay.buzz/stream/getSources?id=<fileId>` (and `getSourcesNew`) returns:

```json
{"tracks":[…], "intro":{…}, "outro":{…}, "server":4,
 "enc":"wdeBruh3qqn_i5wUNnyaPcXq…_nwHO7j0C6etflIbPfDbgdIfa_qigKkn3Gp-CTdxFAxgI"}
```

`enc` is base64url. The recipe is a five-line function in `https://megaplay.buzz/lib/e1-player.min.js`
(also `newclient.min.js`):

```js
const SOURCE_ENC_KEY = "i?LMTAx0Q6,:}50U", SOURCE_ENC_IV = "W0;27ToaUpl_P%'c";
padKeyBytes(k, 32)  // utf8(k) into the front of a 32-byte zero array
b64UrlDecode(s)     // - → +, _ → /, pad '=' to len%4, atob
crypto.subtle.decrypt({name:"AES-CBC", iv: IV16}, importKey("raw", KEY32, "AES-CBC"), ct)
```

So: **AES-256-CBC, PKCS#7, key = UTF-8(`i?LMTAx0Q6,:}50U`) zero-padded to 32 bytes, IV =
UTF-8(`W0;27ToaUpl_P%'c`) exactly 16 bytes.**

```python
key = (b"i?LMTAx0Q6,:}50U" + b"\0" * 32)[:32]
iv  = b"W0;27ToaUpl_P%'c"
ct  = base64.b64decode(enc.replace('-', '+').replace('_', '/') + '=' * (-len(enc) % 4))
out = AES.new(key, AES.MODE_CBC, iv).decrypt(ct); out = out[:-out[-1]]
# b'{"file":"https://fetch.nexabloom.top/anime/<32hex>/<32hex>/master.m3u8"}'
```

```kotlin
val key = "i?LMTAx0Q6,:}50U".toByteArray().copyOf(32)      // zero-padded
val iv  = "W0;27ToaUpl_P%'c".toByteArray()                 // 16 bytes
val ct  = Base64.decode(enc.replace('-', '+').replace('_', '/'), Base64.DEFAULT)
val c = Cipher.getInstance("AES/CBC/PKCS5Padding")         // PKCS5 == PKCS7 on the JVM
c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
val file = JSONObject(String(c.doFinal(ct))).getString("file")
```

~15 lines, no nonce, no HMAC. But the key/IV **live in the player JS**: treat them as configuration
to re-derive, not constants to trust. This provider already changed scheme once.

---

## 3. Re:Anime / flixcloud — per-request seed, sha256-derived field names, wasm, PBKDF2, IP-bound JWT

The most involved of the four, and the one to avoid if you have a choice. `GET
https://flixcloud.cc/e/{access_id}?v=N` is a ~25 KB SvelteKit page whose embedded data blob
contains `obfuscation_seed`, `token`, `w_payload` (a **357-byte** base64 wasm exporting
`memory, _s, _r, _c`), `obfuscated_crypto_data` (`{cd_xxx:{ad_xxx:[{od_xxx:{kf_xxx,ivf_xxx}}]}}`)
and a third key fragment under a hashed key. The algorithm, from
`https://flixcloud.cc/res/immutable/nodes/12.ug9x_MkD.js`:

```
token       = data[ e[48:64] + "_" + e[56:64] ]
frag2       = data[ a[0:16] + "_" + a[16:24] ]
e           = sha256hex(seed + "0") → sha256hex(e + "1") → sha256hex(e + "2")
a           = same 3-step chain applied to e
field names = cd_/ad_/od_/kf_/ivf_ + e[24:32], e[32:40], e[40:48], e[8:16], e[16:24]

GET https://flixcloud.cc/api/m3u8/{token}        # one-shot
  → { [sha256(token+"vid")[0:10]]: b64 ciphertext,
      [sha256(token+"key")[0:10]]: b64 frag3,
      metadata:{version:"2.1", encoding:"aes256cbc"} }

K     = wasm._r(frag1, frag2, frag3)             # seeded by _s(seed[0:8] as u32)
N     = PBKDF2-HMAC-SHA256(K, salt=seed, iter=1000, dkLen=32); N[i] ^= seed[i % 16]
key   = SHA256(N)
plain = AES-256-CBC(key, iv).decrypt(ciphertext) → the master.m3u8 URL
```

Then the playlist body is itself `base64(plaintext XOR keystream)`, keystream = a **constant**
32-byte key returned by the wasm export `_c()` — the same on every page load and every seed:

```
_c() = mZDEeR4KzHWJdTonCbp1eOtfTD310Ym2ADl5sFloe1M=
```

Consequences that decide feasibility:

- **Field names change every load** because they are derived from a per-request seed. Every scrape
  must re-derive them; nothing can be hardcoded.
- `/api/m3u8/{token}` is single-use: the second call returns `410 {"error":"invalid_or_used_token"}`.
  One embed fetch per playback attempt.
- The returned media URL carries an **HS256 JWT** with claims
  `{video_id, client_ip, exp, iat, iss:"video-hosting-platform"}`, 6 h TTL. The JWT is reusable;
  only the bootstrap token is one-shot. **The IP binding means a VPN or shared proxy hop breaks
  playback**, and re-resolution must happen from the same egress IP.
- `metadata.version:"2.1"` is a version field on the *obfuscation*: assume it will move.

A working port exists as the captured notes — it instantiates the real wasm rather
than reimplementing `_s/_r/_c`, and prints a decrypted 1080p master URL every run.

---

## 4. Animepahe / kwik — Dean-Edwards packed JS

The play page and the file host are HTML with two `eval(function(p,a,c,k,e,d){…})` blobs. The
second one unpacks to a plain string assignment:

```js
const source = 'https://vault-NN.owocdn.top/stream/NN/XX/<64hex>/uwu.m3u8';  // host: vault-NN.{owo,uwu}cdn.top
… new Hls(config); hls.loadSource(source)
```

No second POST, no token exchange: the m3u8 is **inside** the packed blob. Base62 alphabet,
`0-9a-zA-Z`, replace in reverse index order with word boundaries:

```python
DIG = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
def e(n): return DIG[n] if n < 62 else e(n // 62) + DIG[n % 62]
def unpack(p, a, c, k):                      # k = blob.split('|')
    for i in range(c - 1, -1, -1):
        if k[i]:
            p = p.replace(r'\b' + e(i) + r'\b', k[i])   # regex, not str.replace
    return p

# grab: eval\(function\(p,a,c,k,e,d\).*?\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\),0,\{\}\)
```

Four real decodes (2025-10 → 2026-04) all produced the same shape:

| captured | unpacked source |
|---|---|
| 2025-10-21 `kwik.cx/e/0LqHMaBz7Gx6` | `https://vault-99.owocdn.top/stream/99/01/00cc600a…9bca/uwu.m3u8` |
| 2026-02-15 `kwik.cx/e/0kq8anpKU7Ql` | `https://vault-13.owocdn.top/stream/13/08/79a9c497…3980c/uwu.m3u8` |
| 2026-03-25 `kwik.cx/e/0YD6vxg5DIcZ` | `https://vault-04.uwucdn.top/stream/04/07/7d365103…e4bd/uwu.m3u8` |
| 2026-04-01 `kwik.cx/e/0Cy7eKNGTZr3` | `https://vault-03.uwucdn.top/stream/03/06/abce0fdb…ca1a5/uwu.m3u8` |

The unpacker is trivial. It did not matter: every page that *serves* those blobs is behind a
Cloudflare managed challenge (see `case-studies.md`), so the packing was never the obstacle.

---

## 5. Checklist for a new encoding

1. `xxd -l 24` — compressed, text, or none of the above.
2. Search the bundle for `DecompressionStream`, `crypto.subtle`, `atob`, `eval(function`,
   `TextEncoder` — in that order; it covers all four schemes above.
3. Copy the snippet into a scratch script and run it unchanged against one recorded response.
4. Only then port it. Assert an invariant at every step (gzip magic, JSON starts with `{`, decrypted
   text contains `m3u8` or `"file"`).
5. Record where the key came from, with file and offset. A key without provenance cannot be
   re-derived after rotation.
