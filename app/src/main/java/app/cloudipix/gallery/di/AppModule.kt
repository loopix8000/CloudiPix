package app.cloudipix.gallery.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import app.cloudipix.gallery.core.database.FavoriteDao
import app.cloudipix.gallery.core.database.FolderCoverDao
import app.cloudipix.gallery.core.database.GalleryDatabase
import app.cloudipix.gallery.core.database.IgnoredFolderDao
import app.cloudipix.gallery.core.database.MediaDao
import app.cloudipix.gallery.core.database.PinnedFolderDao
import app.cloudipix.gallery.core.database.SyncIndexDatabase
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): GalleryDatabase =
        Room.databaseBuilder(context, GalleryDatabase::class.java, "gallery.db")
            .addMigrations(
                GalleryDatabase.MIGRATION_1_2,
                GalleryDatabase.MIGRATION_2_3,
                GalleryDatabase.MIGRATION_3_4,
                GalleryDatabase.MIGRATION_4_5,
            )
            .build()

    @Provides
    @Singleton
    internal fun syncIndexDatabase(@ApplicationContext context: Context): SyncIndexDatabase =
        Room.databaseBuilder(context, SyncIndexDatabase::class.java, File(context.noBackupFilesDir, "sync-index.db").absolutePath).build()

    @Provides
    fun mediaDao(db: GalleryDatabase): MediaDao = db.mediaDao()

    @Provides
    fun favoriteDao(db: GalleryDatabase): FavoriteDao = db.favoriteDao()

    @Provides
    fun ignoredFolderDao(db: GalleryDatabase): IgnoredFolderDao = db.ignoredFolderDao()

    @Provides
    fun pinnedFolderDao(db: GalleryDatabase): PinnedFolderDao = db.pinnedFolderDao()

    @Provides
    fun folderCoverDao(db: GalleryDatabase): FolderCoverDao = db.folderCoverDao()
}
