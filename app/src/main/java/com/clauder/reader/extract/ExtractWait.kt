package com.clauder.reader.extract

/**
 * 다음 화 페이지로 이동한 뒤, 본문이 나올 때까지 얼마나 기다릴지 정한다.
 *
 * 사이트의 보안 확인 페이지는 브라우저가 스스로 검사를 마치면 저절로 본문 페이지로 넘어가는 경우가 많다.
 * 다만 그 과정이 10초를 넘기기도 하므로, 보안 확인 페이지가 보이는 동안에는 일반적인 경우보다 오래 기다린다.
 * 기다리기만 할 뿐, 검사를 건너뛰거나 대신 풀지는 않는다.
 */
object ExtractWait {
    /** 본문을 못 찾았거나 아직 예전 페이지일 때 기다리는 시간 */
    const val NORMAL_MS = 8_000L

    /** 보안 확인 페이지가 보일 때 기다리는 시간. 이보다 오래 걸리면 사람이 직접 확인해야 하는 경우로 본다. */
    const val SECURITY_CHECK_MS = 45_000L

    /**
     * @param status 방금 추출한 결과의 상태(아직 원하는 본문이 아닌 경우)
     * @param waitedMs 페이지가 뜬 뒤 지금까지 기다린 시간
     * @return 조금 더 기다렸다가 다시 추출해 볼지
     */
    fun keepWaiting(status: ExtractionStatus, waitedMs: Long): Boolean {
        val limit = if (status == ExtractionStatus.BLOCKED) SECURITY_CHECK_MS else NORMAL_MS
        return waitedMs < limit
    }
}
