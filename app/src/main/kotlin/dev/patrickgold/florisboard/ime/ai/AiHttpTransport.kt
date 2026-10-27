/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * @property body request body for POST requests; null for GET.
 */
class AiHttpRequest(
    val url: String,
    val headers: Map<String, String>,
    val body: String?,
    val connectTimeoutMs: Int,
    val readTimeoutMs: Int,
    val maxResponseBytes: Int,
    val method: String = "POST",
)

/**
 * @property body the response body. Only populated for 2xx responses; error bodies are never read, so
 * provider error text (which may echo request content) cannot leak into the UI or logs.
 */
class AiHttpResponse(
    val status: Int,
    val body: String,
)

/**
 * Thrown by a transport when the response exceeds [AiHttpRequest.maxResponseBytes].
 */
class AiResponseTooLargeException : IOException("Response too large")

/**
 * Minimal HTTP seam so the provider logic can be tested without a network.
 *
 * Implementations throw [IOException] (or a subclass such as `SocketTimeoutException`) for transport
 * failures and must abort promptly when the calling coroutine is cancelled.
 */
interface AiHttpTransport {
    suspend fun send(request: AiHttpRequest): AiHttpResponse
}

/**
 * Default transport built on the JDK's [HttpURLConnection], so no extra networking dependency is needed.
 */
class HttpUrlConnectionTransport : AiHttpTransport {
    override suspend fun send(request: AiHttpRequest): AiHttpResponse = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            val connection = URL(request.url).openConnection() as HttpURLConnection
            // The blocking read below cannot observe cancellation by itself. Disconnecting makes it throw,
            // and the result is then ignored because the continuation is already cancelled. The disconnect
            // runs on its own thread because it can touch the socket and the canceller may be the main thread.
            cont.invokeOnCancellation {
                Thread { runCatching { connection.disconnect() } }.start()
            }
            try {
                cont.resume(execute(connection, request))
            } catch (e: Throwable) {
                cont.resumeWithException(e)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun execute(connection: HttpURLConnection, request: AiHttpRequest): AiHttpResponse {
        connection.requestMethod = request.method
        connection.connectTimeout = request.connectTimeoutMs
        connection.readTimeout = request.readTimeoutMs
        // Never follow redirects: that would forward the credentials to another host.
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        for ((name, value) in request.headers) {
            connection.setRequestProperty(name, value)
        }
        if (request.body != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
        }

        val status = connection.responseCode
        if (status !in 200..299) {
            return AiHttpResponse(status, "")
        }
        val out = ByteArrayOutputStream()
        connection.inputStream.use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (out.size() + read > request.maxResponseBytes) throw AiResponseTooLargeException()
                out.write(buffer, 0, read)
            }
        }
        return AiHttpResponse(status, out.toString(Charsets.UTF_8.name()))
    }
}
