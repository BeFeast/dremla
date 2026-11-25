package com.befeast.sleepmusic.playback.testing

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler

/** Standalone virtual-time scope: unlike TestScope, errors are checked without requiring runTest. */
class CheckedTestScope : CoroutineScope {
    val testScheduler = TestCoroutineScheduler()
    private val failures = mutableListOf<Throwable>()
    override val coroutineContext = SupervisorJob() + StandardTestDispatcher(testScheduler) +
        CoroutineExceptionHandler { _, error -> failures += error }

    fun assertHealthy() {
        if (failures.isNotEmpty()) {
            throw AssertionError("Uncaught test coroutine failure", failures.first()).also { failure ->
                failures.drop(1).forEach(failure::addSuppressed)
            }
        }
    }
}
