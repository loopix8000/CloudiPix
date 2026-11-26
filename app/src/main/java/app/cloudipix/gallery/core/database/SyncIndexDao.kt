package app.cloudipix.gallery.core.database

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cloudipix.gallery.core.remote.RemoteEntry
import app.cloudipix.gallery.core.sync.SyncStamp
import app.cloudipix.gallery.core.sync.syncHashBytes

@Database(entities = [SyncFileRow::class, ImportedSyncRoot::class, DavDirectoryRow::class, DavEntryRow::class], version = 3)
internal abstract class SyncIndexDatabase : RoomDatabase() {
    abstract fun syncIndexDao(): SyncIndexDao

    companion object {
        /** 버전별 마이그레이션입니다. 이미 배포한 문장은 고치지 않습니다. */
        val MIGRATIONS: Array<Migration> = arrayOf(
            // 2: 파일 해시를 64자 16진 문자열 대신 SHA-256 32바이트로 저장한다.
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE `sync_files_new` (`root` TEXT NOT NULL, `path` TEXT NOT NULL, " +
                            "`foldedPath` TEXT NOT NULL, `hash` BLOB NOT NULL, `local_size` INTEGER NOT NULL, " +
                            "`local_modified` INTEGER NOT NULL, `local_etag` TEXT, `remote_size` INTEGER, " +
                            "`remote_modified` INTEGER, `remote_etag` TEXT, PRIMARY KEY(`root`, `path`))",
                    )
                    db.compileStatement("INSERT INTO `sync_files_new` VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)").use { insert ->
                        db.query(
                            "SELECT `root`, `path`, `foldedPath`, `hash`, `local_size`, `local_modified`, `local_etag`, " +
                                "`remote_size`, `remote_modified`, `remote_etag` FROM `sync_files`",
                        ).use { cursor ->
                            while (cursor.moveToNext()) {
                                // 형식이 맞지 않는 기록은 옮기지 않는다. 다음 동기화에서 양쪽 내용을 비교해 다시 기록한다.
                                val hash = cursor.getString(3)?.syncHashBytes() ?: continue
                                insert.clearBindings()
                                insert.bindString(1, cursor.getString(0))
                                insert.bindString(2, cursor.getString(1))
                                insert.bindString(3, cursor.getString(2))
                                insert.bindBlob(4, hash)
                                insert.bindLong(5, cursor.getLong(4))
                                insert.bindLong(6, cursor.getLong(5))
                                for (column in 6..9) {
                                    val index = column + 1
                                    when {
                                        cursor.isNull(column) -> insert.bindNull(index)
                                        column == 6 || column == 9 -> insert.bindString(index, cursor.getString(column))
                                        else -> insert.bindLong(index, cursor.getLong(column))
                                    }
                                }
                                insert.executeInsert()
                            }
                        }
                    }
                    db.execSQL("DROP TABLE `sync_files`")
                    db.execSQL("ALTER TABLE `sync_files_new` RENAME TO `sync_files`")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_files_root_foldedPath` ON `sync_files` (`root`, `foldedPath`)")
                }
            },
            // 3: 서버 파일 ID(이동 감지)와 체크섬, 폴더 ETag 기준 목록 캐시. 목록 캐시는 다시 만들 수 있어 비우고 새로 받는다.
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `sync_files` ADD COLUMN `local_fileId` INTEGER")
                    db.execSQL("ALTER TABLE `sync_files` ADD COLUMN `remote_fileId` INTEGER")
                    db.execSQL("DROP TABLE IF EXISTS `dav_entries`")
                    db.execSQL("DROP TABLE IF EXISTS `dav_directories`")
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `dav_directories` (`directory` INTEGER NOT NULL, `server` TEXT NOT NULL, " +
                            "`token` TEXT, `etag` TEXT, `checkedAt` INTEGER NOT NULL, PRIMARY KEY(`directory`))",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `dav_entries` (`directoryId` INTEGER NOT NULL, `path` TEXT NOT NULL, " +
                            "`name` TEXT NOT NULL, `directory` INTEGER NOT NULL, `modified` INTEGER NOT NULL, `size` INTEGER NOT NULL, " +
                            "`etag` TEXT, `preview` TEXT, `mimeType` TEXT, `unsupported` INTEGER NOT NULL, " +
                            "`modifiedTimeIsApproximate` INTEGER NOT NULL, `fileId` INTEGER, `checksum` TEXT, " +
                            "PRIMARY KEY(`directoryId`, `name`))",
                    )
                }
            },
        )
    }
}

/** 동기화한 파일 기록입니다. [hash]는 마지막으로 확인한 내용의 SHA-256 32바이트입니다. */
@Entity(tableName = "sync_files", primaryKeys = ["root", "path"], indices = [Index("root", "foldedPath")])
internal data class SyncFileRow(
    val root: String,
    val path: String,
    val foldedPath: String,
    val hash: ByteArray,
    @Embedded(prefix = "local_") val local: SyncStamp,
    @Embedded(prefix = "remote_") val remote: SyncStamp?,
)

/**
 * 서버 폴더 목록 캐시의 기준입니다. 변경 목록을 받는 서버는 [token]을, 하위 변경이 폴더 ETag에 전파되는 서버는
 * 목록과 같은 응답에서 읽은 폴더의 [etag]를 둡니다. [checkedAt]은 서버에서 목록을 마지막으로 확인한 시각입니다.
 */
@Entity(tableName = "dav_directories", primaryKeys = ["directory"])
internal data class DavDirectoryRow(
    val directory: Long,
    val server: String,
    val token: String?,
    val etag: String? = null,
    val checkedAt: Long = 0L,
)

@Entity(tableName = "imported_sync_roots", primaryKeys = ["root"])
internal data class ImportedSyncRoot(val root: String)

@Entity(tableName = "dav_entries", primaryKeys = ["directoryId", "name"])
internal data class DavEntryRow(val directoryId: Long, @Embedded val entry: RemoteEntry)

@Dao
internal interface SyncIndexDao {
    @Query("SELECT EXISTS(SELECT 1 FROM imported_sync_roots WHERE root = :root)")
    fun imported(root: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun markImported(row: ImportedSyncRoot)

    @Query("DELETE FROM imported_sync_roots WHERE root = :root")
    fun forgetImport(root: String)

    @Query("SELECT * FROM sync_files WHERE root = :root")
    fun files(root: String): List<SyncFileRow>

    @Query("SELECT * FROM sync_files WHERE root = :root AND foldedPath IN (:paths)")
    fun files(root: String, paths: List<String>): List<SyncFileRow>

    @Query("SELECT * FROM sync_files WHERE root = :root AND foldedPath >= :start AND foldedPath < :end")
    fun filesInRange(root: String, start: String, end: String): List<SyncFileRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putFiles(rows: List<SyncFileRow>)

    @Query("DELETE FROM sync_files WHERE root = :root AND path IN (:paths)")
    fun deleteFiles(root: String, paths: List<String>)

    @Query("DELETE FROM sync_files WHERE root = :root")
    fun clearFiles(root: String)

    @Query("SELECT token FROM dav_directories WHERE directory = :id")
    fun token(id: Long): String?

    @Query("SELECT * FROM dav_directories WHERE directory = :id")
    fun directory(id: Long): DavDirectoryRow?

    @Query("UPDATE dav_directories SET checkedAt = :time WHERE directory = :id")
    fun markChecked(id: Long, time: Long)

    @Query("SELECT * FROM dav_entries WHERE directoryId = :id ORDER BY rowid")
    fun entries(id: Long): List<DavEntryRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putDirectory(row: DavDirectoryRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putEntries(rows: List<DavEntryRow>)

    @Query("DELETE FROM dav_entries WHERE directoryId = :id AND name IN (:names)")
    fun deleteEntries(id: Long, names: List<String>)

    @Query("DELETE FROM dav_entries WHERE directoryId = :id")
    fun clearEntries(id: Long)

    @Query("DELETE FROM dav_entries WHERE directoryId IN (SELECT directory FROM dav_directories WHERE server = :server)")
    fun clearServerEntries(server: String)

    @Query("DELETE FROM dav_directories WHERE server = :server")
    fun clearDirectories(server: String)

    @Transaction
    fun removeServer(server: String) {
        clearServerEntries(server)
        clearDirectories(server)
    }

    // 토큰과 ETag 중 하나만 기준으로 남긴다. 다른 방식으로 읽은 목록에 변경분을 이어 붙이지 않기 위해서다.
    @Transaction
    fun saveListing(id: Long, server: String, token: String?, etag: String?, entries: List<RemoteEntry>,
        removed: List<String>, replace: Boolean, checkedAt: Long) {
        if (replace) clearEntries(id) else removed.chunked(500).forEach { deleteEntries(id, it) }
        entries.chunked(500).forEach { batch -> putEntries(batch.map { DavEntryRow(id, it) }) }
        putDirectory(DavDirectoryRow(id, server, token, etag, checkedAt))
    }
}
