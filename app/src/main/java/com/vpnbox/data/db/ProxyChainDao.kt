package com.vpnbox.data.db

import androidx.room.*
import com.vpnbox.data.model.ProxyChain
import kotlinx.coroutines.flow.Flow

@Dao
interface ProxyChainDao {
    @Query("SELECT * FROM proxy_chains ORDER BY createdAt DESC")
    fun getAllChains(): Flow<List<ProxyChain>>

    @Query("SELECT * FROM proxy_chains WHERE id = :id")
    suspend fun getChainById(id: Long): ProxyChain?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChain(chain: ProxyChain): Long

    @Update
    suspend fun updateChain(chain: ProxyChain)

    @Delete
    suspend fun deleteChain(chain: ProxyChain)
}
