package com.wanderwildwood.jimeikin.playback

import androidx.media3.common.PlaybackException

/**
 * Why a song is not playing, worded for the person holding the phone.
 *
 * Idea and first version by heavencanthandle (https://github.com/heavencanthandle/Music-box).
 *
 * Before this a failure that was not YouTube's own said nothing: a server song whose login had
 * gone (the password is sealed by a key that a reinstall deletes) showed as playing, at 0:00,
 * for ever. Each kind here has one plain message and, where there is one, a way to put it right.
 *
 * "A server song" below means one that still streams from the server. A server song that has
 * been kept on the phone is a file, plays without the server, and is never one of these.
 */
enum class Trouble {
    /** A server song, and no server login to play it with. */
    SERVER_SIGNED_OUT,

    /** The server would not answer: down, wrong address, or the phone cannot reach it. */
    SERVER_UNREACHABLE,

    /** The server answered, and refused: the login is wrong, or it sent something not music. */
    SERVER_REFUSED,

    /** Nothing is reaching the internet at all. */
    NO_INTERNET,

    /** The song's file is not where the library says it is. */
    FILE_MISSING,

    /** The file is there, and Music Box is not allowed to read the phone's music. */
    NO_FILE_ACCESS,

    /** A server song asked to play, and still waiting for the first sound after [STALL_MS]. */
    STILL_LOADING,

    /** Anything else. Says so, rather than nothing. */
    CANT_PLAY,
}

object PlaybackTrouble {

    /** How long a server song may sit loading before it is said to be stuck. */
    const val STALL_MS = 20_000L

    /** A server song with no login to play it with: said before it is tried, not after. */
    fun beforePlaying(streamsFromServer: Boolean, serverSignedIn: Boolean): Trouble? =
        if (streamsFromServer && !serverSignedIn) Trouble.SERVER_SIGNED_OUT else null

    /** A server song that has sat loading too long: no login, no internet, or the server not answering. */
    fun stalled(online: Boolean, serverSignedIn: Boolean): Trouble = when {
        !serverSignedIn -> Trouble.SERVER_SIGNED_OUT
        !online -> Trouble.NO_INTERNET
        else -> Trouble.SERVER_UNREACHABLE
    }

    /**
     * A player error, read from its [errorCode] (a [PlaybackException] code) and, for a refused
     * request, the server's [httpStatus].
     */
    fun fromError(
        errorCode: Int,
        httpStatus: Int?,
        streamsFromServer: Boolean,
        online: Boolean,
        serverSignedIn: Boolean,
    ): Trouble {
        if (streamsFromServer && !serverSignedIn) return Trouble.SERVER_SIGNED_OUT
        return when (errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            -> when {
                !online -> Trouble.NO_INTERNET
                streamsFromServer -> Trouble.SERVER_UNREACHABLE
                else -> Trouble.CANT_PLAY
            }

            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> when {
                httpStatus == 404 && !streamsFromServer -> Trouble.FILE_MISSING
                httpStatus != null && httpStatus >= 500 && streamsFromServer -> Trouble.SERVER_UNREACHABLE
                streamsFromServer -> Trouble.SERVER_REFUSED
                else -> Trouble.CANT_PLAY
            }

            // A server that refuses a login often says so in a page of XML, which the player
            // then cannot read as music.
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            -> if (streamsFromServer) Trouble.SERVER_REFUSED else Trouble.CANT_PLAY

            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> Trouble.FILE_MISSING
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> Trouble.NO_FILE_ACCESS
            else -> if (!online && streamsFromServer) Trouble.NO_INTERNET else Trouble.CANT_PLAY
        }
    }
}
