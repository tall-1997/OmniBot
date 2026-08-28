package cn.com.omnimind.baselib.mccloud

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class McCloudConsumerRulesTest {
    @Test
    fun consumerRulesKeepReflectiveCloudPayloadFields() {
        val workingDirectory = File(System.getProperty("user.dir"))
        val rules = sequenceOf(
            File(workingDirectory, "baselib/consumer-rules.pro"),
            File(workingDirectory, "consumer-rules.pro"),
        )
            .firstOrNull(File::isFile)
            ?.readText()
            .orEmpty()

        assertTrue(rules.contains("class cn.com.omnimind.baselib.mccloud.**"))
        assertTrue(rules.contains("<fields>;"))
    }
}
