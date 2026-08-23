package com.personal.smsforwarder.forwarder

import com.personal.smsforwarder.core.TemplateRenderer
import com.personal.smsforwarder.model.ForwardRequest
import com.personal.smsforwarder.model.ForwarderConfig
import com.personal.smsforwarder.model.HttpMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * POST/PUT/GET to a configured URL. Values substituted into a JSON body template are
 * JSON-string-escaped, so a message body containing quotes or newlines can't produce
 * malformed JSON.
 */
class HttpForwarder(
    private val client: OkHttpClient = defaultClient(),
) : Forwarder {

    override suspend fun send(
        request: ForwardRequest,
        config: ForwarderConfig,
        onProgress: (String) -> Unit,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            val c = config as ForwarderConfig.Http
            if (c.url.isBlank()) {
                return@withContext Result.failure(ForwarderConfigException("No URL configured"))
            }
            runCatching {
                val rendered = TemplateRenderer.render(
                    c.bodyTemplate, request, TemplateRenderer.escapingFor(c.contentType)
                )

                val body = when (c.method) {
                    HttpMethod.GET -> null
                    HttpMethod.POST, HttpMethod.PUT ->
                        rendered.toRequestBody(c.contentType.toMediaTypeOrNull())
                }

                onProgress("${c.method} ${c.url.take(48)}")
                val builder = Request.Builder().url(c.url).method(c.method.name, body)
                c.headers.filter { it.name.isNotBlank() }.forEach { builder.header(it.name, it.value) }

                client.newCall(builder.build()).execute().use { response ->
                    val snippet = response.body?.string().orEmpty().take(200).replace('\n', ' ')
                    if (!response.isSuccessful) {
                        throw java.io.IOException("HTTP ${response.code} ${response.message} $snippet".trim())
                    }
                    "HTTP ${response.code}" + if (snippet.isBlank()) "" else " — $snippet"
                }
            }
        }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
