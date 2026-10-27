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

package dev.patrickgold.florisboard.ime.ai.local

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * One open download stream.
 *
 * @property status HTTP status: 200 (full body) or 206 (resumed from the requested offset).
 * @property contentLength number of bytes this response will deliver, or -1 if unknown.
 */
class ModelHttpResponse(
    val status: Int,
    val contentLength: Long,
    val input: InputStream,
    private val onClose: () -> Unit = {},
) : AutoCloseable {
    override fun close() {
        runCatching { input.close() }
        onClose()
    }
}

/**
 * Network seam for model downloads, so the download logic can be tested without a network.
 */
fun interface ModelHttp {
    /**
     * Opens [url], asking the server to start at byte [resumeFrom]. Non-success statuses are returned,
     * not thrown, so the caller can tell "needs login" from "network down". [bearerToken] is only sent
     * to the host of the original URL.
     */
    @Throws(IOException::class)
    fun open(url: String, resumeFrom: Long, bearerToken: String?): ModelHttpResponse
}

/**
 * [ModelHttp] on [HttpURLConnection]. Redirects are followed by hand (up to [MAX_REDIRECTS]) so the
 * token is never forwarded to another host: Hugging Face redirects downloads to a signed CDN URL that
 * must not receive the user's token.
 */
class HttpUrlModelHttp(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : ModelHttp {
    override fun open(url: String, resumeFrom: Long, bearerToken: String?): ModelHttpResponse {
        val originalHost = URL(url).host
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            val currentUrl = URL(current)
            if (!currentUrl.protocol.equals("https", ignoreCase = true)) {
                throw IOException("Refusing non-HTTPS download URL")
            }
            val connection = currentUrl.openConnection() as HttpURLConnection
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            // The file is already compressed, and a range request needs the raw bytes.
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (resumeFrom > 0) connection.setRequestProperty("Range", "bytes=$resumeFrom-")
            if (bearerToken != null && currentUrl.host.equals(originalHost, ignoreCase = true)) {
                connection.setRequestProperty("Authorization", "Bearer $bearerToken")
            }
            val status = connection.responseCode
            if (status in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                current = URL(currentUrl, location ?: throw IOException("Redirect without Location")).toString()
                return@repeat
            }
            if (status !in 200..299) {
                connection.disconnect()
                return ModelHttpResponse(status, -1, ByteArrayInputStream(ByteArray(0)))
            }
            return ModelHttpResponse(
                status = status,
                contentLength = connection.contentLengthLong,
                input = connection.inputStream,
                onClose = { connection.disconnect() },
            )
        }
        throw IOException("Too many redirects")
    }

    private companion object {
        const val MAX_REDIRECTS = 5
    }
}
