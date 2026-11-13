package cx.miruro.app.data

/** Shared HTTP constants. */
object Http {
    /** Any modern browser UA is fine; the edge only checks the Sec-Fetch-Site + Referer pair. */
    const val UA =
        "Mozilla/5.0 (Linux; Android 15; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/156.0.0.0 Mobile Safari/537.36"
}
