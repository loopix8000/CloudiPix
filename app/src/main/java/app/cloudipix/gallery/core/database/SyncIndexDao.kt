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
import app.cloudipix.gallery.core.remote.RemoteEntry
import app.cloudipix.gallery.core.sync.SyncStamp

@Database(entities = [SyncFileRow::class, ImportedSyncRoot::class, DavDirectoryRow::class, DavEntryRow::class], version = 1)
internal abstract class SyncIndexDatabase : RoomDatabase() {
    abstract fun syncIndexDao(): SyncIndexDao
}

@Entity(tableName = "sync_files", primaryKeys = ["root", "path"], indices = [Index("root", "foldedPath")])
internal data class SyncFileRow(
    val root: String,
    val path: String,
    val foldedPath: String,
    val hash: String,
    @Embedded(prefix = "local_") val local: SyncStamp,
    @Embedded(prefix = "remote_") val remote: SyncStamp?,
)

@Entity(tableName = "dav_directories", primaryKeys = ["directory"])
internal data class DavDirectoryRow(val directory: Long, val server: String, val token: String)

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

    @Transaction
    fun saveListing(id: Long, server: String, token: String, entries: List<RemoteEntry>, removed: List<String>, replace: Boolean) {
        if (replace) clearEntries(id) else removed.chunked(500).forEach { deleteEntries(id, it) }
        putEntries(entries.map { DavEntryRow(id, it) })
        putDirectory(DavDirectoryRow(id, server, token))
    }
}
