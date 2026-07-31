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
import com.norfold.app.ui.fonts.OfflineFontLibrary
import com.norfold.app.data.FontImportStore

class NorfoldApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        DiagnosticsStore.install(this)
        // Reads the packaged font manifest (a single small asset, no font binaries) so the first paragraph
        // rendered already has the bundled catalogue available and does not fall back to a generic.
        OfflineFontLibrary.initialize(this)
        FontImportStore(this).records().forEach(OfflineFontLibrary::install)
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
