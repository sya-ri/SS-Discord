package me.syari.ss.discord.internal.requests

import me.syari.ss.discord.api.exceptions.ErrorResponseException
import me.syari.ss.discord.api.exceptions.RateLimitedException
import me.syari.ss.discord.api.requests.Request
import me.syari.ss.discord.api.requests.Response
import me.syari.ss.discord.api.requests.RestFuture
import me.syari.ss.discord.internal.JDA
import me.syari.ss.discord.internal.requests.CallbackContext.Companion.isCallbackContext
import okhttp3.RequestBody
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.function.BiFunction
import java.util.function.Consumer

open class RestAction<T>(
    val jda: JDA, private val route: Route?, private val handler: BiFunction<Response, Request<T>, T>?
) {
    constructor(api: JDA, route: Route?): this(api, route, null)

    fun queue() {
        val route = finalizeRoute()
        val data = finalizeData()
        jda.requester.request(
            Request(
                this, DEFAULT_SUCCESS, DEFAULT_FAILURE, true, data, route!!
            )
        )
    }

    private fun submit(shouldQueue: Boolean): CompletableFuture<T> {
        val route = finalizeRoute()
        val data = finalizeData()
        return RestFuture(this, shouldQueue, data, route!!)
    }

    fun complete(): T {
        return try {
            complete(true)
        } catch (e: RateLimitedException) {
            throw AssertionError(e)
        }
    }

    @Throws(RateLimitedException::class)
    fun complete(shouldQueue: Boolean): T {
        check(!isCallbackContext) { "Preventing use of complete() in callback threads! This operation can be a deadlock cause" }
        return try {
            submit(shouldQueue).get()
        } catch (e: Throwable) {
            if (e is ExecutionException) {
                val t = e.cause
                if (t is RateLimitedException) {
                    throw (t as RateLimitedException?)!!
                } else if (t is ErrorResponseException) {
                    throw (t as ErrorResponseException?)!!
                }
            }
            throw RuntimeException(e)
        }
    }

    protected open fun finalizeData(): RequestBody? {
        return null
    }

    protected fun finalizeRoute(): Route? {
        return route
    }

    open fun handleResponse(
        response: Response, request: Request<T>
    ) {
        if (response.isOk) {
            handleSuccess(response, request)
        } else {
            request.onFailure(response)
        }
    }

    protected open fun handleSuccess(
        response: Response, request: Request<T>
    ) {
        if (handler == null) {
            request.onSuccess(null)
        } else {
            request.onSuccess(handler.apply(response, request))
        }
    }

    companion object {
        private val DEFAULT_SUCCESS = Consumer { o: Any? -> }
        private val DEFAULT_FAILURE: Consumer<in Throwable> = Consumer { t: Throwable? -> }
    }
}