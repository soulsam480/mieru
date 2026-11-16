package cx.miruro.app.data

/**
 * Checks run on a downloaded update before the system installer is opened.
 *
 * A truncated download, an HTML error page saved under `.apk`, or the wrong release asset all
 * "download fine" and then confuse the installer — the user taps Install and the app silently
 * stays on the old build. Catching it here turns that into a readable error.
 */
internal fun validateApkBytes(bytes: ByteArray, expectedSize: Long): String? {
    if (expectedSize > 0 && bytes.size.toLong() != expectedSize) {
        return "Download is ${bytes.size} bytes, expected $expectedSize"
    }
    if (bytes.size < ZIP_MAGIC.size) {
        return "Download is too small to be an APK"
    }
    val looksLikeZip =
        bytes[0] == ZIP_MAGIC[0] &&
            bytes[1] == ZIP_MAGIC[1] &&
            bytes[2] == ZIP_MAGIC[2] &&
            bytes[3] == ZIP_MAGIC[3]
    if (!looksLikeZip) {
        return "Download is not an APK (no ZIP header) — the server may have returned an error page"
    }
    return null
}

/** Locates the End Of Central Directory record, i.e. the last four bytes before an APK comment. */
private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
