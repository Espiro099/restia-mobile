package com.restia.mobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Pone un [UnconfinedTestDispatcher] como `Dispatchers.Main` durante la prueba y, al terminar,
 * cancela los ViewModels registrados con [track] y espera a que sus corrutinas acaben antes de
 * restaurarlo.
 *
 * Las corrutinas de `viewModelScope` se reanudan en hilos de OkHttp o de `Dispatchers.IO` y desde
 * ahí leen `Dispatchers.Main`. Si una de esas lecturas coincide con `setMain`/`resetMain`,
 * kotlinx-coroutines-test lanza "Dispatchers.Main is used concurrently with setting it", a veces
 * hasta el `setMain` de la prueba siguiente. Con el Job de cada ViewModel ya terminado, nadie lee
 * `Dispatchers.Main` mientras se cambia, sin depender de cuánto tarde el hilo de red.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule : TestWatcher() {
    private val viewModels = mutableListOf<ViewModel>()

    /** Registra el ViewModel para cancelarlo al final de la prueba. */
    fun <T : ViewModel> track(viewModel: T): T = viewModel.also { viewModels += it }

    override fun starting(description: Description) = Dispatchers.setMain(UnconfinedTestDispatcher())

    override fun finished(description: Description) {
        try {
            val jobs = viewModels.map { vm -> vm.viewModelScope.also { it.cancel() }.coroutineContext.job }
            runBlocking { withTimeout(10_000) { jobs.joinAll() } }
        } finally {
            viewModels.clear()
            Dispatchers.resetMain()
        }
    }
}
