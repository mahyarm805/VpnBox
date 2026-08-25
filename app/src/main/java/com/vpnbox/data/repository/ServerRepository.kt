package com.vpnbox.data.repository

import com.vpnbox.data.db.ProxyChainDao
import com.vpnbox.data.db.ServerDao
import com.vpnbox.data.model.ProxyChain
import com.vpnbox.data.model.ServerConfig
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerRepository @Inject constructor(
    private val serverDao: ServerDao,
    private val proxyChainDao: ProxyChainDao
) {
    // Server operations
    fun getAllServers(): Flow<List<ServerConfig>> = serverDao.getAllServers()

    suspend fun getServerById(id: Long): ServerConfig? = serverDao.getServerById(id)

    suspend fun getSelectedServer(): ServerConfig? = serverDao.getSelectedServer()

    fun observeSelectedServer(): Flow<ServerConfig?> = serverDao.observeSelectedServer()

    suspend fun insertServer(server: ServerConfig): Long = serverDao.insertServer(server)

    suspend fun updateServer(server: ServerConfig) = serverDao.updateServer(server)

    suspend fun deleteServer(server: ServerConfig) = serverDao.deleteServer(server)

    suspend fun selectServer(server: ServerConfig) {
        serverDao.clearSelected()
        serverDao.updateServer(server.copy(isSelected = true))
    }

    suspend fun getServerCount(): Int = serverDao.getServerCount()

    // Proxy Chain operations
    fun getAllChains(): Flow<List<ProxyChain>> = proxyChainDao.getAllChains()

    suspend fun getChainById(id: Long): ProxyChain? = proxyChainDao.getChainById(id)

    suspend fun insertChain(chain: ProxyChain): Long = proxyChainDao.insertChain(chain)

    suspend fun updateChain(chain: ProxyChain) = proxyChainDao.updateChain(chain)

    suspend fun deleteChain(chain: ProxyChain) = proxyChainDao.deleteChain(chain)
}
