package com.norfold.app

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.SvgDecoder
import coil.decode.VideoFrameDecoder
import com.norfold.app.data.DiagnosticsStore

class NorfoldApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        DiagnosticsStore.install(this)
    }

    /**
     * One application-wide loader keeps chat media behavior deterministic. Coil's optional GIF,
     * SVG, and video artifacts do not install their decoders merely by being dependencies, so a
     * GIF otherwise falls back to its first frame and looks like a still image.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                add(ImageDecoderDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
            add(SvgDecoder.Factory())
            add(VideoFrameDecoder.Factory())
        }
        .respectCacheHeaders(false)
        .build()
}
