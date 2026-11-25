package app.cloudipix.gallery.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 갤러리 색인·즐겨찾기·폴더 설정 데이터베이스입니다.
 * 스키마를 바꾸면 [version]을 올리고 [MIGRATIONS]에 이전 버전에서 올라오는 마이그레이션을 추가합니다.
 */
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
        /**
         * 버전별 마이그레이션입니다. SQL은 해당 버전에서 Room이 만든 테이블 정의와 같아야 하므로
         * 이미 배포한 문장은 고치지 않습니다.
         */
        val MIGRATIONS: Array<Migration> = arrayOf(
            // 2: 숨긴 폴더
            sqlMigration(
                1, 2,
                "CREATE TABLE IF NOT EXISTS ignored_folders (bucket_id INTEGER NOT NULL, name TEXT NOT NULL, PRIMARY KEY(bucket_id))",
            ),
            // 3: 고정한 폴더
            sqlMigration(
                2, 3,
                "CREATE TABLE IF NOT EXISTS pinned_folders (bucket_id INTEGER NOT NULL, PRIMARY KEY(bucket_id))",
            ),
            // 4: 폴더 표지
            sqlMigration(
                3, 4,
                "CREATE TABLE IF NOT EXISTS folder_covers " +
                    "(bucket_id INTEGER NOT NULL, photo_id INTEGER, blur_automatic INTEGER NOT NULL, PRIMARY KEY(bucket_id))",
            ),
            // 5: 즐겨찾기 추가 순서. 기존 즐겨찾기는 추가 시각을 알 수 없어 저장된 행 순서를 그대로 씁니다.
            sqlMigration(
                4, 5,
                "ALTER TABLE favorites ADD COLUMN added_order INTEGER NOT NULL DEFAULT 0",
                "UPDATE favorites SET added_order = rowid",
                "CREATE INDEX IF NOT EXISTS index_favorites_added_order ON favorites(added_order)",
            ),
        )

        private fun sqlMigration(from: Int, to: Int, vararg statements: String): Migration =
            object : Migration(from, to) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    for (sql in statements) db.execSQL(sql)
                }
            }
    }
}
