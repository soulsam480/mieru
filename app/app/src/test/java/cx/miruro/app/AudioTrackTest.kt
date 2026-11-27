package cx.miruro.app

import cx.miruro.app.ui.player.audioLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioTrackTest {

    @Test
    fun audio_label_uses_the_language_name() {
        assertEquals("Korean", audioLabel("kor", null))
        assertEquals("English", audioLabel("eng", "English audio"))
    }

    @Test
    fun audio_label_falls_back_to_the_track_label() {
        assertEquals("Commentary", audioLabel(null, "Commentary"))
        assertEquals("Track", audioLabel(null, null))
    }

    @Test
    fun audio_label_handles_regional_tags() {
        assertEquals("Portuguese (Brazil)", audioLabel("pt-BR", null))
    }
}
