package com.github.bitstuffing.webvideocaster

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.bitstuffing.webvideocaster.utils.CastSession
import com.github.bitstuffing.webvideocaster.utils.CastUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CastUtilsInstrumentedTest {


    @Test
    fun searchDevices_doesNotCrash() = runBlocking {
        Log.d("CAST_TEST", "TEST begin")

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val lock = CastUtils.acquireMulticastLock(context)

        try {
            Log.d("CAST_TEST", "MulticastLock locked")

            val devices = CastUtils.searchDevices()

            Log.d("CAST_TEST", "Devices found: ${devices.size}")
            devices.forEach { device ->
                Log.d("CAST_TEST", "Device: ${device.friendlyName} | IP: ${device.ip}")
            }
        } finally {
            lock.release()
            Log.d("CAST_TEST", "Free MulticastLock")
        }
    }

    @Test
    fun castUrl_doesNotCrash() = runBlocking {

        val latch = java.util.concurrent.CountDownLatch(1)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val lock = CastUtils.acquireMulticastLock(context)

        var session: CastSession? = null

        try {

            val devices = CastUtils.searchDevices()
            val device = devices.firstOrNull()

            assertNotNull("No available cast devices", device)

            val testUrl = "https://download.blender.org/peach/bigbuckbunny_movies/BigBuckBunny_320x180.mp4"

            CastUtils.castUrl(device!!, testUrl) {
                session = it
                latch.countDown()
            }

            Thread.sleep(18000)

            assertNotNull(session)

            CastUtils.castPause(session!!)
            Thread.sleep(5000)

            val paused = CastUtils.castGetStatus(session!!)
            Log.d("CAST_TEST", "PAUSED STATUS: $paused")

            CastUtils.castPlay(session!!)
            Thread.sleep(5000)

            val playing = CastUtils.castGetStatus(session!!)
            Log.d("CAST_TEST", "PLAYING STATUS: $playing")

            CastUtils.castStop(session!!)

            val status2 = CastUtils.castGetStatus(session!!)

            Log.d("CAST_TEST", "STATUS after STOP: $status2")

            Thread.sleep(5000)

            val status3 = CastUtils.castGetStatus(session!!)

            Log.d("CAST_TEST", "STATUS before EXIT: $status3")

            Thread.sleep(1000)
        } finally {
            lock.release()
        }
    }

    @Test
    fun castSeek_changesPlaybackPosition() = runBlocking {

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val lock = CastUtils.acquireMulticastLock(context)

        try {
            val devices = CastUtils.searchDevices()
            val device = devices.firstOrNull()

            assertNotNull("No cast devices found", device)

            val latch = java.util.concurrent.CountDownLatch(1)

            var session: CastSession? = null

            CastUtils.castUrl(device!!, "https://directo.tuwebtv.es/canal1.m3u8") {
                session = it
                latch.countDown()
            }

            latch.await()

            assertNotNull(session)

            Thread.sleep(8000)

            val before = CastUtils.castGetStatus(session!!)
            val beforeTime = before
                ?.optJSONArray("status")
                ?.optJSONObject(0)
                ?.optDouble("currentTime")

            Log.d("CAST_TEST", "Before SEEK: $beforeTime")

            CastUtils.castForward(session!!, 60)

            Thread.sleep(3000)

            val after = CastUtils.castGetStatus(session!!)
            val afterTime = after
                ?.optJSONArray("status")
                ?.optJSONObject(0)
                ?.optDouble("currentTime")

            Log.d("CAST_TEST", "After Forward: $afterTime")

            assertTrue(
                "Seek did not move playback position",
                (afterTime ?: 0.0) >= 55.0
            )

            CastUtils.castStop(session!!)

            val status2 = CastUtils.castGetStatus(session!!)

            Log.d("CAST_TEST", "STATUS after STOP: $status2")

            Thread.sleep(5000)

            val status3 = CastUtils.castGetStatus(session!!)

            Log.d("CAST_TEST", "STATUS before EXIT: $status3")

            Thread.sleep(1000)

        } finally {
            lock.release()
        }
    }

    @Test
    fun appContext_isCorrect() {
        val appContext: Context = InstrumentationRegistry.getInstrumentation().targetContext
        assertNotNull(appContext.packageName)
    }
}
