package com.drivemap.app.net

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

class HttpException(val code: Int, val body: String) : IOException("HTTP $code: ${body.take(200)}")

object Http {
    /** Identifies the app to the public map services (their usage policies ask for this). */
    const val USER_AGENT = "DriveMap/1.0 (Android navigation app; https://github.com/martin6727/drivemap)"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** [totalTimeoutSec]: hard limit for the whole call (default: read timeout + 15 s). */
    suspend fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutSec: Long = 0, totalTimeoutSec: Long = 0): String {
        val b = Request.Builder().url(url).header("User-Agent", USER_AGENT)
        headers.forEach { (k, v) -> b.header(k, v) }
        return execute(b.build(), timeoutSec, totalTimeoutSec)
    }

    /** Binary download (e.g. a spreadsheet). */
    suspend fun getBytes(url: String, timeoutSec: Long = 60): ByteArray {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        val c = client.newBuilder().readTimeout(timeoutSec, TimeUnit.SECONDS).callTimeout(timeoutSec + 15, TimeUnit.SECONDS).build()
        return suspendCancellableCoroutine { cont ->
            val call = c.newCall(req)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            val body = it.body?.bytes() ?: ByteArray(0)
                            if (!it.isSuccessful) cont.resumeWithException(HttpException(it.code, ""))
                            else cont.resumeWith(Result.success(body))
                        } catch (e: Exception) {
                            if (cont.isActive) cont.resumeWithException(e)
                        }
                    }
                }
            })
        }
    }

    suspend fun postJson(url: String, json: String, timeoutSec: Long = 0): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .post(json.toRequestBody(JSON))
            .build()
        return execute(req, timeoutSec)
    }

    suspend fun postForm(url: String, form: Map<String, String>, timeoutSec: Long = 0): String {
        val fb = FormBody.Builder()
        form.forEach { (k, v) -> fb.add(k, v) }
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).post(fb.build()).build()
        return execute(req, timeoutSec)
    }

    private suspend fun execute(request: Request, timeoutSec: Long, totalTimeoutSec: Long = 0): String {
        val c = if (timeoutSec > 0) client.newBuilder()
            .readTimeout(timeoutSec, TimeUnit.SECONDS)
            .connectTimeout(minOf(15L, timeoutSec), TimeUnit.SECONDS)
            .callTimeout(if (totalTimeoutSec > 0) totalTimeoutSec else timeoutSec + 15, TimeUnit.SECONDS)
            .build() else client
        return suspendCancellableCoroutine { cont ->
            val call = c.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            val body = it.body?.string() ?: ""
                            if (!it.isSuccessful) {
                                cont.resumeWithException(HttpException(it.code, body))
                            } else {
                                cont.resumeWith(Result.success(body))
                            }
                        } catch (e: Exception) {
                            if (cont.isActive) cont.resumeWithException(e)
                        }
                    }
                }
            })
        }
    }
}
