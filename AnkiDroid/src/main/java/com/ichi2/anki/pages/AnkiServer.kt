// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: Copyright (c) 2022 Mani <infinyte01@gmail.com>

package com.ichi2.anki.pages

import android.content.res.AssetManager
import com.ichi2.utils.AssetHelper.guessMimeType
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

open class AnkiServer(
    private val postHandler: PostRequestHandler,
    port: Int = 0,
    private val getAssetManager: (() -> AssetManager?)? = null,
) : NanoHTTPD(LOCALHOST, port) {
    fun baseUrl(): String = "http://$LOCALHOST:$listeningPort/"

    // it's faster to serve local files without GZip. see 'page render' in logs
    // This also removes 'W/System: A resource failed to call end.'
    override fun useGzipWhenAccepted(r: Response?) = false

    override fun serve(session: IHTTPSession): Response =
        when (session.method) {
            Method.POST -> {
                val uri = session.uri
                Timber.d("POST: Requested %s", uri)
                val inputBytes = getSessionBytes(session)

                try {
                    val data = runBlocking { postHandler.handlePostRequest(PostRequestUri(uri), inputBytes) }
                    buildResponse(data)
                } catch (exception: Exception) {
                    Timber.w(exception, "buildResponse failure")
                    buildResponse(exception.localizedMessage?.encodeToByteArray(), status = Response.Status.INTERNAL_ERROR)
                }
            }
            Method.GET -> {
                Timber.d("GET: Requested %s", session.uri)
                servePageGetRequest(session.uri)
                    ?: run {
                        Timber.d("Rejecting GET request to server %s", session.uri)
                        newFixedLengthResponse(Response.Status.NOT_FOUND, null, null)
                    }
            }
            else -> {
                Timber.d("Ignored request of unhandled method %s, uri %s", session.method, session.uri)
                newFixedLengthResponse(null)
            }
        }

    private fun buildResponse(
        data: ByteArray?,
        mimeType: String = "application/binary",
        status: Response.IStatus = Response.Status.OK,
    ): Response =
        if (data == null) {
            newFixedLengthResponse(null)
        } else {
            newChunkedResponse(status, mimeType, ByteArrayInputStream(data))
        }

    private fun buildResponse(
        inputStream: InputStream,
        mimeType: String,
        status: Response.IStatus = Response.Status.OK,
    ): Response = newChunkedResponse(status, mimeType, inputStream)

    private fun servePageGetRequest(uri: String): Response? {
        val assetManager = getAssetManager?.invoke() ?: return null
        val path = uri.substringBefore("?")

        if (path == "/favicon.png") {
            return buildResponse(byteArrayOf(), mimeType = "image/x-icon")
        }

        val assetPath = resolveAssetPath(path) ?: return null
        return try {
            val response =
                if (assetPath == "backend/sveltekit/index.html") {
                    val html =
                        assetManager
                            .open(assetPath)
                            .bufferedReader(Charsets.UTF_8)
                            .use { it.readText() }
                    val patchedHtml = injectLegacyWebViewPolyfills(html)
                    buildResponse(patchedHtml.toByteArray(Charsets.UTF_8), mimeType = "text/html")
                } else {
                    val mimeType = guessMimeType(assetPath)
                    val inputStream = assetManager.open(assetPath)
                    buildResponse(inputStream, mimeType)
                }
            response.apply {
                if ("immutable" in path) {
                    addHeader("Cache-Control", "max-age=31536000")
                }
            }
        } catch (e: IOException) {
            Timber.w(e, "Not found %s", assetPath)
            null
        }
    }

    private fun resolveAssetPath(path: String): String? =
        when {
            path.startsWith("/_app/") -> "backend/sveltekit/app/${path.substring(6)}"
            isSvelteKitPage(path.removePrefix("/")) -> "backend/sveltekit/index.html"
            else -> null
        }

    companion object {
        const val LOCALHOST = "127.0.0.1"

        /** Common prefix used on Anki requests */
        const val ANKI_PREFIX = "/_anki/"
        const val ANKIDROID_PREFIX = "/ankidroid/"
        const val ANKIDROID_JS_PREFIX = "/jsapi/"

        fun getSessionBytes(session: IHTTPSession): ByteArray {
            val contentLength =
                session.headers["content-length"]?.toIntOrNull()
                    ?: session.headers["Content-Length"]?.toIntOrNull()

            if (contentLength == null || contentLength < 0) {
                Timber.w("Missing or invalid content-length header for uri %s, reading until EOF", session.uri)
                return session.inputStream.readBytes()
            }

            val bytes = ByteArray(contentLength)
            var offset = 0
            while (offset < contentLength) {
                val read = session.inputStream.read(bytes, offset, contentLength - offset)
                if (read < 0) {
                    Timber.w(
                        "Unexpected EOF while reading POST body for uri %s (%d/%d bytes)",
                        session.uri,
                        offset,
                        contentLength,
                    )
                    break
                }
                offset += read
            }

            if (offset == contentLength) {
                return bytes
            }

            return bytes.copyOf(offset)
        }
    }
}
