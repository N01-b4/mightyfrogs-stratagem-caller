package com.mightyfrog.stratagemcaller.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Stratagem::class], version = 1, exportSchema = false)
abstract class StratagemDatabase : RoomDatabase() {
    abstract fun dao(): StratagemDao

    companion object {
        @Volatile private var instance: StratagemDatabase? = null

        fun get(context: Context): StratagemDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    StratagemDatabase::class.java,
                    "stratagem-caller.db"
                ).build().also { instance = it }
            }
    }
}
