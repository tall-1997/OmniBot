package cn.com.omnimind.baselib.mccloud

import org.junit.Assert.assertEquals
import org.junit.Test

class McCloudEventsTest {
    @Test
    fun subscriberReceivesEventsUntilUnsubscribed() {
        val events = mutableListOf<Map<String, Any?>>()
        val unsubscribe = McCloudEvents.subscribe(events::add)

        McCloudEvents.emit(mapOf("type" to "connected"))
        unsubscribe()
        McCloudEvents.emit(mapOf("type" to "closed"))

        assertEquals(listOf(mapOf("type" to "connected")), events)
    }
}
