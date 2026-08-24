package com.vpnbox.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "proxy_chains")
data class ProxyChain(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val serverIds: List<Long>,
    val isActive: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
