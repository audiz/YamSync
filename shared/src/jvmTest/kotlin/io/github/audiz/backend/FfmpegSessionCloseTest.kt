package io.github.audiz.backend

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertTrue

class FfmpegSessionCloseTest {
    @Test
    fun stoppingSessionDoesNotWaitForBlockedDecoderPipe() {
        val permitClose = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val destroyed = AtomicBoolean(false)
        val killedBeforeClosing = AtomicBoolean(false)
        val process = object : Process() {
            override fun getOutputStream() = ByteArrayOutputStream()
            override fun getErrorStream() = InputStream.nullInputStream()
            override fun getInputStream() = object : InputStream() {
                override fun read() = -1
                override fun close() {
                    killedBeforeClosing.set(destroyed.get())
                    closing.countDown()
                    permitClose.await(10, TimeUnit.SECONDS)
                }
            }
            override fun waitFor() = 0
            override fun exitValue() = 0
            override fun destroy() { destroyed.set(true) }
            override fun destroyForcibly(): Process { destroy(); return this }
        }
        val session = FfmpegAudioPumpBackend.AudioSession(id = 1L)
        session.proc = process
        session.pcmQueue.add(ByteArray(4))
        val caller = Executors.newSingleThreadExecutor()
        try {
            val stopped = caller.submit { session.close() }
            stopped.get(2, TimeUnit.SECONDS)
            assertTrue(session.isClosed)
            assertTrue(session.pcmQueue.isEmpty())
            assertTrue(closing.await(2, TimeUnit.SECONDS))
            assertTrue(killedBeforeClosing.get())
        } finally {
            permitClose.countDown()
            caller.shutdownNow()
        }
    }
}
