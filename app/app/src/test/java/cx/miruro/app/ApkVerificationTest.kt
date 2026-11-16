package cx.miruro.app

import cx.miruro.app.data.validateApkBytes
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Guards that turn a bad download into a readable error instead of a silent no-op install. */
class ApkVerificationTest {

    private val zipHeader = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)
    private val htmlPage = "<!DOCTYPE html><html><body>rate limited</body></html>".toByteArray()

    @Test
    fun accepts_a_zip_with_the_expected_size() {
        assertNull(validateApkBytes(zipHeader, zipHeader.size.toLong()))
    }

    @Test
    fun rejects_an_html_error_page() {
        assertNotNull(validateApkBytes(htmlPage, htmlPage.size.toLong()))
    }

    @Test
    fun rejects_a_truncated_download() {
        assertNotNull(validateApkBytes(zipHeader, zipHeader.size + 100L))
    }

    @Test
    fun rejects_a_file_too_small_to_be_an_apk() {
        assertNotNull(validateApkBytes(byteArrayOf(0x50, 0x4B), 2))
    }

    @Test
    fun an_unknown_expected_size_still_checks_the_header() {
        assertNull(validateApkBytes(zipHeader, 0))
        assertNotNull(validateApkBytes(htmlPage, 0))
    }
}
