package com.tactilesight.frame

import android.util.Log
import com.tactilesight.core.Frame
import com.tactilesight.core.FrameSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The band when it is there, the phone camera when it is not.
 *
 * This is what makes the app usable by someone who has never seen a band:
 * with no UNO Q on the network every press goes to the phone camera, and the
 * moment one appears the same press measures distance — no picker, no mode.
 *
 * [bandConnected] is kept current by a cheap background probe (see
 * [BandProbe]) rather than discovered per press, because discovering it per
 * press means a phone-only user waits out a connect timeout on every tap.
 *
 * A band capture that fails falls back to the phone for *that* press. A wearer
 * whose band dropped off WiFi mid-walk is better served by a description
 * without distances than by an apology — and the spoken answer already omits
 * distances when the frame carries no depth (ADR-0013).
 */
class AutoFrameSource(
    private val band: FrameSource,
    private val phone: FrameSource,
    private val bandConnected: () -> Boolean,
) : FrameSource {

    override suspend fun capture(): Frame {
        if (!bandConnected()) return phone.capture()
        return try {
            band.capture()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "band capture failed — using the phone camera for this press", e)
            phone.capture()
        }
    }

    private companion object {
        const val TAG = "AutoFrameSource"
    }
}

/** Is a band answering at this address? A TCP connect to its HTTP port. */
object BandProbe {

    /**
     * Short on purpose: on a network with no band the connect simply times
     * out, and this runs every few seconds while the screen is up.
     */
    private const val TIMEOUT_MS = 800

    suspend fun reachable(host: String): Boolean = withContext(Dispatchers.IO) {
        if (host.isBlank()) return@withContext false
        try {
            Socket().use { it.connect(InetSocketAddress(host, BandCaptureSource.HTTP_PORT), TIMEOUT_MS) }
            true
        } catch (e: Exception) {
            false
        }
    }
}
