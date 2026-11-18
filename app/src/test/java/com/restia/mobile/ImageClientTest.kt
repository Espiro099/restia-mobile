package com.restia.mobile

import com.restia.mobile.core.network.ImageClient
import java.io.IOException
import javax.net.ssl.SSLPeerUnverifiedException
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fotos del menú: solo HTTPS, sin la sesión y con el anclaje del host de la API. */
class ImageClientTest {
    private val cert = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
    private val other = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
    private val server = MockWebServer()

    @After fun tearDown() = server.shutdown()

    private fun https(held: HeldCertificate = cert) {
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(held).build().sslSocketFactory(), false)
    }

    /** El cliente de fotos, confiando además en los certificados de prueba (como haría la CA del sistema). */
    private fun client(pins: List<String>, base: String = server.url("/api/v1/").toString()): OkHttpClient {
        val trusted = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).addTrustedCertificate(other.certificate).build()
        return ImageClient.build(base, pins, pinsRequired = pins.isNotEmpty())
            .newBuilder().sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager).build()
    }

    private fun get(client: OkHttpClient, path: String = "/foto.jpg") =
        client.newCall(Request.Builder().url(server.url(path)).build()).execute()

    @Test fun solo_se_aceptan_direcciones_https() {
        assertTrue(ImageClient.isAllowed("https://cdn.restia.mx/platos/tacos.jpg"))
        assertFalse(ImageClient.isAllowed("http://cdn.restia.mx/platos/tacos.jpg"))
        assertFalse(ImageClient.isAllowed("javascript:alert(1)"))
        assertFalse(ImageClient.isAllowed("data:image/png;base64,AAAA"))
        assertFalse(ImageClient.isAllowed(""))
        assertFalse(ImageClient.isAllowed(null))
    }

    @Test fun una_foto_por_http_se_rechaza_sin_salir_a_la_red() {
        server.enqueue(MockResponse().setBody("x"))
        server.start()
        assertThrows(IOException::class.java) { get(client(emptyList(), base = "http://127.0.0.1/api/v1/")) }
        assertEquals(0, server.requestCount)
    }

    @Test fun la_foto_no_lleva_el_token_de_sesion() {
        https()
        server.enqueue(MockResponse().setBody("x"))
        server.start()
        assertEquals(200, get(client(listOf(CertificatePinner.pin(cert.certificate)))).code)
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test fun en_el_host_de_la_api_aplica_el_anclaje() {
        https(other)
        server.enqueue(MockResponse().setBody("x"))
        server.start()
        assertThrows(SSLPeerUnverifiedException::class.java) { get(client(listOf(CertificatePinner.pin(cert.certificate)))) }
    }

    @Test fun una_redireccion_a_http_no_se_sigue() {
        https()
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://127.0.0.1:1/foto.jpg"))
        server.start()
        assertEquals(302, get(client(listOf(CertificatePinner.pin(cert.certificate)))).code)
        assertEquals(1, server.requestCount)
    }
}
