package com.cinemora.app.download

import android.content.Context
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.cinemora.app.core.EpisodeItem
import com.cinemora.app.core.StreamSource
import com.cinemora.app.data.Movie

/** Enqueue / list / remove real episode downloads. */
@UnstableApi
object DownloadHelper {

    fun downloadId(movie: Movie, episode: EpisodeItem): String =
        "${movie.providerId}_${movie.contentId.hashCode()}_${episode.number}"

    /** Enqueue a download of the best source for one episode. */
    fun enqueue(context: Context, movie: Movie, episode: EpisodeItem, source: StreamSource) {
        val data = listOf(
            "${movie.title} — Episode ${episode.number}",
            movie.providerId,
            movie.contentId,
            episode.number.toString(),
        ).joinToString("\u001F")
        val request = DownloadRequest.Builder(downloadId(movie, episode), android.net.Uri.parse(source.url))
            .setCustomCacheKey(source.url)
            .setData(data.toByteArray(Charsets.UTF_8))
            .setMimeType(if (source.isM3u8) MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP4)
            .build()
        DownloadService.sendAddDownload(
            context,
            CinemoraDownloadService::class.java,
            request,
            false,
        )
    }

    fun remove(context: Context, downloadId: String) {
        DownloadService.sendRemoveDownload(
            context,
            CinemoraDownloadService::class.java,
            downloadId,
            false,
        )
    }

    data class DownloadItem(
        val id: String,
        val title: String,
        val providerId: String,
        val contentId: String,
        val episodeNumber: Int,
        val state: Int,
        val progress: Float,
    )

    /** Current downloads from the offline index. */
    fun list(context: Context): List<DownloadItem> {
        val manager = try {
            DownloadService.getDownloadManager(context, CinemoraDownloadService::class.java)
        } catch (_: Exception) {
            return emptyList()
        }
        val out = mutableListOf<DownloadItem>()
        val cursor = manager.downloadIndex.getDownloads()
        while (cursor.moveToNext()) {
            val d: Download = cursor.download
            val total = d.contentLength
            val progress = if (total > 0) (d.bytesDownloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
            val parts = d.request.data.decodeToString().split("\u001F")
            out.add(
                DownloadItem(
                    id = d.request.id,
                    title = parts.getOrNull(0) ?: d.request.id,
                    providerId = parts.getOrNull(1) ?: "",
                    contentId = parts.getOrNull(2) ?: "",
                    episodeNumber = parts.getOrNull(3)?.toIntOrNull() ?: 1,
                    state = d.state,
                    progress = progress,
                ),
            )
        }
        cursor.close()
        return out
    }

    fun stateOf(context: Context, downloadId: String): Int? {
        val manager = try {
            DownloadService.getDownloadManager(context, CinemoraDownloadService::class.java)
        } catch (_: Exception) {
            return null
        }
        return try {
            manager.downloadIndex.getDownload(downloadId)?.state
        } catch (_: Exception) {
            null
        }
    }
}
