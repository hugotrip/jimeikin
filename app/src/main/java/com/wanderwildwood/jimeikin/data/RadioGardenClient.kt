package com.wanderwildwood.jimeikin.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** One town, and how many stations it has. */
data class RadioPlace(
    val id: String,
    val title: String,
    val country: String,
    val stationCount: Int,
)

/** One station, as radio.garden lists it. */
data class RadioChannel(
    val id: String,
    val title: String,
    val place: String,
    val country: String,
)

/**
 * The station list behind radio.garden.
 *
 * radio.garden is a globe you drag to hear what is on the air somewhere else. The globe is the
 * part that cannot survive a 4.3" screen with sixteen greys; the part worth keeping is *place*,
 * and place is three lists - country, town, station.
 *
 * Their api is not documented and not promised to anyone, so nothing here is allowed to become
 * load-bearing: a station that is kept stores the stream url it resolved to, and plays from
 * that afterwards without asking radio.garden anything. If this whole file stops working one
 * day, the stations already kept go on playing.
 */
object RadioGarden {

    private const val BASE = "https://radio.garden/api/ara/content"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)          // the stream url is the redirect; see [resolveStream]
        .build()

    // The whole place index is one 1.8 MB document listing about twelve and a half thousand
    // towns. It is fetched once per run of the app and kept in memory rather than fetched per
    // screen, because paging through countries would otherwise re-download it every time.
    @Volatile
    private var places: List<RadioPlace>? = null

    private fun get(url: String): String? = try {
        val request = Request.Builder()
            .url(url)
            // Their cdn answers 403 to a bare client.
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 12) MusicBox")
            .build()
        http.newBuilder().followRedirects(true).build().newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    } catch (e: Exception) {
        android.util.Log.w("RadioGarden", "fetch failed: $url", e)
        null
    }

    suspend fun places(): List<RadioPlace> = withContext(Dispatchers.IO) {
        places?.let { return@withContext it }
        val body = get("$BASE/places") ?: return@withContext emptyList()
        val parsed = try {
            val list = JSONObject(body).getJSONObject("data").getJSONArray("list")
            (0 until list.length()).mapNotNull { i ->
                val item = list.optJSONObject(i) ?: return@mapNotNull null
                val url = item.optString("url")
                val id = url.substringAfterLast('/').takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                RadioPlace(
                    id = id,
                    title = item.optString("title").ifBlank { return@mapNotNull null },
                    country = item.optString("country").ifBlank { "Elsewhere" },
                    stationCount = item.optInt("size", 0),
                )
            }
        } catch (e: Exception) {
            android.util.Log.w("RadioGarden", "could not read the place list", e)
            emptyList()
        }
        places = parsed
        parsed
    }

    suspend fun countries(): List<Pair<String, Int>> = withContext(Dispatchers.IO) {
        places()
            .groupBy { it.country }
            .map { (country, towns) -> country to towns.sumOf { it.stationCount } }
            .sortedBy { it.first.lowercase() }
    }

    suspend fun placesIn(country: String): List<RadioPlace> = withContext(Dispatchers.IO) {
        places()
            .filter { it.country == country && it.stationCount > 0 }
            .sortedBy { it.title.lowercase() }
    }

    suspend fun channelsIn(place: RadioPlace): List<RadioChannel> = withContext(Dispatchers.IO) {
        val body = get("$BASE/page/${place.id}/channels") ?: return@withContext emptyList()
        try {
            val items = JSONObject(body)
                .getJSONObject("data")
                .getJSONArray("content")
                .getJSONObject(0)
                .getJSONArray("items")
            (0 until items.length()).mapNotNull { i ->
                val page = items.optJSONObject(i)?.optJSONObject("page") ?: return@mapNotNull null
                val id = page.optString("url").substringAfterLast('/').takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                RadioChannel(
                    id = id,
                    title = page.optString("title").ifBlank { return@mapNotNull null },
                    place = page.optJSONObject("place")?.optString("title") ?: place.title,
                    country = page.optJSONObject("country")?.optString("title") ?: place.country,
                )
            }.sortedBy { it.title.lowercase() }
        } catch (e: Exception) {
            android.util.Log.w("RadioGarden", "could not read ${place.title}'s stations", e)
            emptyList()
        }
    }

    /**
     * The actual stream behind a station.
     *
     * `channel.mp3` does not carry audio: it answers 302 and names the station's own server in
     * the Location header. Following it here rather than at play time is what lets a kept
     * station be stored as an ordinary url, so it survives this api.
     */
    suspend fun resolveStream(channelId: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE/listen/$channelId/channel.mp3")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 12) MusicBox")
                .build()
            http.newCall(request).execute().use { response ->
                response.header("Location")?.takeIf { it.isNotBlank() }
                    ?: if (response.isSuccessful) response.request.url.toString() else null
            }
        } catch (e: Exception) {
            android.util.Log.w("RadioGarden", "could not resolve $channelId", e)
            null
        }
    }
}
