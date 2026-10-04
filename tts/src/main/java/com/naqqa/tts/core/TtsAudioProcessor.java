package com.naqqa.tts.core;

import com.naqqa.tts.config.TtsProperties;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public class TtsAudioProcessor {

    private static final double GATE_BELOW_LOUDEST_DB = 35;
    private static final double ABSOLUTE_GATE_DB = -60;

    private final TtsProperties.Audio settings;

    public TtsAudioProcessor(TtsProperties.Audio settings) {
        this.settings = settings == null ? new TtsProperties.Audio() : settings;
    }

    public byte[] process(byte[] wav) {
        if (!settings.isEnabled() || wav == null) {
            return wav;
        }
        Pcm pcm = Pcm.parse(wav);
        if (pcm == null || pcm.frames() == 0) {
            return wav;
        }
        double[] samples = pcm.read(wav);
        double gain = gain(samples, pcm);
        int fade = (int) Math.min(pcm.frames() / 2, Math.round(pcm.rate * Math.max(0, settings.getFadeMs()) / 1000.0));
        byte[] out = wav.clone();
        ByteBuffer buffer = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        for (int frame = 0; frame < pcm.frames(); frame++) {
            double envelope = gain;
            if (fade > 0 && frame < fade) {
                envelope *= curve((double) frame / fade);
            }
            int fromEnd = pcm.frames() - 1 - frame;
            if (fade > 0 && fromEnd < fade) {
                envelope *= curve((double) fromEnd / fade);
            }
            for (int c = 0; c < pcm.channels; c++) {
                int index = frame * pcm.channels + c;
                long value = Math.round(samples[index] * envelope * 32768.0);
                buffer.putShort(pcm.dataOffset + index * 2, (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value)));
            }
        }
        return out;
    }

    private double gain(double[] samples, Pcm pcm) {
        double peak = 0;
        for (double s : samples) {
            peak = Math.max(peak, Math.abs(s));
        }
        if (peak <= 0) {
            return 1;
        }
        double gain = 1;
        if (settings.getTargetRmsDb() != null) {
            double active = activeRms(samples, pcm);
            if (active > 0) {
                gain = Math.pow(10, settings.getTargetRmsDb() / 20) / active;
            }
        }
        if (settings.getPeakDb() != null) {
            double ceiling = Math.pow(10, settings.getPeakDb() / 20) / peak;
            gain = settings.getTargetRmsDb() == null ? ceiling : Math.min(gain, ceiling);
        }
        return gain;
    }

    static double activeRms(double[] samples, Pcm pcm) {
        int size = Math.max(1, pcm.rate / 50) * pcm.channels;
        int count = samples.length / size;
        if (count == 0) {
            return rms(samples, 0, samples.length);
        }
        double[] frames = new double[count];
        double loudest = 0;
        for (int i = 0; i < count; i++) {
            frames[i] = rms(samples, i * size, size);
            loudest = Math.max(loudest, frames[i]);
        }
        double gate = Math.max(loudest * Math.pow(10, -GATE_BELOW_LOUDEST_DB / 20), Math.pow(10, ABSOLUTE_GATE_DB / 20));
        double sum = 0;
        int active = 0;
        for (double f : frames) {
            if (f >= gate) {
                sum += f * f;
                active++;
            }
        }
        return active == 0 ? 0 : Math.sqrt(sum / active);
    }

    private static double rms(double[] samples, int from, int length) {
        double sum = 0;
        for (int i = from; i < from + length; i++) {
            sum += samples[i] * samples[i];
        }
        return length == 0 ? 0 : Math.sqrt(sum / length);
    }

    private static double curve(double x) {
        return 0.5 - 0.5 * Math.cos(Math.PI * Math.max(0, Math.min(1, x)));
    }

    record Pcm(int channels, int rate, int dataOffset, int dataLength) {

        int frames() {
            return dataLength / 2 / channels;
        }

        double[] read(byte[] wav) {
            ByteBuffer buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
            double[] samples = new double[frames() * channels];
            for (int i = 0; i < samples.length; i++) {
                samples[i] = buffer.getShort(dataOffset + i * 2) / 32768.0;
            }
            return samples;
        }

        static Pcm parse(byte[] wav) {
            if (wav.length < 44 || !"RIFF".equals(ascii(wav, 0)) || !"WAVE".equals(ascii(wav, 8))) {
                return null;
            }
            ByteBuffer buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
            int offset = 12;
            int channels = 0;
            int rate = 0;
            boolean pcm16 = false;
            while (offset + 8 <= wav.length) {
                String id = ascii(wav, offset);
                long size = Integer.toUnsignedLong(buffer.getInt(offset + 4));
                int body = offset + 8;
                if ("fmt ".equals(id) && size >= 16 && body + 16 <= wav.length) {
                    pcm16 = buffer.getShort(body) == 1 && buffer.getShort(body + 14) == 16;
                    channels = buffer.getShort(body + 2);
                    rate = buffer.getInt(body + 4);
                } else if ("data".equals(id)) {
                    if (!pcm16 || channels <= 0 || rate <= 0) {
                        return null;
                    }
                    int length = (int) Math.min(size, wav.length - body);
                    return new Pcm(channels, rate, body, length - length % (2 * channels));
                }
                offset = (int) Math.min(Integer.MAX_VALUE, body + size + (size % 2));
            }
            return null;
        }

        private static String ascii(byte[] wav, int offset) {
            return new String(wav, offset, 4, StandardCharsets.US_ASCII);
        }
    }
}
