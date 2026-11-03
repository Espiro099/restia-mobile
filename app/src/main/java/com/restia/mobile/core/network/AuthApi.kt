package com.restia.mobile.core.network

import retrofit2.Call
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

/** Operaciones sin Bearer: alta, login y renovación. */
interface PublicAuthApi {
    @POST("auth/mobile/enroll")
    suspend fun enroll(@Body body: DeviceEnrollRequest): Envelope<DeviceEnrollData>

    @POST("auth/mobile/login")
    suspend fun login(@Body body: MobileLoginRequest): Envelope<TokenData>

    @POST("auth/mobile/refresh")
    fun refresh(@Body body: MobileRefreshRequest): Call<Envelope<TokenData>>

    @POST("auth/logout")
    suspend fun logoutWith(@Header("Authorization") bearer: String): Response<Unit>
}

/** Operaciones con Bearer; pasan por el interceptor y el authenticator. */
interface SessionApi {
    @GET("auth/me")
    suspend fun me(): Envelope<MeData>

    @POST("auth/logout")
    suspend fun logout(): Response<Unit>
}
