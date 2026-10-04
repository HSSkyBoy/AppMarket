package com.app.market.domain.model.profile

import kotlin.test.Test
import kotlin.test.assertEquals

class SamsungRequestContextTest {
    @Test
    fun defaultContextMatchesChinaProtocolRoutingInputs() {
        assertEquals(
            SamsungRequestContext("CHN", "zh_CN", "460", "00", "CHC"),
            DefaultSamsungRequestContext,
        )
    }
}
