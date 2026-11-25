package app.cloudipix.gallery.di

import android.content.Context
import androidx.room.Room
import app.cloudipix.gallery.core.database.FavoriteDao
import app.cloudipix.gallery.core.database.FolderCoverDao
import app.cloudipix.gallery.core.database.GalleryDatabase
import app.cloudipix.gallery.core.database.IgnoredFolderDao
import app.cloudipix.gallery.core.database.MediaDao
import app.cloudipix.gallery.core.database.PinnedFolderDao
import app.cloudipix.gallery.core.database.SyncIndexDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/** 앱 전체에서 하나씩 쓰는 데이터베이스와 DAO를 제공합니다. */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /** 사용자 데이터가 든 갤러리 데이터베이스입니다. 파일 이름을 바꾸면 기존 데이터를 잃습니다. */
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): GalleryDatabase = Room
        .databaseBuilder(context, GalleryDatabase::class.java, "gallery.db")
        .addMigrations(*GalleryDatabase.MIGRATIONS)
        .build()

    /** 폴더 동기화 색인입니다. 기기에서 다시 만들 수 있는 값이라 백업하지 않는 폴더에 둡니다. */
    @Provides
    @Singleton
    internal fun syncIndexDatabase(@ApplicationContext context: Context): SyncIndexDatabase {
        val file = File(context.noBackupFilesDir, "sync-index.db")
        return Room.databaseBuilder(context, SyncIndexDatabase::class.java, file.absolutePath).build()
    }

    @Provides fun mediaDao(database: GalleryDatabase): MediaDao = database.mediaDao()

    @Provides fun favoriteDao(database: GalleryDatabase): FavoriteDao = database.favoriteDao()

    @Provides fun ignoredFolderDao(database: GalleryDatabase): IgnoredFolderDao = database.ignoredFolderDao()

    @Provides fun pinnedFolderDao(database: GalleryDatabase): PinnedFolderDao = database.pinnedFolderDao()

    @Provides fun folderCoverDao(database: GalleryDatabase): FolderCoverDao = database.folderCoverDao()
}
