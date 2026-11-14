package com.yogesh.gallery

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageDecoderDecoder
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Request for a small preview. It is served by Android's own thumbnail cache (ContentResolver.loadThumbnail),
 * which is far faster than decoding the full photo/video for every grid cell while scrolling.
 */
class MediaThumb(val uri: Uri, val isVideo: Boolean, val px: Int)

class ThumbFetcher(private val ctx: Context, private val d: MediaThumb) : Fetcher {
    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val bmp = try {
            ctx.contentResolver.loadThumbnail(d.uri, android.util.Size(d.px, d.px), null)
        } catch (e: Exception) {
            if (d.isVideo) throw e else decodeBitmap(ctx, d.uri, d.px)
        }
        DrawableResult(BitmapDrawable(ctx.resources, bmp), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory(private val ctx: Context) : Fetcher.Factory<MediaThumb> {
        override fun create(data: MediaThumb, options: Options, imageLoader: ImageLoader): Fetcher =
            ThumbFetcher(ctx, data)
    }
}

const val GRID_THUMB_PX = 384

fun thumbKey(m: Media, px: Int = GRID_THUMB_PX) = "t:${m.id}:${m.size}:${m.mod}:$px"

/** Grid / list thumbnail request (memory-cached under [thumbKey]). Fast: uses the system thumbnail cache. */
fun thumbRequest(ctx: Context, m: Media, px: Int = GRID_THUMB_PX): ImageRequest =
    ImageRequest.Builder(ctx)
        .data(MediaThumb(m.uri, m.isVideo, px))
        .size(px)
        .memoryCacheKey(thumbKey(m, px))
        .diskCachePolicy(CachePolicy.DISABLED)
        .crossfade(false)
        .build()

/** Full-screen request. Shows the already cached grid thumbnail instantly while the big image decodes. */
fun fullRequest(ctx: Context, m: Media, stamp: Long = 0L, maxPx: Int = 2048): ImageRequest =
    ImageRequest.Builder(ctx)
        .data(m.uri)
        .size(maxPx)
        .decoderFactory(ImageDecoderDecoder.Factory())
        .memoryCacheKey("f:${m.id}:${m.size}:${m.mod}:$stamp:$maxPx")
        .apply { if (stamp == 0L) placeholderMemoryCacheKey(thumbKey(m)) }
        .diskCachePolicy(CachePolicy.DISABLED)
        .crossfade(false)
        .build()
