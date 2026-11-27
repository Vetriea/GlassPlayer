package com.example.glassplayer

import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.concurrent.TimeUnit

class YtDownloader : Downloader() {
    private val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()

    override fun execute(request: Request): Response {
        val method = request.httpMethod()
        val data = request.dataToSend()
        val body = when {
            data != null -> data.toRequestBody()
            method == "POST" -> ByteArray(0).toRequestBody()
            else -> null
        }
        val b = okhttp3.Request.Builder().url(request.url()).method(method, body)
        b.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0")
        request.headers().forEach { (k, vs) ->
            vs.forEachIndexed { i, v -> if (i == 0) b.header(k, v) else b.addHeader(k, v) }
        }
        return client.newCall(b.build()).execute().use { r ->
            if (r.code == 429) throw ReCaptchaException("Rate limited", request.url())
            Response(r.code, r.message, r.headers.toMultimap(), r.body?.string(), r.request.url.toString())
        }
    }
}

object YT {
    private var ready = false
    private fun init() {
        if (!ready) { NewPipe.init(YtDownloader()); ready = true }
    }

    fun search(q: String): List<Song> {
        init()
        val yt = ServiceList.YouTube
        val handler = yt.searchQHFactory.fromQuery(q, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), "")
        val info = SearchInfo.getInfo(yt, handler)
        return info.relatedItems.filterIsInstance<StreamInfoItem>().map {
            Song(
                id = it.url.hashCode().toLong(),
                title = it.name,
                artist = it.uploaderName ?: "YouTube Music",
                uri = Uri.EMPTY,
                thumb = it.thumbnails.lastOrNull()?.url,
                webUrl = it.url
            )
        }
    }

    fun streamUrl(pageUrl: String): String {
        init()
        val info = StreamInfo.getInfo(pageUrl)
        val best = info.audioStreams.filter { it.isUrl }.maxByOrNull { it.averageBitrate }
            ?: error("No audio stream found")
        return best.content
    }
}
