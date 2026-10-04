package com.tactilesight.frame

import com.tactilesight.core.DepthMap
import com.tactilesight.core.Frame
import com.tactilesight.core.FrameSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The standalone promise: with no band the phone camera answers, with a band
 * the band does, and a band that fails mid-press never costs the user a press.
 */
class AutoFrameSourceTest {

    private fun source(id: String, fails: Boolean = false) = object : FrameSource {
        var calls = 0
        override suspend fun capture(): Frame {
            calls++
            if (fails) error("$id is down")
            return Frame(ByteArray(0), DepthMap.NONE, 0L, sourceId = id)
        }
    }

    @Test
    fun `no band means the phone camera`() = runTest {
        val band = source("band")
        val auto = AutoFrameSource(band, source("phone")) { false }

        assertEquals("phone", auto.capture().sourceId)
        assertEquals("the band is not even tried", 0, band.calls)
    }

    @Test
    fun `a connected band is used`() = runTest {
        val auto = AutoFrameSource(source("band"), source("phone")) { true }

        assertEquals("band", auto.capture().sourceId)
    }

    @Test
    fun `a band that fails mid-press falls back to the phone`() = runTest {
        val auto = AutoFrameSource(source("band", fails = true), source("phone")) { true }

        assertEquals("phone", auto.capture().sourceId)
    }
}
