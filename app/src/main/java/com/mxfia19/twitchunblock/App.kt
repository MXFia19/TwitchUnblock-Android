package com.mxfia19.twitchunblock

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.mxfia19.twitchunblock.core.AppState
import com.mxfia19.twitchunblock.core.Http
import com.mxfia19.twitchunblock.core.I18n
import com.mxfia19.twitchunblock.core.Logger
import com.mxfia19.twitchunblock.core.NetworkStatus

class App : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        instance = this
        I18n.load(this)
        AppState.init(this)
        NetworkStatus.start(this)
        Logger.info("APP", "Lancement", "${BuildConfig.VERSION_NAME} (${BuildConfig.CHANNEL}) · Android ${Build.VERSION.RELEASE}")
    }

    /** Images (avatars, miniatures, emotes) : le même client réseau que le
     *  reste, et les emotes animées (GIF, WebP animé dès Android 9). */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(Http.client)
            .components {
                if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
            }
            .crossfade(true)
            .build()

    companion object {
        lateinit var instance: App
            private set
    }
}
