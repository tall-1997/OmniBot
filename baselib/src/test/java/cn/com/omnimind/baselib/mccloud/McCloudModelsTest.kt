package cn.com.omnimind.baselib.mccloud

import org.junit.Assert.assertEquals
import org.junit.Test

class McCloudModelsTest {
    @Test
    fun defaultEndpointsSelectIndependentServiceDomains() {
        val endpoints = McCloudEndpoints()

        assertEquals(
            "https://monkeycode-ai.com",
            endpoints.baseUrl(McCloudDomain.MONKEY_CODE),
        )
        assertEquals(
            "https://baizhi.cloud",
            endpoints.baseUrl(McCloudDomain.BAIZHI),
        )
    }

    @Test
    fun walletConvertsRawBalanceToCreditsUsingServiceScale() {
        assertEquals(12L, McCloudWallet(balance = 12_999).credits)
        assertEquals(0L, McCloudWallet(balance = 999).credits)
    }

    @Test
    fun serviceMessageRemovesTraceSuffix() {
        assertEquals(
            "登录已过期",
            cleanMcCloudMessage("登录已过期 [trace_id: abc-123]"),
        )
        assertEquals(
            "MonkeyCode cloud request failed",
            cleanMcCloudMessage("  "),
        )
    }
}
