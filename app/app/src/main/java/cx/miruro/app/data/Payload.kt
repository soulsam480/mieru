package cx.miruro.app.data

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/**
 * Miruro serves every API response as `application/octet-stream` where the bytes are:
 *
 *     gzip( XOR(body, "miruro/catalog") )
 *
 * Recovered from the web client (`assets/Button-BTdICcI9.js`):
 *
 *     var Le = new TextEncoder().encode(`miruro/catalog`);
 *     for (let i = 0; i < t.length; i++) t[i] ^= Le[i % Le.length];
 *     ... pipeThrough(new DecompressionStream(`gzip`))
 */
private val KEY = "miruro/catalog".toByteArray(Charsets.UTF_8)

fun decodePayload(raw: ByteArray): String {
    val unxored = ByteArray(raw.size)
    for (i in raw.indices) {
        unxored[i] = (raw[i].toInt() xor KEY[i % KEY.size].toInt()).toByte()
    }
    return GZIPInputStream(ByteArrayInputStream(unxored)).use {
        it.readBytes().toString(Charsets.UTF_8)
    }
}
