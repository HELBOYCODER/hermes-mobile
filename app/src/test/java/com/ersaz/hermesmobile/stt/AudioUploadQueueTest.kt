package com.ersaz.hermesmobile.stt

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AudioUploadQueueTest {
    @Test fun finishDrainsAudioThenEndsUpload() {
        val q = AudioUploadQueue()
        q.put(byteArrayOf(1, 2))
        q.finish()
        assertArrayEquals(byteArrayOf(1, 2), q.take())
        assertNull(q.take())
    }
    @Test fun cancellingUnblocksWaitingUploadWithoutNullInsertion() {
        val q = AudioUploadQueue()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val waiting = executor.submit<ByteArray?> { q.take() }
            q.cancel()
            assertNull(waiting.get(1, TimeUnit.SECONDS))
        } finally { executor.shutdownNow() }
    }
    @Test fun cancellationDiscardsQueuedAudio() {
        val q = AudioUploadQueue()
        q.put(byteArrayOf(1))
        q.cancel()
        assertNull(q.take())
    }
}
