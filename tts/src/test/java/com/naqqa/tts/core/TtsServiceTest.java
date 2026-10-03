package com.naqqa.tts.core;

import com.naqqa.tts.config.TtsProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TtsServiceTest {

    @TempDir
    Path dir;

    static class FakeEngine implements TtsEngine {
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean ready = true;
        volatile boolean fail;
        volatile String lastText;

        public boolean ready() {
            return ready;
        }

        public void prepare() {
        }

        public void shutdown() {
        }

        public Path synthesize(String text, String lang, TtsProperties.Voice voice, Path output) {
            calls.incrementAndGet();
            lastText = text;
            if (fail) {
                throw new TtsException(TtsException.Reason.FAILED, "boom");
            }
            try {
                Files.write(output, new byte[100]);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return output;
        }
    }

    private TtsProperties props() {
        TtsProperties p = new TtsProperties();
        p.setEnabled(true);
        TtsProperties.Voice ro = new TtsProperties.Voice();
        ro.setName("ro_RO-mihai-medium");
        p.setVoices(Map.of("ro", ro));
        p.setLexicon(Map.of("ro", Map.of("OMY", "Omi")));
        p.setBreakerFailures(2);
        return p;
    }

    private TtsService service(TtsProperties p, FakeEngine engine, int perMinute) {
        return new TtsService(p, engine, new TtsCache(dir, 10_000_000), new TtsRateLimiter(perMinute), new TtsTextNormalizer());
    }

    private static TtsException.Reason reason(Throwable e) {
        return ((TtsException) e).reason();
    }

    @Test
    void cachesSynthesizedAudio() {
        FakeEngine engine = new FakeEngine();
        TtsService s = service(props(), engine, 10);
        TtsService.Audio first = s.speak(s.prepare("Salut, sunt OMY", "ro"), "1.1.1.1");
        TtsService.Audio second = s.speak(s.prepare("Salut, sunt OMY", "ro"), "1.1.1.1");
        assertThat(first.cached()).isFalse();
        assertThat(second.cached()).isTrue();
        assertThat(second.bytes()).hasSize(100);
        assertThat(engine.calls.get()).isEqualTo(1);
        assertThat(engine.lastText).isEqualTo("Salut, sunt Omi");
    }

    @Test
    void rejectsUnsupportedLanguage() {
        TtsService s = service(props(), new FakeEngine(), 10);
        assertThatThrownBy(() -> s.prepare("hello", "de")).satisfies(e -> assertThat(reason(e)).isEqualTo(TtsException.Reason.UNSUPPORTED));
    }

    @Test
    void rateLimitsOnlyCacheMisses() {
        TtsService s = service(props(), new FakeEngine(), 1);
        s.speak(s.prepare("unu", "ro"), "ip");
        s.speak(s.prepare("unu", "ro"), "ip");
        assertThatThrownBy(() -> s.speak(s.prepare("doi", "ro"), "ip")).satisfies(e -> assertThat(reason(e)).isEqualTo(TtsException.Reason.RATE_LIMITED));
    }

    @Test
    void opensBreakerAfterRepeatedFailures() {
        FakeEngine engine = new FakeEngine();
        engine.fail = true;
        TtsService s = service(props(), engine, 100);
        assertThatThrownBy(() -> s.speak(s.prepare("text unu", "ro"), "ip")).isInstanceOf(TtsException.class);
        assertThatThrownBy(() -> s.speak(s.prepare("text doi", "ro"), "ip")).isInstanceOf(TtsException.class);
        assertThatThrownBy(() -> s.speak(s.prepare("text trei", "ro"), "ip")).satisfies(e -> assertThat(reason(e)).isEqualTo(TtsException.Reason.UNAVAILABLE));
        assertThat(engine.calls.get()).isEqualTo(2);
        assertThat(s.ready()).isFalse();
    }

    @Test
    void unavailableWhenEngineNotReady() {
        FakeEngine engine = new FakeEngine();
        engine.ready = false;
        TtsService s = service(props(), engine, 10);
        assertThatThrownBy(() -> s.speak(s.prepare("salut", "ro"), "ip")).satisfies(e -> assertThat(reason(e)).isEqualTo(TtsException.Reason.UNAVAILABLE));
    }
}
