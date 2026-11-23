package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
  entities = [UserAccountEntity::class, GraphProjectEntity::class, StudioNoteEntity::class],
  version = 1,
  exportSchema = false,
)
abstract class LinkDrawDatabase : RoomDatabase() {
  abstract fun linkDrawDao(): LinkDrawDao

  companion object {
    @Volatile private var INSTANCE: LinkDrawDatabase? = null

    fun getInstance(context: Context): LinkDrawDatabase =
      INSTANCE
        ?: synchronized(this) {
          INSTANCE
            ?: Room.databaseBuilder(
                context.applicationContext,
                LinkDrawDatabase::class.java,
                "linkdraw_studio.db",
              )
              .build()
              .also { INSTANCE = it }
        }
  }
}
