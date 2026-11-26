package app.cloudipix.gallery

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.cloudipix.gallery.core.media.Thumbnail
import app.cloudipix.gallery.core.media.ThumbnailFetcher
import app.cloudipix.gallery.core.media.ThumbnailKeyer
import app.cloudipix.gallery.core.media.ThumbnailStorage
import app.cloudipix.gallery.core.remote.RemoteImages
import app.cloudipix.gallery.core.sync.FolderSync
import app.cloudipix.gallery.core.update.AutomaticUpdateCheck
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class GalleryApp : Application(), SingletonImageLoader.Factory {

    @Inject lateinit var remoteImages: RemoteImages
    @Inject lateinit var thumbnails: ThumbnailStorage
    @Inject lateinit var folderSync: FolderSync

    private val foregroundWork = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            folderSync.onAppOpened()
            AutomaticUpdateCheck.checkIfDue(this@GalleryApp)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(foregroundWork)
    }

    /** 썸네일 요청은 앱 전용 불러오기로, GIF와 동영상 첫 프레임은 Coil 디코더로 처리합니다. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(ThumbnailFetcher.Factory(context, thumbnails, remoteImages), Thumbnail::class)
            add(ThumbnailKeyer(), Thumbnail::class)
            add(AnimatedImageDecoder.Factory())
            add(VideoFrameDecoder.Factory())
        }
        .crossfade(true)
        .build()
}
