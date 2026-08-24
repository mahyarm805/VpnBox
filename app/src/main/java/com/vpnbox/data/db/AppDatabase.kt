package com.vpnbox.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.vpnbox.data.model.ProxyChain
import com.vpnbox.data.model.ServerConfig

@Database(
    entities = [ServerConfig::class, ProxyChain::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun proxyChainDao(): ProxyChainDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vpnbox_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
