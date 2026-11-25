package app.cloudipix.gallery.core.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {

    @Query(
        """
        SELECT a.*, cover.id AS cover_id, cover.type AS cover_type,
               p.id AS preferred_photo_id, p.type AS preferred_photo_type
        FROM (
            SELECT bucket_id, MIN(bucket_name) AS bucket_name, COUNT(*) AS item_count, SUM(size) AS size,
                   MAX(date_taken) AS date_taken, MAX(date_modified) AS date_modified
            FROM media WHERE type IN (:types)
                AND (:includeHidden OR bucket_id NOT IN (SELECT bucket_id FROM ignored_folders))
            GROUP BY bucket_id
        ) a
        JOIN media cover ON cover.id = (SELECT id FROM media WHERE bucket_id = a.bucket_id AND type IN (:types)
                                       ORDER BY date_taken DESC, id DESC LIMIT 1)
        LEFT JOIN folder_covers c ON c.bucket_id = a.bucket_id
        LEFT JOIN media p ON p.id = c.photo_id AND p.bucket_id = a.bucket_id
        """
    )
    fun folders(types: List<Int>, includeHidden: Boolean): Flow<List<FolderRow>>

    @Query(
        """
        SELECT m.bucket_id, MIN(m.bucket_name) AS bucket_name, COUNT(*) AS item_count, SUM(m.size) AS size,
               MAX(m.date_taken) AS date_taken, MAX(m.date_modified) AS date_modified,
               (SELECT c.id FROM media c WHERE c.bucket_id = m.bucket_id ORDER BY c.date_taken DESC LIMIT 1) AS cover_id,
               (SELECT c.type FROM media c WHERE c.bucket_id = m.bucket_id ORDER BY c.date_taken DESC LIMIT 1) AS cover_type,
               p.id AS preferred_photo_id, p.type AS preferred_photo_type
        FROM media m
        LEFT JOIN folder_covers c ON c.bucket_id = m.bucket_id
        LEFT JOIN media p ON p.id = c.photo_id AND p.bucket_id = m.bucket_id
        WHERE m.bucket_id IN (SELECT bucket_id FROM ignored_folders)
        GROUP BY m.bucket_id
        """
    )
    fun hiddenFolders(): Flow<List<FolderRow>>

    @Query(
        """
        SELECT bucket_id, MIN(bucket_name) AS bucket_name, MIN(relative_path) AS relative_path,
               COUNT(*) AS item_count, MIN(id) AS media_id
        FROM media GROUP BY bucket_id ORDER BY bucket_name COLLATE NOCASE
        """
    )
    fun allFolders(): Flow<List<FolderNameRow>>

    @Query("SELECT * FROM media WHERE bucket_id = :bucketId AND type IN (:types)")
    fun mediaIn(bucketId: Long, types: List<Int>): Flow<List<MediumEntity>>

    @Query("SELECT * FROM media WHERE type IN (:types) AND bucket_id NOT IN (SELECT bucket_id FROM ignored_folders)")
    fun recent(types: List<Int>): Flow<List<MediumEntity>>

    @Query(
        """
        SELECT m.*, f.added_order FROM media m
        JOIN favorites f ON f.path = m.relative_path || m.name
        WHERE m.type IN (:types)
          AND m.bucket_id NOT IN (SELECT bucket_id FROM ignored_folders)
        """
    )
    fun favoriteMedia(types: List<Int>): Flow<List<FavoriteMediaRow>>

    @Query(
        """
        SELECT * FROM media
        WHERE name LIKE '%' || :query || '%' AND bucket_id NOT IN (:locked)
          AND bucket_id NOT IN (SELECT bucket_id FROM ignored_folders)
        ORDER BY date_taken DESC LIMIT 300
        """
    )
    fun search(query: String, locked: List<Long>): Flow<List<MediumEntity>>

    @Query("SELECT * FROM media WHERE bucket_id IN (:bucketIds)")
    suspend fun mediaForFolders(bucketIds: List<Long>): List<MediumEntity>

    @Query("SELECT * FROM media WHERE bucket_id IN (:bucketIds) AND bucket_id NOT IN (SELECT bucket_id FROM ignored_folders)")
    fun observeMirrorFiles(bucketIds: List<Long>): Flow<List<MediumEntity>>

    @Query("SELECT * FROM media WHERE id = :id")
    suspend fun medium(id: Long): MediumEntity?

    @Query("SELECT relative_path || name FROM media WHERE id = :id")
    suspend fun pathOf(id: Long): String?

    @Query("SELECT EXISTS(SELECT 1 FROM media WHERE relative_path || name = :path)")
    suspend fun hasPath(path: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<MediumEntity>)

    @Query("SELECT * FROM media")
    suspend fun indexedMedia(): List<MediumEntity>

    @Query("SELECT * FROM media WHERE id IN (:ids)")
    suspend fun indexedMedia(ids: Set<Long>): List<MediumEntity>

    @Query("SELECT * FROM media WHERE substr(relative_path, 1, length(:prefix)) = :prefix COLLATE NOCASE")
    suspend fun indexedUnder(prefix: String): List<MediumEntity>

    @Delete
    suspend fun deleteAll(items: List<MediumEntity>)

    @Query("DELETE FROM media WHERE id IN (:ids)")
    suspend fun removeIds(ids: List<Long>)

    @Transaction
    suspend fun removeFromIndex(ids: Set<Long>) {
        ids.chunked(500).forEach { removeIds(it) }
    }

    @Transaction
    suspend fun updateIndex(items: List<MediumEntity>, previous: List<MediumEntity>): MediaIndexChanges {
        val remaining = previous.associateByTo(HashMap(), MediumEntity::id)
        val removed = mutableListOf<MediumEntity>()
        val changed = items.filter { item ->
            val old = remaining.remove(item.id)
            if (old != null && (old.bucketId != item.bucketId || old.relativePath != item.relativePath || old.name != item.name)) {
                removed += old
            }
            old != item
        }
        if (remaining.isNotEmpty()) deleteAll(remaining.values.toList())
        if (changed.isNotEmpty()) insertAll(changed)
        removed.addAll(remaining.values)
        return MediaIndexChanges(removed, changed)
    }
}

data class MediaIndexChanges(val removed: List<MediumEntity>, val changed: List<MediumEntity>)

@Dao
interface FolderCoverDao {
    @Query("SELECT * FROM folder_covers")
    fun all(): Flow<List<FolderCoverEntity>>

    @Query("SELECT * FROM folder_covers WHERE bucket_id = :id")
    suspend fun find(id: Long): FolderCoverEntity?

    @Upsert
    suspend fun upsert(cover: FolderCoverEntity)

    @Query("DELETE FROM folder_covers WHERE bucket_id = :id")
    suspend fun remove(id: Long)

    @Transaction
    suspend fun setPhoto(id: Long, photoId: Long?) {
        upsert((find(id) ?: FolderCoverEntity(id)).copy(photoId = photoId))
    }

}

@Dao
interface IgnoredFolderDao {
    @Query("SELECT * FROM ignored_folders ORDER BY name COLLATE NOCASE")
    fun all(): Flow<List<IgnoredFolderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(folder: IgnoredFolderEntity)

    @Query("DELETE FROM ignored_folders WHERE bucket_id = :id")
    suspend fun remove(id: Long)

    @Query("DELETE FROM ignored_folders WHERE bucket_id IN (:bucketIds)")
    suspend fun removeAll(bucketIds: List<Long>)

    @Query("DELETE FROM ignored_folders")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addAll(folders: List<IgnoredFolderEntity>)

    @Transaction
    suspend fun replaceAll(folders: List<IgnoredFolderEntity>) {
        clear()
        addAll(folders)
    }
}

@Dao
interface PinnedFolderDao {
    @Query("SELECT bucket_id FROM pinned_folders")
    fun ids(): Flow<List<Long>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addAll(folders: List<PinnedFolderEntity>)

    @Query("DELETE FROM pinned_folders WHERE bucket_id IN (:bucketIds)")
    suspend fun removeAll(bucketIds: List<Long>)
}

@Dao
interface FavoriteDao {
    @Query("SELECT path FROM favorites")
    fun paths(): Flow<List<String>>

    @Query("SELECT * FROM favorites")
    fun entries(): Flow<List<FavoriteEntity>>

    @Query("INSERT OR IGNORE INTO favorites(path, added_order) SELECT :path, COALESCE(MAX(added_order), 0) + 1 FROM favorites")
    suspend fun add(path: String)

    @Query("INSERT OR IGNORE INTO favorites(path, added_order) SELECT :target, added_order FROM favorites WHERE path = :source")
    suspend fun copy(source: String, target: String)

    @Transaction
    suspend fun move(source: String, target: String) {
        if (source == target) return
        copy(source, target)
        remove(source)
    }

    @Query("DELETE FROM favorites WHERE path = :path")
    suspend fun remove(path: String)
}
