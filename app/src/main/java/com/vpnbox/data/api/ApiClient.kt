package com.vpnbox.data.api

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class LoginRequest(
    @SerializedName("username") val username: String,
    @SerializedName("password") val password: String
)

data class LoginResponse(
    @SerializedName("token") val token: String,
    @SerializedName("user_id") val userId: String
)

data class ServerResponse(
    @SerializedName("servers") val servers: List<ServerData>
)

data class ServerData(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("protocol") val protocol: String,
    @SerializedName("address") val address: String,
    @SerializedName("port") val port: Int,
    @SerializedName("country") val country: String?
)

class ApiClient {
    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var baseUrl = ""
    private var authToken = ""

    fun setBaseUrl(url: String) {
        baseUrl = url.trimEnd('/')
    }

    fun setAuthToken(token: String) {
        authToken = token
    }

    suspend fun login(username: String, password: String): Result<LoginResponse> {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/api/login")
                .post(gson.toJson(LoginRequest(username, password)).toRequestBody(jsonMediaType))
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                Result.success(gson.fromJson(body, LoginResponse::class.java))
            } else {
                Result.failure(Exception("Login failed: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getServers(): Result<List<ServerData>> {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/api/servers")
                .addHeader("Authorization", "Bearer $authToken")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val serverResponse = gson.fromJson(body, ServerResponse::class.java)
                Result.success(serverResponse.servers)
            } else {
                Result.failure(Exception("Failed to fetch servers: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
