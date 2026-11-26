package com.rsoundveil.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal object NetworkJson {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): JSONObject =
        withContext(Dispatchers.IO) {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 12_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "RSoundveil/0.1.0 (https://github.com/riynk/rsoundveil)")
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            try {
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (status !in 200..299) throw IOException("HTTP " + status + ": " + body.take(240))
                if (body.isBlank()) JSONObject() else JSONObject(body)
            } finally {
                connection.disconnect()
            }
        }
}
