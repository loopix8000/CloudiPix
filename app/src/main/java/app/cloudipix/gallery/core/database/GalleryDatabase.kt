package app.cloudipix.gallery.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MediumEntity::class, FavoriteEntity::class, IgnoredFolderEntity::class,
        PinnedFolderEntity::class, FolderCoverEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class GalleryDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun ignoredFolderDao(): IgnoredFolderDao
    abstract fun pinnedFolderDao(): PinnedFolderDao
    abstract fun folderCoverDao(): FolderCoverDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS ignored_folders " +
                        "(bucket_id INTEGER NOT NULL, name TEXT NOT NULL, PRIMARY KEY(bucket_id))"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS pinned_folders " +
                        "(bucket_id INTEGER NOT NULL, PRIMARY KEY(bucket_id))"
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS folder_covers " +
                        "(bucket_id INTEGER NOT NULL, photo_id INTEGER, blur_automatic INTEGER NOT NULL, PRIMARY KEY(bucket_id))"
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE favorites ADD COLUMN added_order INTEGER NOT NULL DEFAULT 0")
                // 기존 즐겨찾기는 당시의 체크 시각 대신 저장된 행 순서를 사용한다.
                db.execSQL("UPDATE favorites SET added_order = rowid")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_favorites_added_order ON favorites(added_order)")
            }
        }
    }
}
