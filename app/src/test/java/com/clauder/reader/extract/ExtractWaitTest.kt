package com.clauder.reader.extract

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractWaitTest {

    @Test
    fun `보안 확인 페이지는 저절로 넘어갈 때까지 더 오래 기다린다`() {
        // 예전에는 8초쯤에서 포기해서, 10초 넘게 걸리는 보안 확인을 매번 놓쳤다.
        assertTrue(ExtractWait.keepWaiting(ExtractionStatus.BLOCKED, 9_000))
        assertTrue(ExtractWait.keepWaiting(ExtractionStatus.BLOCKED, 30_000))
        assertFalse(ExtractWait.keepWaiting(ExtractionStatus.BLOCKED, 45_000))
        assertFalse(ExtractWait.keepWaiting(ExtractionStatus.BLOCKED, 60_000))
    }

    @Test
    fun `본문을 못 찾는 페이지는 오래 붙잡지 않는다`() {
        for (status in listOf(ExtractionStatus.TOO_SHORT, ExtractionStatus.ERROR, ExtractionStatus.OK)) {
            assertTrue(ExtractWait.keepWaiting(status, 5_000))
            assertFalse(ExtractWait.keepWaiting(status, 8_000))
            assertFalse(ExtractWait.keepWaiting(status, 20_000))
        }
    }
}
