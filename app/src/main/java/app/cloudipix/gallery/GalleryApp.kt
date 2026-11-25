package app.cloudipix.gallery

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import dagger.hilt.android.HiltAndroidApp
import app.cloudipix.gallery.core.media.Thumbnail
import app.cloudipix.gallery.core.media.ThumbnailFetcher
import app.cloudipix.gallery.core.media.ThumbnailKeyer
import app.cloudipix.gallery.core.media.ThumbnailStorage
import app.cloudipix.gallery.core.remote.RemoteImages
import app.cloudipix.gallery.core.remote.RemoteGallery
import app.cloudipix.gallery.core.sync.FolderSync
import javax.inject.Inject

@HiltAndroidApp
class GalleryApp : Application(), SingletonImageLoader.Factory {

    @Inject lateinit var remoteImages: RemoteImages
    @Inject lateinit var thumbnails: ThumbnailStorage
    @Inject lateinit var folderSync: FolderSync
    @Inject lateinit var remoteGallery: RemoteGallery

    override fun onCreate() {
        super.onCreate()
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                remoteGallery.refreshInBackground()
                folderSync.onAppOpened()
            }
        })
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(ThumbnailFetcher.Factory(context, thumbnails, remoteImages), Thumbnail::class)
                add(ThumbnailKeyer(), Thumbnail::class)
                add(AnimatedImageDecoder.Factory())
                add(VideoFrameDecoder.Factory())
            }
            .crossfade(true)
            .build()
}
