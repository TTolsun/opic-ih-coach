package com.opicih.coach

import com.opicih.coach.speech.VoiceGender
import com.opicih.coach.speech.VoiceGenders
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceGendersTest {
    @Test fun localAndNetworkVariantsShareTheGenderOfTheirCode() {
        assertEquals(VoiceGender.FEMALE, VoiceGenders.of("en-us-x-iob-local"))
        assertEquals(VoiceGender.FEMALE, VoiceGenders.of("en-us-x-iob-network"))
        assertEquals(VoiceGender.MALE, VoiceGenders.of("en-us-x-iol-local"))
        assertEquals(VoiceGender.MALE, VoiceGenders.of("en-US-X-IOM-LOCAL"))
    }

    @Test fun koreanVoicesAreCovered() {
        assertEquals(VoiceGender.FEMALE, VoiceGenders.of("ko-kr-x-ism-local"))
        assertEquals(VoiceGender.MALE, VoiceGenders.of("ko-kr-x-koc-local"))
    }

    @Test fun unknownNamesAreNotGuessed() {
        assertEquals(VoiceGender.UNKNOWN, VoiceGenders.of("en-US-language"))
        assertEquals(VoiceGender.UNKNOWN, VoiceGenders.of("en-US-SMTf00"))
        assertEquals("", VoiceGender.UNKNOWN.label)
    }
}
