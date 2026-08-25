package com.vpnbox.data.db

import androidx.room.*
import com.vpnbox.data.model.ServerConfig
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {
    @Query("SELECT * FROM servers ORDER BY createdAt DESC")
    fun getAllServers(): Flow<List<ServerConfig>>

    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun getServerById(id: Long): ServerConfig?

    @Query("SELECT * FROM servers WHERE isSelected = 1 LIMIT 1")
    suspend fun getSelectedServer(): ServerConfig?

    @Query("SELECT * FROM servers WHERE isSelected = 1 LIMIT 1")
    fun observeSelectedServer(): Flow<ServerConfig?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServer(server: ServerConfig): Long

    @Update
    suspend fun updateServer(server: ServerConfig)

    @Delete
    suspend fun deleteServer(server: ServerConfig)

    @Query("UPDATE servers SET isSelected = 0")
    suspend fun clearSelected()

    @Query("SELECT COUNT(*) FROM servers")
    suspend fun getServerCount(): Int
}
