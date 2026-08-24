package com.vpnbox.core

import com.vpnbox.data.model.ProxyChain
import com.vpnbox.data.model.ServerConfig
import com.vpnbox.data.repository.ServerRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProxyChainManager @Inject constructor(
    private val repository: ServerRepository
) {
    suspend fun getChainConfig(chain: ProxyChain): String? {
        val servers = mutableListOf<ServerConfig>()
        for (serverId in chain.serverIds) {
            val server = repository.getServerById(serverId) ?: return null
            servers.add(server)
        }

        if (servers.isEmpty()) return null

        return if (servers.size == 1) {
            ConfigGenerator().generateConfig(servers.first())
        } else {
            ConfigGenerator().generateChainConfig(servers)
        }
    }

    suspend fun createChain(name: String, serverIds: List<Long>): ProxyChain {
        val chain = ProxyChain(
            name = name,
            serverIds = serverIds
        )
        val id = repository.insertChain(chain)
        return chain.copy(id = id)
    }

    suspend fun deleteChain(chain: ProxyChain) {
        repository.deleteChain(chain)
    }

    suspend fun toggleChain(chain: ProxyChain) {
        repository.updateChain(chain.copy(isActive = !chain.isActive))
    }
}
