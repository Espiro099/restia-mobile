package com.restia.mobile.core.network

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * Cliente de las fotos del menú. Va aparte del de la API a propósito: las fotos pueden estar en otro host
 * (un CDN), así que no lleva el token de sesión ni los encabezados de la app. Si la foto está en el host de
 * la API, el anclaje de certificados sigue aplicando.
 */
object ImageClient {
    /** Solo HTTPS, igual que valida el backend (`url:https`): nada de http, data: ni otros esquemas. */
    fun isAllowed(url: String?): Boolean = url?.toHttpUrlOrNull()?.isHttps == true

    fun build(baseUrl: String, pins: List<String>, pinsRequired: Boolean): OkHttpClient =
        CertificatePinning.apply(OkHttpClient.Builder(), baseUrl, pins, pinsRequired)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            // Una redirección tampoco puede bajar a HTTP.
            .followSslRedirects(false)
            .addNetworkInterceptor(HttpsOnly())
            .build()

    /** Corta cualquier petición que no sea HTTPS, también las que llegan por redirección. */
    class HttpsOnly : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val url = chain.request().url
            if (!url.isHttps) throw IOException("Foto rechazada: solo se cargan por HTTPS.")
            return chain.proceed(chain.request())
        }
    }
}
