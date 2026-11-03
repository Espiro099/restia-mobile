package com.restia.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfiguracionTest {
    @Test
    fun paqueteDeLaAplicacion() {
        assertEquals("com.restia.mobile", MainActivity::class.java.`package`?.name)
    }
}
