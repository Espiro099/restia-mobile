package com.restia.mobile.core.network

import okhttp3.CertificatePinner
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * Anclaje de certificados (Actividad 3.2, OWASP Mobile M5): además de la cadena de confianza normal,
 * la llave pública del servidor debe coincidir con una de las huellas SPKI SHA-256 configuradas. Un
 * certificado emitido por otra CA, aunque el teléfono confíe en ella (proxy de intercepción en una red
 * pública, CA instalada por el usuario), se rechaza con SSLPeerUnverifiedException.
 *
 * Las huellas se obtienen con:
 * `openssl x509 -in cert.crt -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | openssl enc -base64`
 * Se configuran al menos dos (la actual y una de respaldo) para poder rotar el certificado sin
 * publicar otra versión de la app.
 */
object CertificatePinning {
    /** Huellas de BuildConfig.API_CERT_PINS: "sha256/AAA=,sha256/BBB=". Vacía si no hay. */
    fun parse(pins: String): List<String> =
        pins.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { if (it.startsWith("sha256/")) it else "sha256/$it" }

    /**
     * Aplica el anclaje al host de [baseUrl]. Sin HTTPS no hay certificado que anclar: en release eso es
     * un error de configuración y se falla cerrado; en debug se permite el backend local por HTTP.
     */
    fun apply(builder: OkHttpClient.Builder, baseUrl: String, pins: List<String>, required: Boolean): OkHttpClient.Builder {
        val url = baseUrl.toHttpUrl()
        if (!url.isHttps) {
            check(!required) { "La API debe usar HTTPS en release: $baseUrl" }
            return builder
        }
        if (pins.isEmpty()) {
            check(!required) { "Faltan las huellas del certificado de la API (restia.apiPins)." }
            return builder
        }
        val pinner = CertificatePinner.Builder().apply { pins.forEach { add(url.host, it) } }.build()
        return builder.certificatePinner(pinner)
    }
}
