package com.vpnbox.data.db

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.vpnbox.data.model.Protocol

class Converters {
    private val gson = Gson()

    @TypeConverter
    fun fromProtocol(protocol: Protocol): String = protocol.name

    @TypeConverter
    fun toProtocol(value: String): Protocol = Protocol.valueOf(value)

    @TypeConverter
    fun fromLongList(value: List<Long>): String = gson.toJson(value)

    @TypeConverter
    fun toLongList(value: String): List<Long> {
        val type = object : TypeToken<List<Long>>() {}.type
        return gson.fromJson(value, type)
    }
}
