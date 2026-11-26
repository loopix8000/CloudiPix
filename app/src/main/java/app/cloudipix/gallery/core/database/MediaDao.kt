package app.cloudipix.gallery.core.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** 한 번에 지우는 ID 수입니다. 오래된 SQLite의 바인딩 변수 한도(999)보다 작게 나눕니다. */
private const val DELETE_BATCH = 500

/** 미디어 색인 조회와 갱신입니다. 숨긴 폴더(ignored_folders)는 따로 요청하지 않으면 결과에서 뺍니다. */
@Dao
interface MediaDao {

    /**
     * 종류 필터에 맞는 항목이 있는 폴더 목록입니다. 대표 표지는 가장 최근에 찍은 항목(같으면 ID가 큰 항목)이고,
     * 사용자가 고른 표지가 아직 그 폴더에 있으면 preferred_photo_*로 함께 돌려줍니다.
     */
    @Query(
        """
        SELECT g.*,
               cover.id AS cover_id, cover.type AS cover_type,
               chosen.id AS preferred_photo_id, chosen.type AS preferred_photo_type
        FROM (
            SELECT bucket_id,
                   MIN(bucket_name) AS bucket_name,
                   COUNT(*) AS item_count,
                   SUM(size) AS size,
                   MAX(date_taken) AS date_taken,
                   MAX(date_modified) AS date_modified
            FROM media
            WHERE type IN (:types)
              AND (:includeHidden OR bucket_id NOT IN (SELECT bucket_id FROM ignored_folders))
            GROUP BY bucket_id
        ) g
        JOIN media cover ON cover.id = (
            SELECT id FROM media
            WHERE bucket_id = g.bucket_id AND type IN (:types)
            ORDER BY date_taken DESC, id DESC
            LIMIT 1
        )
        LEFT JOIN folder_covers pick ON pick.bucket_id = g.bucket_id
        LEFT JOIN media chosen ON chosen.id = pick.photo_id AND chosen.bucket_id = g.bucket_id
        """
    )
    fun folders(types: List<Int>, includeHidden: Boolean): Flow<List<FolderRow>>

    /**
     * 숨긴 폴더 목록입니다. 종류 필터 없이 모든 항목을 셉니다. 표지 ID와 종류는 같은 항목에서 가져오도록
     * 두 하위 쿼리의 정렬을 같게 둡니다(촬영 시각이 같으면 ID가 큰 항목).
     */
    @Query(
        """
        SELECT m.bucket_id,
               MIN(m.bucket_name) AS bucket_name,
               COUNT(*) AS item_count,
               SUM(m.size) AS size,
               MAX(m.date_taken) AS date_taken,
               MAX(m.date_modified) AS date_modified,
               (SELECT x.id FROM media x WHERE x.bucket_id = m.bucket_id ORDER BY x.date_taken DESC, x.id DESC LIMIT 1) AS cover_id,
               (SELECT x.type FROM media x WHERE x.bucket_id = m.bucket_id ORDER BY x.date_taken DESC, x.id DESC LIMIT 1) AS cover_type,
               chosen.id AS preferred_photo_id, chosen.type AS preferred_photo_type
        FROM media m
        LEFT JOIN folder_covers pick ON pick.bucket_id = m.bucket_id
        LEFT JOIN media chosen ON chosen.id = pick.photo_id AND chosen.bucket_id = m.bucket_id
        WHERE m.bucket_id IN (SELECT bucket_id FROM ignored_folders)
        GROUP BY m.bucket_id
        """
    )
    fun hiddenFolders(): Flow<List<FolderRow>>

    /** 숨긴 폴더를 포함한 모든 폴더의 이름순 목록입니다. */
    @Query(
        """
        SELECT bucket_id,
               MIN(bucket_name) AS bucket_name,
               MIN(relative_path) AS relative_path,
               COUNT(*) AS item_count,
               MIN(id) AS media_id
        FROM media
        GROUP BY bucket_id
        ORDER BY bucket_name COLLATE NOCASE
        """
    )
    fun allFolders(): Flow<List<FolderNameRow>>

    @Query("SELECT * FROM media WHERE bucket_id = :bucketId AND type IN (:types)")
    fun mediaIn(bucketId: Long, types: List<Int>): Flow<List<MediumEntity>>

    /** 숨긴 폴더를 뺀 모든 폴더의 항목입니다. */
    @Query("SELECT * FROM media WHERE type IN (:types) AND bucket_id NOT IN (SELECT bucket_id FROM ignored_folders)")
    fun recent(types: List<Int>): Flow<List<MediumEntity>>

    /** 즐겨찾기 경로와 일치하는 항목입니다. 숨긴 폴더의 항목은 뺍니다. */
    @Query(
        """
        SELECT media.*, fav.added_order
        FROM media
        JOIN favorites fav ON fav.path = media.relative_path || media.name
        WHERE media.type IN (:types)
          AND media.bucket_id NOT IN (SELECT bucket_id FROM ignored_folders)
        """
    )
    fun favoriteMedia(types: List<Int>): Flow<List<FavoriteMediaRow>>

    /** 이름에 [query]가 들어간 항목을 최근 촬영순으로 300개까지 찾습니다. 잠금·숨김 폴더는 뺍니다. */
    @Query(
        """
        SELECT * FROM media
        WHERE name LIKE '%' || :query || '%'
          AND bucket_id NOT IN (:locked)
          AND bucket_id NOT IN (SELECT bucket_id FROM ignored_folders)
        ORDER BY date_taken DESC
        LIMIT 300
        """
    )
    fun search(query: String, locked: List<Long>): Flow<List<MediumEntity>>

    @Query("SELECT * FROM media WHERE bucket_id IN (:bucketIds)")
    suspend fun mediaForFolders(bucketIds: List<Long>): List<MediumEntity>

    @Query("SELECT * FROM media WHERE bucket_id IN (:bucketIds) AND bucket_id NOT IN (SELECT bucket_id FROM ignored_folders)")
    fun observeMirrorFiles(bucketIds: List<Long>): Flow<List<MediumEntity>>

    @Query("SELECT * FROM media WHERE id = :id")
    suspend fun medium(id: Long): MediumEntity?

    /** 즐겨찾기에 쓰는 경로(`relative_path + name`)입니다. */
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

    /** 상대 경로가 [prefix]로 시작하는 항목입니다. 대소문자를 구분하지 않습니다. */
    @Query("SELECT * FROM media WHERE substr(relative_path, 1, length(:prefix)) = :prefix COLLATE NOCASE")
    suspend fun indexedUnder(prefix: String): List<MediumEntity>

    @Delete
    suspend fun deleteAll(items: List<MediumEntity>)

    @Query("DELETE FROM media WHERE id IN (:ids)")
    suspend fun removeIds(ids: List<Long>)

    @Transaction
    suspend fun removeFromIndex(ids: Set<Long>) {
        for (batch in ids.chunked(DELETE_BATCH)) removeIds(batch)
    }

    /**
     * [previous]로 읽어 둔 기존 색인을 새로 읽은 [items]로 바꿉니다. 내용이 같은 항목은 다시 쓰지 않습니다.
     *
     * 돌려주는 `removed`에는 사라진 항목과, 폴더나 이름이 바뀌어 예전 위치에서 없어진 항목의 예전 값이 들어갑니다.
     * `changed`에는 새로 생겼거나 내용이 바뀐 항목이 들어갑니다.
     */
    @Transaction
    suspend fun updateIndex(items: List<MediumEntity>, previous: List<MediumEntity>): MediaIndexChanges {
        val unmatched = HashMap<Long, MediumEntity>(previous.size)
        for (entity in previous) unmatched[entity.id] = entity

        val updated = ArrayList<MediumEntity>()
        val changed = ArrayList<MediumEntity>()
        val relocated = ArrayList<MediumEntity>()
        for (item in items) {
            val before = unmatched.remove(item.id)
            if (before == item) continue
            updated += item
            // 수정 세대만 바뀌었으면 파일 위치·크기·수정 시각이 그대로라 동기화할 변경으로 보지 않습니다.
            if (before != null && before.copy(generation = item.generation) == item) continue
            changed += item
            if (before != null && before.movedTo(item)) relocated += before
        }

        val gone = unmatched.values.toList()
        if (gone.isNotEmpty()) deleteAll(gone)
        if (updated.isNotEmpty()) insertAll(updated)
        return MediaIndexChanges(removed = relocated + gone, changed = changed)
    }
}

/** 폴더나 상대 경로, 이름 중 하나라도 바뀌었으면 예전 위치에서는 사라진 것으로 봅니다. */
private fun MediumEntity.movedTo(current: MediumEntity): Boolean =
    bucketId != current.bucketId || relativePath != current.relativePath || name != current.name

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

    /** 표지 사진만 바꾸고 같은 행의 다른 값은 그대로 둡니다. [photoId]가 `null`이면 자동 표지로 돌아갑니다. */
    @Transaction
    suspend fun setPhoto(id: Long, photoId: Long?) {
        val current = find(id) ?: FolderCoverEntity(bucketId = id)
        upsert(current.copy(photoId = photoId))
    }
}

@Dao
interface IgnoredFolderDao {
    @Query("SELECT * FROM ignored_folders ORDER BY name COLLATE NOCASE")
    fun all(): Flow<List<IgnoredFolderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(folder: IgnoredFolderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addAll(folders: List<IgnoredFolderEntity>)

    @Query("DELETE FROM ignored_folders WHERE bucket_id = :id")
    suspend fun remove(id: Long)

    @Query("DELETE FROM ignored_folders WHERE bucket_id IN (:bucketIds)")
    suspend fun removeAll(bucketIds: List<Long>)

    @Query("DELETE FROM ignored_folders")
    suspend fun clear()

    /** 숨긴 폴더 목록 전체를 한 트랜잭션에서 바꿉니다. */
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

/** 즐겨찾기입니다. 새로 추가한 항목은 지금까지의 가장 큰 추가 순서 다음 번호를 받습니다. */
@Dao
interface FavoriteDao {
    @Query("SELECT path FROM favorites")
    fun paths(): Flow<List<String>>

    @Query("SELECT * FROM favorites")
    fun entries(): Flow<List<FavoriteEntity>>

    /** 백업용 경로 목록입니다. 복원했을 때 순서가 유지되도록 추가 순서대로 돌려줍니다. */
    @Query("SELECT path FROM favorites ORDER BY added_order, path")
    suspend fun pathsForBackup(): List<String>

    @Query("SELECT COALESCE(MAX(added_order), 0) FROM favorites")
    suspend fun latestOrder(): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMissing(entries: List<FavoriteEntity>)

    /** 백업의 즐겨찾기를 지금 목록 뒤에 순서대로 붙입니다. 이미 있는 경로는 기존 순서를 유지합니다. */
    @Transaction
    suspend fun restoreBackup(paths: List<String>) {
        if (paths.isEmpty()) return
        val base = latestOrder()
        val restored = paths.mapIndexed { offset, path -> FavoriteEntity(path, addedOrder = base + offset + 1) }
        insertMissing(restored)
    }

    @Query("INSERT OR IGNORE INTO favorites(path, added_order) SELECT :path, COALESCE(MAX(added_order), 0) + 1 FROM favorites")
    suspend fun add(path: String)

    @Query("INSERT OR IGNORE INTO favorites(path, added_order) SELECT :target, added_order FROM favorites WHERE path = :source")
    suspend fun copy(source: String, target: String)

    /** 파일을 옮기거나 이름을 바꿨을 때 즐겨찾기 경로를 따라 옮깁니다. 추가 순서는 유지합니다. */
    @Transaction
    suspend fun move(source: String, target: String) {
        if (source == target) return
        copy(source, target)
        remove(source)
    }

    @Query("DELETE FROM favorites WHERE path = :path")
    suspend fun remove(path: String)
}
