package com.naqqa.tts.core;

import com.naqqa.tts.config.TtsProperties;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class TtsAudioProcessorTest {

    private static final int RATE = 22050;

    private static byte[] wav(short[] samples) {
        ByteBuffer b = ByteBuffer.allocate(44 + samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + samples.length * 2).put("WAVE".getBytes(StandardCharsets.US_ASCII));
        b.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1).putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(samples.length * 2);
        for (short s : samples) {
            b.putShort(s);
        }
        return b.array();
    }

    private static short[] samples(byte[] wav) {
        ByteBuffer b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        short[] out = new short[(wav.length - 44) / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = b.getShort(44 + i * 2);
        }
        return out;
    }

    private static short[] tone(int length, double amplitude) {
        short[] s = new short[length];
        for (int i = 0; i < length; i++) {
            s[i] = (short) Math.round(Math.sin(2 * Math.PI * 220 * i / RATE) * amplitude * 32767);
        }
        return s;
    }

    private static double peak(short[] s) {
        int max = 0;
        for (short v : s) {
            max = Math.max(max, Math.abs(v));
        }
        return max / 32768.0;
    }

    @Test
    void leavesNonWavBytesUntouched() {
        byte[] junk = new byte[100];
        assertThat(new TtsAudioProcessor(new TtsProperties.Audio()).process(junk)).isSameAs(junk);
    }

    @Test
    void fadesEdgesToSilence() {
        short[] in = new short[RATE / 2];
        java.util.Arrays.fill(in, (short) 8000);
        TtsProperties.Audio audio = new TtsProperties.Audio();
        audio.setTargetRmsDb(null);
        audio.setPeakDb(null);
        short[] out = samples(new TtsAudioProcessor(audio).process(wav(in)));
        assertThat(out[0]).isEqualTo((short) 0);
        assertThat(out[out.length - 1]).isEqualTo((short) 0);
        assertThat(out[out.length / 2]).isEqualTo((short) 8000);
    }

    @Test
    void limitsFullScalePeaksBelowCeiling() {
        TtsProperties.Audio audio = new TtsProperties.Audio();
        audio.setTargetRmsDb(null);
        short[] out = samples(new TtsAudioProcessor(audio).process(wav(tone(RATE, 1.0))));
        assertThat(peak(out)).isBetween(0.88, 0.8913);
    }

    @Test
    void bringsQuietAndLoudClipsToTheSameLoudness() {
        TtsAudioProcessor processor = new TtsAudioProcessor(new TtsProperties.Audio());
        short[] quiet = samples(processor.process(wav(tone(RATE, 0.1))));
        short[] loud = samples(processor.process(wav(tone(RATE, 0.9))));
        assertThat(peak(quiet)).isCloseTo(peak(loud), org.assertj.core.data.Offset.offset(0.01));
        assertThat(peak(loud)).isLessThanOrEqualTo(0.8913);
    }

    @Test
    void disabledProcessingReturnsInput() {
        TtsProperties.Audio audio = new TtsProperties.Audio();
        audio.setEnabled(false);
        byte[] in = wav(tone(1000, 0.5));
        assertThat(new TtsAudioProcessor(audio).process(in)).isSameAs(in);
    }
}
