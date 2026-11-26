package app.cloudipix.gallery.core.model

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.Immutable
import app.cloudipix.gallery.core.remote.isRemoteId

/** 미디어 종류입니다. 순번(ordinal)이 데이터베이스에 저장되므로 순서를 바꾸지 않습니다. */
enum class MediaType { Image, Video, Gif }

/** 갤러리에 보이는 사진·동영상 하나입니다. 기기 항목과 원격 항목을 함께 나타냅니다. */
data class Medium(
    val id: Long,
    override val name: String,
    val bucketId: Long,
    override val dateTaken: Long,
    override val dateModified: Long,
    override val size: Long,
    val type: MediaType,
    val durationMs: Long,
    val isFavorite: Boolean,
    /** 원격 항목의 주소입니다. 기기 항목이면 `null`입니다. */
    val remoteUri: Uri? = null,
    /** 원격 항목을 기기에 받아 둔 사본입니다. 있으면 화면에는 사본을 보여 줍니다. */
    val localCopyUri: Uri? = null,
    override val favoriteOrder: Long? = null,
    val localCopyModified: Long? = null,
    val staleLocalCopyModified: Long? = null,
    /** 기기 항목의 MediaStore 수정 세대입니다. 모르면 0입니다. */
    val generation: Long = 0,
) : Sortable {
    override val typeRank: Int get() = type.ordinal
    val isRemote: Boolean get() = remoteUri != null

    /** 공유·편집 등에 쓰는 원래 주소입니다. */
    val uri: Uri get() = remoteUri ?: mediaUri(id, type)

    /** 화면에 그릴 때 쓰는 주소입니다. */
    val displayUri: Uri get() = localCopyUri?.takeUnless { staleLocalCopyModified != null && staleLocalCopyModified == localCopyModified } ?: uri
}

/** 폴더 목록의 폴더 하나입니다. 최근 항목·즐겨찾기 같은 가상 폴더도 같은 형태로 나타냅니다. */
@Immutable
data class Folder(
    val bucketId: Long,
    override val name: String,
    val itemCount: Int,
    override val size: Long,
    override val dateTaken: Long,
    override val dateModified: Long,
    val coverId: Long,
    /** 표지 항목의 [MediaType] 순번입니다. */
    val coverType: Int,
    val isPinned: Boolean = false,
    val remoteCover: Uri? = null,
    val manualCover: Uri? = null,
    val isLocked: Boolean = false,
    val automaticCover: Uri? = null,
    val isHidden: Boolean = false,
    /** 서버 폴더와 합친 폴더는 미디어를 서버 ID로 찾고, 이 기기 ID로 저장한 배치를 그대로 씁니다. */
    val localBucketId: Long? = null,
) : Sortable {
    override val typeRank: Int get() = coverType
    val isRecent: Boolean get() = bucketId == RECENT_BUCKET_ID
    val isFavorites: Boolean get() = bucketId == FAVORITES_BUCKET_ID
    val isVirtual: Boolean get() = isVirtualFolder(bucketId)
    val isRemote: Boolean get() = !isVirtual && (remoteCover != null || isRemoteId(bucketId))

    /** 표지 주소입니다. 직접 고른 표지, 자동 표지, 원격 표지, 표지 항목 순으로 씁니다. */
    val coverUri: Uri
        get() = manualCover ?: automaticCover ?: remoteCover
            ?: mediaUri(coverId, MediaType.entries.getOrElse(coverType) { MediaType.Image })
}

/** 모든 폴더의 항목을 모아 보여 주는 가상 폴더 ID입니다. */
const val RECENT_BUCKET_ID = Long.MIN_VALUE

/** 즐겨찾기 가상 폴더 ID입니다. */
const val FAVORITES_BUCKET_ID = Long.MAX_VALUE

fun isVirtualFolder(bucketId: Long): Boolean = bucketId == RECENT_BUCKET_ID || bucketId == FAVORITES_BUCKET_ID

/**
 * 폴더들을 합친 '최근 항목' 가상 폴더입니다. 표지와 촬영일은 가장 최근에 찍은 항목이 든 폴더를 따르고,
 * 개수·크기는 합계, 수정일은 가장 늦은 값입니다. 폴더가 없으면 `null`입니다.
 */
fun List<Folder>.recent(): Folder? {
    val newest = maxByOrNull { it.dateTaken } ?: return null
    return newest.copy(
        bucketId = RECENT_BUCKET_ID,
        name = "",
        itemCount = sumOf { it.itemCount },
        size = sumOf { it.size },
        dateModified = maxOf { it.dateModified },
        isPinned = false,
        manualCover = null,
        localBucketId = null,
    )
}

/** 폴더 선택·숨김 설정 화면에 쓰는 폴더 정보입니다. */
data class FolderEntry(
    val bucketId: Long,
    val name: String,
    val path: String,
    val itemCount: Int,
    val volumeName: String? = null,
    val syncLocalPath: LocalStoragePath? = null,
    val localBucketId: Long? = null,
)

/** 기기 항목의 MediaStore 주소입니다. 동영상은 동영상 컬렉션, 나머지는 사진 컬렉션을 씁니다. */
fun mediaUri(id: Long, type: MediaType): Uri {
    val collection = when (type) {
        MediaType.Video -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        MediaType.Image, MediaType.Gif -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }
    return ContentUris.withAppendedId(collection, id)
}
