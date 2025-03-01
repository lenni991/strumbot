/*
 * Copyright 2019-present Florian Spieß and the Strumbot Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package strumbot

import dev.minn.jda.ktx.SLF4J
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import net.dv8tion.jda.api.utils.data.DataArray
import net.dv8tion.jda.api.utils.data.DataObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.slf4j.Logger
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.lang.Integer.min
import java.time.Instant
import java.time.ZonedDateTime

suspend fun createTwitchApi(http: OkHttpClient, clientId: String, clientSecret: String, scope: CoroutineScope): TwitchApi {
    val api = TwitchApi(http, clientId, clientSecret, "N/A", scope)
    api.authorize()
    return api
}

class TwitchApi(
    private val http: OkHttpClient,
    private val clientId: String,
    private val clientSecret: String,
    private var accessToken: String,
    private val scope: CoroutineScope
) {

    private val log: Logger by SLF4J
    private val warnedMissingVod = mutableSetOf<String>()
    private val games = FixedSizeMap<String, Game>(10)

    internal suspend fun authorize() {
        val request = post("https://id.twitch.tv/oauth2/token") {
            add("client_id", clientId)
            add("client_secret", clientSecret)
            add("grant_type", "client_credentials")
        }

        val call = http.newCall(request)
        call.await(scope) { response ->
            when {
                response.isSuccessful -> {
                    val json = DataObject.fromJson(response.body!!.byteStream())
                    accessToken = json.getString("access_token")
                }
                response.code < 500 -> throw NotAuthorized(response)
                else -> throw response.asException()
            }
        }
    }

    private suspend fun <T> makeRequest(request: Request, failed: Boolean = false, handler: (Response) -> T?): T? {
        log.trace("Making request to {}", request.url)
        val call = http.newCall(request)
        return call.await(scope) { response ->
            log.trace("Got response {} for url {}", response.code, request.url)
            when {
                failed && !response.isSuccessful -> { // Prevent infinite loop on broken API
                    throw response.asException()
                }
                response.code == 401 -> { // oauth token expires after a few months of uptime
                    log.warn("Authorization expired, refreshing token...")
                    authorize()
                    // Update authorization header to new token
                    retryRequest(request)?.let(handler)
                }
                response.code == 404 -> {
                    log.warn("Received 404 response for request to ${request.url}")
                    null
                }
                response.code == 429 -> { // I have never seen this actually happen
                    log.warn("Hit rate limit, retrying request. Headers:\n{}", response.headers)
                    val reset = response.header("ratelimit-reset")?.let {
                        it.toLong() - System.currentTimeMillis()
                    } ?: 1000

                    delay(reset)
                    retryRequest(request)?.let(handler)
                }
                response.isSuccessful -> handler(response)
                else -> throw response.asException()
            }
        }
    }

    private suspend fun retryRequest(request: Request): Response? {
        return makeRequest(request.newBuilder().authorization().build(), true) { it }
    }

    private fun get(url: String, vararg params: Pair<String, String>): Request {
        val query = if (params.isEmpty()) ""
                    else params.joinToString("&", "?") { "${it.first}=${it.second}" }
        return Request.Builder()
            .url("https://api.twitch.tv/helix/$url$query")
            .header("Client-ID", clientId)
            .authorization()
            .build()
    }

    private fun Request.Builder.authorization() = header("Authorization", "Bearer $accessToken")

    fun getStreamByLogin(login: Collection<String>) = scope.async {
        val request = get("streams",
            *login.map { "user_login" to it }.toTypedArray()
        )

        makeRequest(request) { response ->
            val data = body(response)
            if (data.isEmpty) {
                emptyList()
            } else {
                List(data.length()) { i ->
                    val stream = data.getObject(i)
                    Stream(
                        stream.getString("id"),
                        stream.getString("game_id"),
                        stream.getString("title"),
                        stream.getString("type"),
                        stream.getString("language", "en"),
                        stream.getString("thumbnail_url"),
                        stream.getString("user_id"),
                        stream.getString("user_name"),
                        ZonedDateTime.parse(stream.getString("started_at")).toEpochSecond()
                    )
                }
            }
        }
    }

    fun getGame(stream: Stream): Deferred<Game?> = scope.async {
        if (stream.gameId.isEmpty()) {
            EMPTY_GAME
        } else if (stream.gameId in games) {
            games[stream.gameId]
        } else {
            val request = get("games", "id" to stream.gameId)
            makeRequest(request) { response ->
                val data = body(response)
                if (data.isEmpty) {
                    EMPTY_GAME
                } else {
                    val game = data.getObject(0)
                    games.computeIfAbsent(stream.gameId) {
                        Game(
                            game.getString("id"),
                            game.getString("name")
                        )
                    }
                }
            }
        }
    }

    fun getUserIdByLogin(login: String) = scope.async {
        val request = get("users", "login" to login)
        makeRequest(request) { response ->
            val data = body(response)
            if (data.isEmpty)
                null
            else
                data.getObject(0).getString("id")
        }
    }

    fun getVideoById(id: String, type: String? = "archive") = scope.async {
        val request = get("videos?id=$id" + if (type != null) "&type=$type" else "")
        makeRequest(request) { response ->
            handleVideo(response)
        }
    }

    fun getVideoByStream(stream: Stream) = scope.async {
        val userId = stream.userId
        val request = get("videos",
            "type" to  "archive", // archive = vod
            "first" to "5", // check 5 most recent videos, just in case it might ignore my type (default 20)
            "user_id" to userId
        )

        makeRequest(request) { response ->
            val data = body(response)

            repeat(data.length()) { i ->
                val video = data.getObject(i)
                val type = video.getString("type")
                val createdAt = ZonedDateTime.parse(video.getString("created_at")).toEpochSecond()
                // Stream vods are always type archive (other types are highlight and upload)
                if (type == "archive" && stream.startedAt <= createdAt) {
                    return@makeRequest buildVideo(video)
                }
            }
            if (warnedMissingVod.add(stream.userLogin))
                log.warn("Could not find vod for current stream by ${stream.userLogin}. Did you enable archives?")
            return@makeRequest null
        }
    }

    fun getTopClips(userId: String, startedAt: Long, num: Int = 5) = scope.async {
        // this endpoint has horrible api design
        // "closed" https://discuss.dev.twitch.tv/t/new-twitch-api-getclips-missing-some-clips-but-not-all/23888/6
        // twitch filters *after* limiting the number. we need to just get max and then filter

        val request = get("clips",
            "broadcaster_id" to userId,
            "first" to "100",
            "started_at" to "${Instant.ofEpochSecond(startedAt)}"
        )

        makeRequest(request) { response ->
            val data = body(response)
            if (data.length() == 0)
                emptyList()
            else {
                List(min(num, data.length())) {
                    buildVideo(data.getObject(it))
                }
            }
        }
    }

    fun getThumbnail(stream: Stream, width: Int = 1920, height: Int = 1080) = getThumbnail(stream.thumbnail, width, height)
    fun getThumbnail(video: Video, width: Int = 1920, height: Int = 1080) = getThumbnail(video.thumbnail, width, height)
    fun getThumbnail(url: String, width: Int, height: Int) = scope.async<InputStream?> {
        // Stream url uses {width} and video url uses %{width} ??????????????? OK TWITCH ???????????
        val thumbnailUrl = url.replace(Regex("%?\\{width}"), width.toString())
                              .replace(Regex("%?\\{height}"), height.toString()) + "?v=${System.currentTimeMillis()}" // add random number to avoid cache!
        val request = Request.Builder()
            .url(thumbnailUrl)
            .build()

        try {
            makeRequest(request) { response ->
                val buffer = ByteArrayOutputStream()
                response.body!!.byteStream().copyTo(buffer)
                ByteArrayInputStream(buffer.toByteArray())
            }
        } catch (ex: Exception) {
            log.error("Failed to download thumbnail with url '{}'", url, ex)
            null
        }
    }

    private fun handleVideo(response: Response): Video? {
        val data = body(response)
        return if (data.isEmpty)
            null
        else {
            val video = data.getObject(0)
            buildVideo(video)
        }
    }

    private fun body(response: Response): DataArray {
        val json = DataObject.fromJson(response.body!!.byteStream())
        return json.getArray("data")
    }

    private fun buildVideo(video: DataObject): Video {
        val id = video.getString("id")
        val url = video.getString("url")
        val title = video.getString("title")
        val thumbnail = video.getString("thumbnail_url")
        val views = video.getInt("view_count", 0)
        return Video(id, url, title, thumbnail, views)
    }
}

data class Stream(
    val streamId: String,
    val gameId: String,
    val title: String,
    val type: String,
    val language: String,
    val thumbnail: String,
    val userId: String,
    val userLogin: String,
    val startedAt: Long)
data class Video(
    val id: String,
    val url: String,
    val title: String,
    val thumbnail: String,
    val views: Int)
data class Game(val gameId: String, val name: String)

val EMPTY_GAME = Game("", "No Category")