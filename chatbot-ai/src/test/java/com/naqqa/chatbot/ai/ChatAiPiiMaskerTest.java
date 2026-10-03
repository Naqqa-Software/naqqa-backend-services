package com.naqqa.chatbot.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatAiPiiMaskerTest {

    @Test
    void masksEmail() {
        String out = PiiMasker.mask("scrieți-mi la ion.popescu@gmail.com vă rog");
        assertFalse(out.contains("ion.popescu@gmail.com"));
        assertTrue(out.contains(PiiMasker.EMAIL));
    }

    @Test
    void masksMoldovanAndInternationalPhones() {
        assertFalse(PiiMasker.mask("sunați la 069123456").contains("069123456"));
        assertFalse(PiiMasker.mask("sunați la 069 123 456").contains("069 123 456"));
        assertFalse(PiiMasker.mask("tel +373 69 123 456").contains("69 123 456"));
        assertFalse(PiiMasker.mask("call +40 721 234 567 now").contains("721 234 567"));
        assertFalse(PiiMasker.mask("fix 022 000 111").contains("022 000 111"));
    }

    @Test
    void masksLuhnValidCardButKeepsPrices() {
        String out = PiiMasker.mask("cardul 4111 1111 1111 1111 nu merge, prețul e 1 299 lei");
        assertFalse(out.contains("4111"));
        assertTrue(out.contains(PiiMasker.CARD));
        assertTrue(out.contains("1 299 lei"));
    }

    @Test
    void masksIbanAndIdnp() {
        String iban = PiiMasker.mask("IBAN MD24AG000225100013104168 pentru plată");
        assertFalse(iban.contains("MD24AG000225100013104168"));
        String idnp = PiiMasker.mask("IDNP 2001234567890");
        assertFalse(idnp.contains("2001234567890"));
        assertTrue(idnp.contains(PiiMasker.IDNP));
    }

    @Test
    void masksPasswordsInAllLanguages() {
        assertFalse(PiiMasker.mask("parola mea este Secret123").contains("Secret123"));
        assertFalse(PiiMasker.mask("parola: qwerty").contains("qwerty"));
        assertFalse(PiiMasker.mask("мой пароль: тайна2024").contains("тайна2024"));
        assertFalse(PiiMasker.mask("password=hunter2").contains("hunter2"));
    }

    @Test
    void leavesNormalTextUntouched() {
        String text = "Am uitat parola si nu pot intra. Promoții la lapte 2.5% sub 20 lei, reducere 50% până pe 2025-10-03";
        assertEquals(text, PiiMasker.mask(text));
        assertEquals("Ce promoții are Maximum azi?", PiiMasker.mask("Ce promoții are Maximum azi?"));
    }
}
