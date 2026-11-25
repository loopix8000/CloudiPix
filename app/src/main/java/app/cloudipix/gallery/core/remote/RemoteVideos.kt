package app.cloudipix.gallery.core.remote

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/** 서버의 동영상을 내려받지 않고 필요한 구간만 읽어 재생하거나 썸네일 프레임을 뽑는다. */
@Singleton
class RemoteVideos @Inject constructor(
    private val gallery: RemoteGallery,
    private val transport: RemoteTransport,
) {
    @OptIn(UnstableApi::class)
    fun dataSourceFactory(): DataSource.Factory = DataSource.Factory { RemoteVideoDataSource(gallery, transport) }

    /** 동영상 앞부분에서 대표 프레임을 [maxSize] 안에 맞춰 꺼낸다. */
    internal fun frame(server: RemoteServer, photo: RemotePhoto, maxSize: Int): Bitmap {
        val retriever = MediaMetadataRetriever()
        val source = RemoteFrameSource(transport, server, photo.path, photo.size)
        try {
            retriever.setDataSource(source)
            return retriever.getScaledFrameAtTime(FRAME_TIME_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxSize, maxSize)
                ?: retriever.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxSize, maxSize)
                ?: throw IOException("동영상 프레임을 읽을 수 없습니다.")
        } catch (error: RuntimeException) {
            throw IOException("동영상 프레임을 읽을 수 없습니다.", error)
        } finally {
            retriever.release()
            source.close()
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface RemoteVideoEntryPoint {
    fun remoteVideos(): RemoteVideos
}

@OptIn(UnstableApi::class)
private class RemoteVideoDataSource(
    private val gallery: RemoteGallery,
    private val transport: RemoteTransport,
) : BaseDataSource(/* isNetwork = */ true) {
    private var uri: Uri? = null
    private var input: InputStream? = null
    private var remaining = UNSET
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val (server, photo) = gallery.source(dataSpec.uri) ?: throw IOException("동영상이 변경되었거나 연결이 해제되었습니다.")
        if (photo.size > 0 && dataSpec.position > photo.size) throw EOFException()
        remaining = when {
            dataSpec.length != UNSET -> dataSpec.length
            photo.size > 0 -> photo.size - dataSpec.position
            else -> UNSET
        }
        // 파일 끝을 여는 요청은 서버가 범위 오류로 거부하므로 연결하지 않는다.
        if (remaining != 0L) input = transport.openRead(server, photo.path, dataSpec.position)
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val wanted = if (remaining == UNSET) length else minOf(remaining, length.toLong()).toInt()
        val count = requireNotNull(input).read(buffer, offset, wanted)
        if (count < 0) {
            // 크기를 알고 있는데 일찍 끝나면 전송이 끊긴 것이다.
            if (remaining != UNSET) throw EOFException()
            return C.RESULT_END_OF_INPUT
        }
        if (remaining != UNSET) remaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            input?.close()
        } finally {
            input = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}

/** 프레임 추출기가 요청하는 위치를 서버에서 읽는다. 가까운 앞쪽 이동은 연결을 다시 열지 않고 건너뛴다. */
private class RemoteFrameSource(
    private val transport: RemoteTransport,
    private val server: RemoteServer,
    private val path: String,
    private val length: Long,
) : MediaDataSource() {
    private var input: InputStream? = null
    private var position = 0L
    private var transferred = 0L

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        if (length > 0 && position >= length) return -1
        val stream = seek(position)
        val count = stream.read(buffer, offset, size)
        if (count < 0) return -1
        this.position += count
        track(count.toLong())
        return count
    }

    private fun seek(target: Long): InputStream {
        val current = input
        val gap = target - position
        if (current != null && gap in 0..NEARBY_BYTES) {
            var skipped = 0L
            while (skipped < gap) {
                val count = current.skip(gap - skipped)
                if (count <= 0) {
                    if (current.read() < 0) break
                    skipped++
                } else skipped += count
            }
            track(skipped)
            position += skipped
            if (position == target) return current
        }
        current?.close()
        input = null
        return transport.openRead(server, path, target).also {
            input = it
            position = target
        }
    }

    private fun track(bytes: Long) {
        transferred += bytes
        // 색인 없이 끝까지 훑어야 하는 파일은 썸네일을 포기하고 전체를 받지 않는다.
        if (transferred > MAX_FRAME_BYTES) throw IOException("동영상 프레임을 찾기 위해 읽을 양이 너무 많습니다.")
    }

    override fun getSize(): Long = if (length > 0) length else -1

    override fun close() {
        input?.close()
        input = null
    }
}

private val UNSET = C.LENGTH_UNSET.toLong()
private const val FRAME_TIME_US = 1_000_000L
private const val NEARBY_BYTES = 512L * 1024
private const val MAX_FRAME_BYTES = 64L * 1024 * 1024
