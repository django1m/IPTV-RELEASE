package com.iptvplayer.tv.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class DiagnosticResult(
    val ok: Boolean,
    val latencyMs: Long = -1
)

enum class DiagnosisType {
    ALL_OK,
    INTERNET_DOWN,
    PROVIDER_DOWN,
    BOTH_DOWN
}

data class FullDiagnosticResult(
    val internet: DiagnosticResult,
    val provider: DiagnosticResult,
    val diagnosis: DiagnosisType
)

object NetworkDiagnostic {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun checkInternet(): DiagnosticResult = withContext(Dispatchers.IO) {
        try {
            val start = System.currentTimeMillis()
            val request = Request.Builder()
                .url("https://clients3.google.com/generate_204")
                .header("User-Agent", "IPTV Player TV/1.0")
                .build()

            val response = client.newCall(request).execute()
            val latency = System.currentTimeMillis() - start
            response.close()

            DiagnosticResult(ok = response.code == 204 || response.isSuccessful, latencyMs = latency)
        } catch (e: Exception) {
            DiagnosticResult(ok = false)
        }
    }

    suspend fun checkProvider(serverUrl: String, username: String, password: String): DiagnosticResult =
        withContext(Dispatchers.IO) {
            try {
                val baseUrl = serverUrl.trimEnd('/')
                val url = "$baseUrl/player_api.php?username=$username&password=$password"

                val start = System.currentTimeMillis()
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "IPTV Player TV/1.0")
                    .build()

                val response = client.newCall(request).execute()
                val latency = System.currentTimeMillis() - start
                response.close()

                DiagnosticResult(ok = response.isSuccessful, latencyMs = latency)
            } catch (e: Exception) {
                DiagnosticResult(ok = false)
            }
        }

    suspend fun runFullDiagnostic(
        serverUrl: String,
        username: String,
        password: String
    ): FullDiagnosticResult = coroutineScope {
        val internetDeferred = async { checkInternet() }
        val providerDeferred = async { checkProvider(serverUrl, username, password) }

        val internet = internetDeferred.await()
        val provider = providerDeferred.await()

        val diagnosis = when {
            !internet.ok && !provider.ok -> DiagnosisType.BOTH_DOWN
            !internet.ok -> DiagnosisType.INTERNET_DOWN
            !provider.ok -> DiagnosisType.PROVIDER_DOWN
            else -> DiagnosisType.ALL_OK
        }

        FullDiagnosticResult(internet, provider, diagnosis)
    }
}
