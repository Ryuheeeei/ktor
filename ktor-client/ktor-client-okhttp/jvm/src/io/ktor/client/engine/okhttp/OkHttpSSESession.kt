/*
 * Copyright 2014-2024 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.okhttp

import io.ktor.client.plugins.sse.*
import io.ktor.http.*
import io.ktor.sse.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.onCompletion
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import kotlin.coroutines.CoroutineContext

internal class OkHttpSSESession private constructor(
    factory: EventSource.Factory,
    engineRequest: Request,
    override val coroutineContext: CoroutineContext,
) : SSESession, EventSourceListener() {

    constructor(
        engine: OkHttpClient,
        engineRequest: Request,
        callContext: CoroutineContext,
    ) : this(
        factory = EventSources.createFactory(engine),
        engineRequest = engineRequest,
        coroutineContext = callContext + Job() + CoroutineName("OkHttpSSESession"),
    )

    private val serverSentEventsSource = factory.newEventSource(engineRequest, this)

    internal val originResponse: CompletableDeferred<Response> = CompletableDeferred()

    private val _incoming = Channel<ServerSentEvent>(8)

    override val incoming: Flow<ServerSentEvent> = _incoming.consumeAsFlow()
        .onCompletion { cause ->
            // Use onCompletion operator to handle CancellationExceptions which occur in downstream flow.
            if (cause is CancellationException) close(cause = null)
        }

    init {
        coroutineContext.job.invokeOnCompletion {
            close(cause = null)
        }
    }

    override fun onOpen(eventSource: EventSource, response: Response) {
        originResponse.complete(response)
    }

    override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
        _incoming.trySendBlocking(ServerSentEvent(data, type, id))
            .onFailure { if (it is CancellationException) throw it }
    }

    override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
        val statusCode = response?.code
        val contentType = response?.headers?.get(HttpHeaders.ContentType)

        if (response != null &&
            (statusCode != HttpStatusCode.OK.value || contentType != ContentType.Text.EventStream.toString())
        ) {
            originResponse.complete(response)
            close(cause = null)
        } else {
            val error = t?.let {
                SSEClientException(
                    message = "Exception during OkHttpSSESession: ${it.message}",
                    cause = it
                )
            } ?: mapException(response)
            originResponse.completeExceptionally(error)
            close(cause = error)
        }
    }

    override fun onClosed(eventSource: EventSource) {
        close(cause = null)
    }

    private fun close(cause: Throwable?) {
        _incoming.close(cause)
        serverSentEventsSource.cancel()
        // Cancel context last so 'invokeOnCompletion' doesn't override 'cause' for closing '_incoming'.
        coroutineContext.cancel()
    }

    private fun mapException(response: Response?): SSEClientException {
        fun unexpectedError() = SSEClientException(message = "Unexpected error occurred in OkHttpSSESession")

        return when {
            response == null -> unexpectedError()

            response.code != HttpStatusCode.OK.value ->
                SSEClientException(message = "Expected status code ${HttpStatusCode.OK.value} but was ${response.code}")

            response.headers[HttpHeaders.ContentType]
                ?.let { ContentType.parse(it) }?.withoutParameters() != ContentType.Text.EventStream ->
                @Suppress("ktlint:standard:max-line-length")
                SSEClientException(
                    message = "Content type must be ${ContentType.Text.EventStream} but was ${response.headers[HttpHeaders.ContentType]}"
                )

            else -> unexpectedError()
        }
    }
}
