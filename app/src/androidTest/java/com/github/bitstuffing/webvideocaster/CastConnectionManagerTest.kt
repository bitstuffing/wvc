package com.github.bitstuffing.webvideocaster

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.bitstuffing.webvideocaster.utils.CastConnectionManager
import com.github.bitstuffing.webvideocaster.utils.CastDevice
import com.github.bitstuffing.webvideocaster.utils.CastSession
import com.github.bitstuffing.webvideocaster.utils.CastUtils
import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CastConnectionManagerTest {

    @Test
    fun playPauseNewSocket_doesNotCrash() = runBlocking {

        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val device: CastDevice? = CastUtils.searchDevices(context).firstOrNull()
        assertNotNull("No Chromecast found", device)

        val session: CastSession? = CastUtils.getSession(device!!)

        assertNotNull("Session is null", session)

        testManager(session!!)
    }

    @Test
    fun playPauseSameSocket_doesNotCrash() = runBlocking {

        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val device: CastDevice? = CastUtils.searchDevices(context).firstOrNull()
        assertNotNull("No Chromecast found", device)

        var session: CastSession? = null

        val latch = CompletableDeferred<Unit>()

        // 1. Start player
        CastUtils.castUrl(
            device!!,
            "https://directo.tuwebtv.es/canal1.m3u8"
        ) { result ->
            session = result
            latch.complete(Unit)
        }

        latch.await()
        assertNotNull("Session is null", session)

        testManager(session!!)
    }

    @Test
    fun reConnectSession_doesNotCrash() = runBlocking {

        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val device = CastUtils.searchDevices(context).firstOrNull()
        assertNotNull("No Chromecast found", device)

        var session: CastSession? = null

        val latch = CompletableDeferred<Unit>()

        // 1. Start player
        CastUtils.castUrl(
            device!!,
            "https://directo.tuwebtv.es/canal1.m3u8"
        ) { result ->
            session = result
            latch.complete(Unit)
        }

        latch.await()
        assertNotNull("Session is null", session)

        // =========================
        // RECONNECT STATUS
        // =========================

        val session2: CastSession? = CastUtils.getSession(session!!.device)

        val statusJson = CastUtils.castGetStatus(session2!!)

        assertNotNull("Status is null", statusJson)

        println("========== RECONNECT STATUS ==========")
        println(statusJson!!.toString(2))
        println("======================================")

        assertTrue(statusJson.has("status"))
    }

    private suspend fun testManager(session: CastSession) {
        val manager = CastConnectionManager(session!!)

        // Esperar a que el manager esté listo (tenga transportId y mediaSessionId)
        val start = System.currentTimeMillis()
        while (!manager.isReady() && System.currentTimeMillis() - start < 10000) {
            println("Waiting for manager to be ready...")
            delay(500)
        }

        assertTrue("Manager did not become ready", manager.isReady())
        println("Manager ready with mediaSessionId: ${manager.getMediaSessionId()}")

        // 2. give some time
        delay(5_000)

        // 3. PAUSE
        manager.pause()
        delay(3_000)

        // 4. PLAY
        manager.play()
        delay(5_000)

        // 5. SEEK forward
        manager.seek(30.0)
        delay(5_000)

        // 6. SEEK backward
        manager.seek(10.0)
        delay(5_000)

        // 7. STOP
        manager.stop()
        delay(2_000)

        manager.close()

        assertTrue(true)
    }
}
