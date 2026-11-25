package app.cloudipix.gallery.core.database

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

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
)

@Entity(tableName = "ignored_folders")
data class IgnoredFolderEntity(
    @PrimaryKey @ColumnInfo(name = "bucket_id") val bucketId: Long,
    val name: String,
)

@Entity(tableName = "pinned_folders")
data class PinnedFolderEntity(@PrimaryKey @ColumnInfo(name = "bucket_id") val bucketId: Long)

@Entity(tableName = "folder_covers")
data class FolderCoverEntity(
    @PrimaryKey @ColumnInfo(name = "bucket_id") val bucketId: Long,
    @ColumnInfo(name = "photo_id") val photoId: Long? = null,
    // 기존 데이터베이스와의 호환을 위해 이전 블러 설정 열을 유지한다.
    @ColumnInfo(name = "blur_automatic") val blurAutomatic: Boolean = false,
)

@Entity(tableName = "favorites", indices = [Index("added_order")])
data class FavoriteEntity(
    @PrimaryKey val path: String,
    @ColumnInfo(name = "added_order", defaultValue = "0") val addedOrder: Long,
)

data class FavoriteMediaRow(
    @Embedded val medium: MediumEntity,
    @ColumnInfo(name = "added_order") val addedOrder: Long,
)

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

data class FolderNameRow(
    @ColumnInfo(name = "bucket_id") val bucketId: Long,
    @ColumnInfo(name = "bucket_name") val bucketName: String,
    @ColumnInfo(name = "relative_path") val relativePath: String,
    @ColumnInfo(name = "item_count") val itemCount: Int,
    @ColumnInfo(name = "media_id") val mediaId: Long,
)
