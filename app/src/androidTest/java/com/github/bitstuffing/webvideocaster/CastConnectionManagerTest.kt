package com.github.bitstuffing.webvideocaster

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.bitstuffing.webvideocaster.runtime.CastConnectionManager
import com.github.bitstuffing.webvideocaster.utils.CastSession
import com.github.bitstuffing.webvideocaster.utils.CastUtils
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
    fun playPauseSameSocket_doesNotCrash() = runBlocking {

        val device = CastUtils.searchDevices().firstOrNull()
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

        val manager = CastConnectionManager(session!!)

        // 2. give some time
        delay(20_000)

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
