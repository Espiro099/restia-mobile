package com.restia.mobile

import com.restia.mobile.core.network.CertificatePinning
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Actividad 3.2: el anclaje acepta solo el certificado cuya llave pública coincide con la huella, aunque
 * el teléfono confíe en la CA del atacante (el caso de un proxy MitM con su CA instalada).
 */
class CertificatePinningTest {
    private val localIp = "127.0.0.1" // MockWebServer escucha en 127.0.0.1
    private val legitimate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").addSubjectAlternativeName(localIp).build()
    private val attacker = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").addSubjectAlternativeName(localIp).build()
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
    }

    @After fun tearDown() = server.shutdown()

    private fun serve(cert: HeldCertificate) {
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(cert).build()
        server.useHttps(serverCerts.sslSocketFactory(), false)
        server.enqueue(MockResponse().setBody("{\"ok\":true}"))
        server.start()
    }

    /** Cliente que confía en las dos CA (como un teléfono con la CA del proxy instalada) y lleva el anclaje. */
    private fun client(pins: List<String>): OkHttpClient {
        val trusted = HandshakeCertificates.Builder()
            .addTrustedCertificate(legitimate.certificate)
            .addTrustedCertificate(attacker.certificate)
            .build()
        val builder = OkHttpClient.Builder().sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager)
        return CertificatePinning.apply(builder, server.url("/api/v1/").toString(), pins, required = true).build()
    }

    private fun get(client: OkHttpClient) = client.newCall(Request.Builder().url(server.url("/api/v1/up")).build()).execute()

    @Test fun con_la_huella_correcta_se_conecta() {
        serve(legitimate)
        val response = get(client(listOf(CertificatePinner.pin(legitimate.certificate))))
        assertEquals(200, response.code)
        assertEquals("TLSv1.3", response.handshake?.tlsVersion?.javaName ?: "TLSv1.3")
    }

    @Test fun un_certificado_de_otra_llave_se_rechaza_aunque_sea_de_confianza() {
        serve(attacker)
        val error = assertThrows(SSLPeerUnverifiedException::class.java) {
            get(client(listOf(CertificatePinner.pin(legitimate.certificate))))
        }
        assertTrue(error.message!!.contains("Certificate pinning failure"))
    }

    @Test fun la_huella_de_respaldo_permite_rotar_el_certificado() {
        serve(attacker)
        val response = get(client(listOf(CertificatePinner.pin(legitimate.certificate), CertificatePinner.pin(attacker.certificate))))
        assertEquals(200, response.code)
    }

    @Test fun en_release_sin_https_o_sin_huellas_falla_cerrado() {
        assertThrows(IllegalStateException::class.java) {
            CertificatePinning.apply(OkHttpClient.Builder(), "http://api.restia.com/api/v1/", listOf("sha256/x"), required = true)
        }
        assertThrows(IllegalStateException::class.java) {
            CertificatePinning.apply(OkHttpClient.Builder(), "https://api.restia.com/api/v1/", emptyList(), required = true)
        }
        // En debug, el backend local por HTTP sigue funcionando.
        CertificatePinning.apply(OkHttpClient.Builder(), "http://127.0.0.1:8000/api/v1/", emptyList(), required = false)
    }

    @Test fun las_huellas_se_leen_de_la_configuracion() {
        assertEquals(
            listOf("sha256/QJon1gO99NvFbJL2a054X3MyNORUzfyxP3JiGQVYj8s=", "sha256/BBB="),
            CertificatePinning.parse(" QJon1gO99NvFbJL2a054X3MyNORUzfyxP3JiGQVYj8s= , sha256/BBB=,"),
        )
        assertEquals(emptyList<String>(), CertificatePinning.parse(""))
    }
}
