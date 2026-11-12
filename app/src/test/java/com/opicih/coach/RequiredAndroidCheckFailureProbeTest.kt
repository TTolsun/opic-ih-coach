package com.opicih.coach

import org.junit.Assert.fail
import org.junit.Test

class RequiredAndroidCheckFailureProbeTest {
    @Test
    fun failsIntentionallyForMergeGateVerification() {
        fail("Intentional failure for the main merge gate verification.")
    }
}
