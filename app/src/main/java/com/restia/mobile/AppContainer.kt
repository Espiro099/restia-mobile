package com.restia.mobile

import android.content.Context
import com.restia.mobile.core.network.BearerInterceptor
import com.restia.mobile.core.network.PublicAuthApi
import com.restia.mobile.core.network.RequestIdInterceptor
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.core.network.TokenAuthenticator
import com.restia.mobile.core.network.ApiJson
import com.restia.mobile.core.security.AccessTokenHolder
import com.restia.mobile.core.security.CredentialStore
import com.restia.mobile.core.security.KeystoreCredentialStore
import com.restia.mobile.data.auth.AuthRepository
import com.restia.mobile.data.auth.AuthStateHolder
import com.restia.mobile.data.auth.TokenRefresher
import com.restia.mobile.data.tables.TablesRepository
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Une las dependencias. No registra cuerpos de peticiones: llevan contraseñas y tokens. */
class Repositories(val auth: AuthRepository, val tables: TablesRepository)

fun buildRepositories(baseUrl: String, store: CredentialStore): Repositories {
    val accessToken = AccessTokenHolder()
    val state = AuthStateHolder()
    val converter = ApiJson.asConverterFactory("application/json".toMediaType())
    val base = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(RequestIdInterceptor())
        .build()
    val publicApi = Retrofit.Builder().baseUrl(baseUrl).client(base).addConverterFactory(converter).build()
        .create(PublicAuthApi::class.java)
    val refresher = TokenRefresher(store, accessToken, publicApi, state)
    val sessionClient = base.newBuilder()
        .addInterceptor(BearerInterceptor(accessToken, refresher))
        .authenticator(TokenAuthenticator(refresher))
        .build()
    val sessionApi = Retrofit.Builder().baseUrl(baseUrl).client(sessionClient).addConverterFactory(converter).build()
        .create(SessionApi::class.java)
    return Repositories(AuthRepository(store, accessToken, publicApi, sessionApi, refresher, state), TablesRepository(sessionApi))
}

class AppContainer(context: Context) {
    val repositories = buildRepositories(BuildConfig.API_BASE_URL, KeystoreCredentialStore(context.applicationContext))
}
