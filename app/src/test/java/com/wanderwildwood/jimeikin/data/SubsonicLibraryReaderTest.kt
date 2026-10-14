package com.wanderwildwood.jimeikin.data

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reader against made-up servers: one that answers everything, one whose empty search
 * comes back short, one with no tag library at all, and one that refuses the login.
 */
class SubsonicLibraryReaderTest {

    private fun song(id: String, album: String?, albumId: String?, parent: String? = null) = JSONObject()
        .put("id", id).put("title", "Song $id").put("artist", "The Lanterns")
        .apply { album?.let { put("album", it) }; albumId?.let { put("albumId", it) }; parent?.let { put("parent", it) } }
        .put("suffix", "mp3")

    private fun album(id: String, name: String, songCount: Int) =
        JSONObject().put("id", id).put("name", name).put("artist", "The Lanterns").put("songCount", songCount)

    private fun ok(build: JSONObject.() -> Unit) = SubsonicResult.Success(JSONObject().put("status", "ok").apply(build))

    /** A server that keeps a tag library of two albums, three songs. */
    private open inner class TagServer {
        val calls = mutableListOf<String>()
        val songs = listOf(song("1", "First", "a1"), song("2", "First", "a1"), song("3", "Second", "a2"))

        open fun search3(params: Map<String, String>): SubsonicResult<JSONObject> {
            val offset = params["songOffset"]!!.toInt()
            val page = songs.drop(offset).take(params["songCount"]!!.toInt())
            return ok { put("searchResult3", JSONObject().put("song", JSONArray(page))) }
        }

        suspend fun call(method: String, params: Map<String, String>): SubsonicResult<JSONObject> {
            calls += method
            return when (method) {
                "getAlbumList2" -> ok {
                    put("albumList2", JSONObject().put("album", JSONArray(listOf(album("a1", "First", 2), album("a2", "Second", 1)))))
                }
                "search3" -> search3(params)
                "getAlbum" -> ok {
                    val id = params["id"]
                    put("album", JSONObject().put("song", JSONArray(songs.filter { it.getString("albumId") == id })))
                }
                else -> SubsonicResult.Failure("no such method", code = 0)
            }
        }
    }

    @Test
    fun aWholeLibraryComesInAFewPagesWhenTheServerAnswersAnEmptySearch() = runBlocking {
        val server = TagServer()
        val library = (SubsonicLibraryReader(server::call).read() as SubsonicResult.Success).value
        assertEquals(3, library.songs.size)
        assertEquals(2, library.albums.size)
        assertFalse("no album was read one by one", "getAlbum" in server.calls)
    }

    @Test
    fun aShortEmptySearchFallsBackToReadingEachAlbum() = runBlocking {
        val server = object : TagServer() {
            // Answers an empty query with nothing, as some servers do.
            override fun search3(params: Map<String, String>) =
                ok { put("searchResult3", JSONObject()) }
        }
        val library = (SubsonicLibraryReader(server::call).read() as SubsonicResult.Success).value
        assertEquals(3, library.songs.size)
        assertEquals(2, server.calls.count { it == "getAlbum" })
    }

    @Test
    fun aServerThatIgnoresTheOffsetDoesNotLoopForever() = runBlocking {
        val server = object : TagServer() {
            // Always the first page, whatever the offset; and the albums claim more songs
            // than exist, so the reader never sees "enough" and must notice the repetition.
            override fun search3(params: Map<String, String>) =
                ok { put("searchResult3", JSONObject().put("song", JSONArray(List(500) { song("x$it", "First", "a1") }))) }
        }
        val result = SubsonicLibraryReader(server::call).read()
        assertTrue(result is SubsonicResult.Success)
        assertTrue(server.calls.count { it == "search3" } <= 2)
    }

    @Test
    fun aServerWithoutATagLibraryIsWalkedByFolder() = runBlocking {
        val calls = mutableListOf<String>()
        val reader = SubsonicLibraryReader { method, params ->
            calls += method
            when (method) {
                "getAlbumList2" -> SubsonicResult.Failure("not implemented", code = 0)
                "getIndexes" -> ok {
                    put("indexes", JSONObject().put("index", JSONArray(listOf(
                        JSONObject().put("name", "L").put("artist", JSONArray(listOf(JSONObject().put("id", "d1").put("name", "The Lanterns")))),
                    ))))
                }
                "getMusicDirectory" -> when (params["id"]) {
                    "d1" -> ok {
                        put("directory", JSONObject().put("id", "d1").put("name", "The Lanterns").put("child", JSONArray(listOf(
                            JSONObject().put("id", "d2").put("isDir", true).put("title", "Lamplight (1999)"),
                        ))))
                    }
                    "d2" -> ok {
                        put("directory", JSONObject().put("id", "d2").put("name", "Lamplight (1999)").put("child", JSONArray(listOf(
                            // One untagged, one tagged: the folder names the first.
                            song("s1", null, null, parent = "d2"),
                            song("s2", "Lamplight", null, parent = "d2"),
                            JSONObject().put("id", "v1").put("isVideo", true).put("title", "A video"),
                        ))))
                    }
                    else -> SubsonicResult.Failure("missing", code = 70)
                }
                else -> SubsonicResult.Failure("no such method", code = 0)
            }
        }
        val library = (reader.read() as SubsonicResult.Success).value
        assertEquals(listOf("s1", "s2"), library.songs.map { it.id })
        assertEquals(1, library.albums.size)
        assertEquals("d2", library.albums.single().id)
        assertEquals("Lamplight", library.albums.single().name)
        assertTrue(library.songs.all { it.albumId == "d2" })
    }

    @Test
    fun anAlbumListOfNothingButUnknownsIsReadByFolderInstead() = runBlocking {
        // Gonic with untagged files: an album list, but every album "Unknown Album".
        val reader = SubsonicLibraryReader { method, params ->
            when (method) {
                "getAlbumList2" -> ok {
                    put("albumList2", JSONObject().put("album", JSONArray(listOf(
                        JSONObject().put("id", "al-3").put("name", "Unknown Album").put("artist", "Unknown Artist").put("songCount", 1),
                    ))))
                }
                "getIndexes" -> ok {
                    put("indexes", JSONObject().put("index", JSONArray(listOf(
                        JSONObject().put("name", "F").put("artist", JSONArray(listOf(JSONObject().put("id", "al-2").put("name", "Folder Artist 1")))),
                    ))))
                }
                "getMusicDirectory" -> when (params["id"]) {
                    "al-2" -> ok {
                        put("directory", JSONObject().put("id", "al-2").put("name", "Folder Artist 1").put("child", JSONArray(listOf(
                            JSONObject().put("id", "al-3").put("isDir", true).put("title", "Folder Album 1"),
                        ))))
                    }
                    else -> ok {
                        put("directory", JSONObject().put("id", "al-3").put("name", "Folder Album 1").put("child", JSONArray(listOf(
                            JSONObject().put("id", "tr-1").put("title", "track 1.mp3").put("album", "Folder Album 1")
                                .put("albumId", "al-3").put("parent", "al-3").put("artist", "Unknown Artist").put("suffix", "mp3"),
                        ))))
                    }
                }
                else -> SubsonicResult.Failure("no such method", code = 0)
            }
        }
        val library = (reader.read() as SubsonicResult.Success).value
        assertEquals("Folder Album 1", library.albums.single().name)
        assertEquals("Folder Artist 1", library.albums.single().artist)
        assertEquals("Folder Artist 1", library.songs.single().artist)
    }

    @Test
    fun aRefusedLoginIsReportedRatherThanWalkedAround() = runBlocking {
        val calls = mutableListOf<String>()
        val result = SubsonicLibraryReader { method, _ ->
            calls += method
            SubsonicResult.Failure("refused", code = 40)
        }.read()
        assertEquals(40, (result as SubsonicResult.Failure).code)
        assertEquals(listOf("getAlbumList2"), calls)
    }

    @Test
    fun anEmptyServerIsAnEmptyLibraryNotAFailure() = runBlocking {
        val result = SubsonicLibraryReader { method, _ ->
            when (method) {
                "getAlbumList2" -> ok { put("albumList2", JSONObject()) }
                "getIndexes" -> ok { put("indexes", JSONObject()) }
                else -> SubsonicResult.Failure("unexpected", code = 0)
            }
        }.read()
        assertEquals(0, (result as SubsonicResult.Success).value.songs.size)
    }

    @Test
    fun theCeilingReplacesRawAndLeavesEverythingElse() {
        val raw = "http://h:4533/rest/stream?u=a&t=b&s=c&v=1.16.1&c=jimeikin&f=json&id=7&format=raw"
        assertEquals(raw, withBitrateCeiling(raw, null))
        assertEquals(
            "http://h:4533/rest/stream?u=a&t=b&s=c&v=1.16.1&c=jimeikin&f=json&id=7&maxBitRate=128",
            withBitrateCeiling(raw, 128),
        )
        val mp3 = "http://h:4533/rest/stream?id=7&format=mp3"
        assertEquals("http://h:4533/rest/stream?id=7&format=mp3&maxBitRate=96", withBitrateCeiling(mp3, 96))
        // A kept file is never touched.
        assertEquals("content://x/y.mp3", withBitrateCeiling("content://x/y.mp3", 96))
    }

    @Test
    fun versionsCompareByNumberNotByText() {
        assertTrue(isOlder("1.8.0", "1.13.0"))
        assertFalse(isOlder("1.16.1", "1.13.0"))
        assertFalse(isOlder("1.13.0", "1.13.0"))
        assertTrue(isOlder("1.12", "1.13.0"))
    }
}
