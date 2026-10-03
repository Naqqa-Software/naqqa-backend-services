package com.naqqa.chatbot;

import com.naqqa.chatbot.service.ChatAudioValidator;
import com.naqqa.chatbot.service.ChatException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatAudioValidatorTest {

    private static byte[] webm() {
        byte[] b = new byte[64];
        b[0] = 0x1A;
        b[1] = 0x45;
        b[2] = (byte) 0xDF;
        b[3] = (byte) 0xA3;
        return b;
    }

    private static byte[] withAscii(String text, int offset) {
        byte[] b = new byte[64];
        byte[] t = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(t, 0, b, offset, t.length);
        return b;
    }

    private static byte[] wav() {
        byte[] b = withAscii("RIFF", 0);
        System.arraycopy("WAVE".getBytes(StandardCharsets.US_ASCII), 0, b, 8, 4);
        return b;
    }

    @Test
    void acceptsMatchingTypes() {
        assertEquals("audio/webm", ChatAudioValidator.validate(webm(), "audio/webm;codecs=opus", 5000L));
        assertEquals("audio/wav", ChatAudioValidator.validate(wav(), "audio/wav", 1000L));
        assertEquals("audio/ogg", ChatAudioValidator.validate(withAscii("OggS", 0), "audio/ogg", 1000L));
        assertEquals("audio/mp4", ChatAudioValidator.validate(withAscii("ftypM4A ", 4), "audio/mp4", 1000L));
        assertEquals("audio/mpeg", ChatAudioValidator.validate(withAscii("ID3", 0), "audio/mpeg", 1000L));
    }

    @Test
    void rejectsMismatchUnknownAndLimits() {
        assertInvalid(() -> ChatAudioValidator.validate(webm(), "audio/wav", 1000L));
        assertInvalid(() -> ChatAudioValidator.validate("<html>".getBytes(StandardCharsets.US_ASCII), "audio/webm", 1000L));
        assertInvalid(() -> ChatAudioValidator.validate(webm(), "application/pdf", 1000L));
        assertInvalid(() -> ChatAudioValidator.validate(webm(), "audio/webm", 60_001L));
        assertInvalid(() -> ChatAudioValidator.validate(webm(), "audio/webm", null));
        assertInvalid(() -> ChatAudioValidator.validate(new byte[0], "audio/webm", 1000L));
        byte[] big = new byte[(int) ChatAudioValidator.MAX_BYTES + 1];
        System.arraycopy(webm(), 0, big, 0, 4);
        assertInvalid(() -> ChatAudioValidator.validate(big, "audio/webm", 1000L));
    }

    private static void assertInvalid(Runnable r) {
        ChatException ex = assertThrows(ChatException.class, r::run);
        assertEquals(ChatException.AUDIO_INVALID, ex.getErrorKey());
    }
}
