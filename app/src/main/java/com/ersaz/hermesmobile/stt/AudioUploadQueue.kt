package com.ersaz.hermesmobile.stt

import java.util.concurrent.LinkedBlockingQueue

/** Empty buffer is the end marker; Java blocking queues reject null elements. */
internal class AudioUploadQueue {
    private val queue = LinkedBlockingQueue<ByteArray>()
    fun put(bytes: ByteArray) { require(bytes.isNotEmpty()); queue.put(bytes) }
    fun take(): ByteArray? = queue.take().takeIf { it.isNotEmpty() }
    fun finish() { queue.offer(ByteArray(0)) }
    fun cancel() { queue.clear(); finish() }
}
