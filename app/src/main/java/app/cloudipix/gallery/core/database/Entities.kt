package app.cloudipix.gallery.core.database

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * 갤러리 데이터베이스(gallery.db) 테이블입니다.
 *
 * 주의: 이 파일의 엔티티는 저장된 스키마와 한 글자도 어긋나면 안 됩니다. Room은 테이블·열 이름,
 * 열 순서, 형식, null 허용 여부, 기본값, 인덱스로 스키마 해시를 만들고, 기기에 저장된 해시와 다르면
 * 데이터베이스를 열지 못합니다. 열을 바꾸려면 버전을 올리고 마이그레이션을 추가해야 합니다.
 * 이름이 따로 없는 속성은 속성 이름이 곧 열 이름입니다.
 */

/**
 * MediaStore에서 읽은 기기 미디어 색인입니다. [type]은 MediaType 순번입니다.
 * [generation]은 MediaStore의 수정 세대(`GENERATION_MODIFIED`)이며 0이면 아직 읽지 않은 것입니다.
 */
@Entity(tableName = "media", indices = [Index("bucket_id"), Index("date_taken")])
data class MediumEntity(
    @PrimaryKey val id: Long,
    val name: String,
    @ColumnInfo(name = "bucket_id") val bucketId: Long,
    @ColumnInfo(name = "bucket_name") val bucketName: String,
    @ColumnInfo(name = "relative_path") val relativePath: String,
    @ColumnInfo(name = "date_taken") val dateTaken: Long,
    @ColumnInfo(name = "date_modified") val dateModified: Long,
    val size: Long,
    val type: Int,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(defaultValue = "0") val generation: Long = 0,
)

/** 갤러리에서 숨긴 폴더입니다. 폴더가 사라져도 목록에 보여 줄 수 있도록 이름을 함께 둡니다. */
@Entity(tableName = "ignored_folders")
data class IgnoredFolderEntity(
    @PrimaryKey @ColumnInfo(name = "bucket_id") val bucketId: Long,
    val name: String,
)

/** 폴더 목록 맨 위에 고정한 폴더입니다. */
@Entity(tableName = "pinned_folders")
data class PinnedFolderEntity(@PrimaryKey @ColumnInfo(name = "bucket_id") val bucketId: Long)

/** 사용자가 고른 폴더 표지 사진입니다. */
@Entity(tableName = "folder_covers")
data class FolderCoverEntity(
    @PrimaryKey @ColumnInfo(name = "bucket_id") val bucketId: Long,
    @ColumnInfo(name = "photo_id") val photoId: Long? = null,
    /** 지금은 쓰지 않는 예전 블러 설정입니다. 기존 데이터베이스와 스키마를 맞추려고 남겨 둡니다. */
    @ColumnInfo(name = "blur_automatic") val blurAutomatic: Boolean = false,
)

/**
 * 즐겨찾기입니다. 미디어 ID는 다시 색인하면 바뀔 수 있어 `relative_path + name` 경로로 기억합니다.
 * [addedOrder]는 즐겨찾기에 추가한 순서입니다.
 */
@Entity(tableName = "favorites", indices = [Index("added_order")])
data class FavoriteEntity(
    @PrimaryKey val path: String,
    @ColumnInfo(name = "added_order", defaultValue = "0") val addedOrder: Long,
)

// ---- 조회 결과 행 ----
// 아래 클래스는 테이블이 아니라 쿼리 결과를 받는 형태라 스키마에 들어가지 않습니다. 열은 이름으로 연결됩니다.

/** 즐겨찾기 미디어와 추가 순서입니다. */
data class FavoriteMediaRow(
    @Embedded val medium: MediumEntity,
    @ColumnInfo(name = "added_order") val addedOrder: Long,
)

/** 폴더 목록 한 줄입니다. 대표 표지와 사용자가 고른 표지(있으면)를 함께 담습니다. */
data class FolderRow(
    @ColumnInfo(name = "bucket_id") val bucketId: Long,
    @ColumnInfo(name = "bucket_name") val bucketName: String,
    @ColumnInfo(name = "item_count") val itemCount: Int,
    val size: Long,
    @ColumnInfo(name = "date_taken") val dateTaken: Long,
    @ColumnInfo(name = "date_modified") val dateModified: Long,
    @ColumnInfo(name = "cover_id") val coverId: Long,
    @ColumnInfo(name = "cover_type") val coverType: Int,
    @ColumnInfo(name = "preferred_photo_id") val preferredPhotoId: Long?,
    @ColumnInfo(name = "preferred_photo_type") val preferredPhotoType: Int?,
)

/** 폴더 선택 목록 한 줄입니다. [mediaId]는 볼륨을 찾을 때 쓰는 대표 항목입니다. */
data class FolderNameRow(
    @ColumnInfo(name = "bucket_id") val bucketId: Long,
    @ColumnInfo(name = "bucket_name") val bucketName: String,
    @ColumnInfo(name = "relative_path") val relativePath: String,
    @ColumnInfo(name = "item_count") val itemCount: Int,
    @ColumnInfo(name = "media_id") val mediaId: Long,
)
