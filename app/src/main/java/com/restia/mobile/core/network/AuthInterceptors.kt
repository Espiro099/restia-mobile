package com.restia.mobile.core.network

import com.restia.mobile.core.security.AccessTokenHolder
import com.restia.mobile.core.storage.ContactTracker
import com.restia.mobile.data.auth.RefreshResult
import com.restia.mobile.data.auth.TokenRefresher
import java.util.UUID
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

class RequestIdInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().header("X-Request-ID", UUID.randomUUID().toString()).build())
}

/** Solo una respuesta exitosa cuenta como contacto con el servidor (A-7). */
class ContactInterceptor(private val contact: ContactTracker) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request()).also { if (it.isSuccessful || it.code == 304) contact.record() }
}

class BearerInterceptor(
    private val accessToken: AccessTokenHolder,
    private val refresher: TokenRefresher,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val token = accessToken.token
        val response = chain.proceed(
            if (token == null) request else request.newBuilder().header("Authorization", "Bearer $token").build(),
        )
        if (response.code == 403) {
            val body = response.peekBody(MAX_ERROR_BYTES).string()
            if (parseError(403, body, null).code == TokenRefresher.DEVICE_BLOCKED) refresher.blocked()
        }
        return response
    }

    private companion object {
        const val MAX_ERROR_BYTES = 4096L
    }
}

/** Ante un 401 renueva y reintenta una sola vez. */
class TokenAuthenticator(private val refresher: TokenRefresher) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.request.header(RETRY_HEADER) != null) return null
        val sent = response.request.header("Authorization")?.removePrefix("Bearer ")
        val result = refresher.refresh(sent) as? RefreshResult.Success ?: return null
        return response.request.newBuilder()
            .header("Authorization", "Bearer ${result.accessToken}")
            .header(RETRY_HEADER, "1")
            .build()
    }

    private companion object {
        const val RETRY_HEADER = "X-Auth-Retry"
    }
}
