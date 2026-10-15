package com.ersaz.hermesmobile.sandbox

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.TimeUnit

/** Cancellation must stop the child before callers delete its temporary output. */
suspend fun waitForCommand(process: Process, timeoutSeconds: Long): Boolean = try {
    runInterruptible { process.waitFor(timeoutSeconds, TimeUnit.SECONDS) }.also { finished ->
        if (!finished) process.destroyForcibly()
    }
} catch (e: CancellationException) {
    process.destroyForcibly()
    runCatching { process.inputStream.close() }
    runCatching { process.errorStream.close() }
    throw e
}
