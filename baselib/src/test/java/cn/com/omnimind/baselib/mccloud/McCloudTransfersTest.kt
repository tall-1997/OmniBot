package cn.com.omnimind.baselib.mccloud

import org.junit.Assert.assertEquals
import org.junit.Test

class McCloudTransfersTest {
    @Test
    fun reconnectBackoffCapsAtThirtySeconds() {
        assertEquals(1_000L, taskReconnectDelayMillis(0))
        assertEquals(2_000L, taskReconnectDelayMillis(1))
        assertEquals(16_000L, taskReconnectDelayMillis(4))
        assertEquals(30_000L, taskReconnectDelayMillis(5))
        assertEquals(30_000L, taskReconnectDelayMillis(100))
    }
}
