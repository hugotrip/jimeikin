package com.wanderwildwood.jimeikin

import androidx.media3.common.PlaybackException
import com.wanderwildwood.jimeikin.playback.PlaybackTrouble
import com.wanderwildwood.jimeikin.playback.Trouble
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackTroubleTest {

    private fun error(
        code: Int,
        status: Int? = null,
        server: Boolean = true,
        online: Boolean = true,
        signedIn: Boolean = true,
    ) = PlaybackTrouble.fromError(code, status, server, online, signedIn)

    @Test
    fun aServerSongWithNoLoginIsSaidBeforeItIsTried() {
        assertEquals(Trouble.SERVER_SIGNED_OUT, PlaybackTrouble.beforePlaying(streamsFromServer = true, serverSignedIn = false))
        assertNull(PlaybackTrouble.beforePlaying(streamsFromServer = true, serverSignedIn = true))
    }

    @Test
    fun aSongThatIsNotStreamedFromTheServerNeedsNoLogin() {
        // A phone file, a YouTube song, a server song kept on the phone.
        assertNull(PlaybackTrouble.beforePlaying(streamsFromServer = false, serverSignedIn = false))
    }

    @Test
    fun noLoginWinsOverWhateverTheErrorWas() {
        assertEquals(
            Trouble.SERVER_SIGNED_OUT,
            error(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, signedIn = false),
        )
    }

    @Test
    fun aConnectionThatFailsIsNoInternetOfflineAndTheServerOnline() {
        val code = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        assertEquals(Trouble.NO_INTERNET, error(code, online = false))
        assertEquals(Trouble.SERVER_UNREACHABLE, error(code, online = true))
        assertEquals(Trouble.CANT_PLAY, error(code, server = false, online = true))
    }

    @Test
    fun aRefusalIsTheLoginAndAServerErrorIsTheServer() {
        val code = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        assertEquals(Trouble.SERVER_REFUSED, error(code, status = 401))
        assertEquals(Trouble.SERVER_UNREACHABLE, error(code, status = 503))
    }

    @Test
    fun aMissingFileThatIsNotTheServersIsMissing() {
        val code = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        assertEquals(Trouble.FILE_MISSING, error(code, status = 404, server = false))
        assertEquals(Trouble.CANT_PLAY, error(code, status = 500, server = false))
    }

    @Test
    fun aServerAnswerThatIsNotMusicIsARefusal() {
        val code = PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
        assertEquals(Trouble.SERVER_REFUSED, error(code))
        assertEquals(Trouble.CANT_PLAY, error(code, server = false))
    }

    @Test
    fun filesMissingOrUnreadableSaySo() {
        assertEquals(Trouble.FILE_MISSING, error(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, server = false))
        assertEquals(Trouble.NO_FILE_ACCESS, error(PlaybackException.ERROR_CODE_IO_NO_PERMISSION, server = false))
    }

    @Test
    fun anythingElseSaysSoRatherThanNothing() {
        assertEquals(Trouble.CANT_PLAY, error(PlaybackException.ERROR_CODE_UNSPECIFIED, server = false))
        assertEquals(Trouble.NO_INTERNET, error(PlaybackException.ERROR_CODE_UNSPECIFIED, online = false))
    }

    @Test
    fun aStallIsNamedByWhatIsMissing() {
        assertEquals(Trouble.SERVER_SIGNED_OUT, PlaybackTrouble.stalled(online = true, serverSignedIn = false))
        assertEquals(Trouble.NO_INTERNET, PlaybackTrouble.stalled(online = false, serverSignedIn = true))
        assertEquals(Trouble.SERVER_UNREACHABLE, PlaybackTrouble.stalled(online = true, serverSignedIn = true))
    }
}
