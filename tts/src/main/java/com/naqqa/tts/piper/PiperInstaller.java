package com.naqqa.tts.piper;

import com.naqqa.tts.config.TtsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class PiperInstaller {

    private static final Logger log = LoggerFactory.getLogger(PiperInstaller.class);

    private final TtsProperties properties;
    private final Path dataDir;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    public PiperInstaller(TtsProperties properties) {
        this.properties = properties;
        this.dataDir = Path.of(properties.getDataDir()).toAbsolutePath();
    }

    public Path binary() throws IOException {
        String configured = properties.getPiper().getBinary();
        if (configured != null && !configured.isBlank()) {
            Path p = Path.of(configured);
            if (Files.isRegularFile(p)) {
                return p.toAbsolutePath();
            }
            if (!properties.getPiper().isAutoInstall()) {
                throw new IOException("Piper binary not found at " + configured);
            }
        }
        Path installed = dataDir.resolve("piper").resolve(windows() ? "piper.exe" : "piper");
        if (Files.isRegularFile(installed)) {
            return installed;
        }
        if (!properties.getPiper().isAutoInstall()) {
            throw new IOException("Piper binary not found and auto-install is disabled");
        }
        installBinary();
        if (!Files.isRegularFile(installed)) {
            throw new IOException("Piper archive did not contain " + installed.getFileName());
        }
        installed.toFile().setExecutable(true);
        return installed;
    }

    public Path model(TtsProperties.Voice voice) throws IOException {
        if (voice.getModel() != null && !voice.getModel().isBlank()) {
            Path p = Path.of(voice.getModel());
            if (Files.isRegularFile(p)) {
                return p.toAbsolutePath();
            }
            throw new IOException("Voice model not found at " + voice.getModel());
        }
        String name = voice.getName();
        if (name == null || !name.matches("[a-z]{2,3}_[A-Z]{2}-[\\w]+-(x_low|low|medium|high)")) {
            throw new IOException("Voice name must look like ro_RO-mihai-medium, got " + name);
        }
        Path dir = dataDir.resolve("voices");
        Path onnx = dir.resolve(name + ".onnx");
        Path json = dir.resolve(name + ".onnx.json");
        if (Files.isRegularFile(onnx) && Files.isRegularFile(json)) {
            return onnx;
        }
        if (!properties.getPiper().isAutoInstall()) {
            throw new IOException("Voice " + name + " is not installed in " + dir);
        }
        String url = voice.getUrl() != null && !voice.getUrl().isBlank() ? voice.getUrl() : voiceUrl(name);
        download(url + ".json", json);
        download(url, onnx);
        return onnx;
    }

    String voiceUrl(String name) {
        String[] parts = name.split("-");
        String locale = parts[0];
        String family = locale.substring(0, locale.indexOf('_'));
        String speaker = parts[1];
        String quality = parts[2];
        String base = properties.getPiper().getVoicesBaseUrl().replaceAll("/+$", "");
        return base + "/" + family + "/" + locale + "/" + speaker + "/" + quality + "/" + name + ".onnx";
    }

    private void installBinary() throws IOException {
        String url = properties.getPiper().getDownloadUrl();
        if (url == null || url.isBlank()) {
            url = "https://github.com/rhasspy/piper/releases/download/" + properties.getPiper().getRelease() + "/" + archiveName();
        }
        Files.createDirectories(dataDir);
        Path archive = dataDir.resolve(url.endsWith(".zip") ? "piper-download.zip" : "piper-download.tar.gz");
        download(url, archive);
        try {
            if (url.endsWith(".zip")) {
                unzip(archive, dataDir);
            } else {
                untar(archive, dataDir);
            }
        } finally {
            Files.deleteIfExists(archive);
        }
        log.info("Piper installed into {}", dataDir.resolve("piper"));
    }

    private static String archiveName() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (windows()) {
            return "piper_windows_amd64.zip";
        }
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            return arch.contains("aarch64") || arch.contains("arm") ? "piper_macos_aarch64.tar.gz" : "piper_macos_x64.tar.gz";
        }
        return arch.contains("aarch64") || arch.contains("arm64") ? "piper_linux_aarch64.tar.gz" : "piper_linux_x86_64.tar.gz";
    }

    static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private void download(String url, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        log.info("Downloading {}", url);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(15)).GET().build();
            HttpResponse<Path> response = http.send(request, HttpResponse.BodyHandlers.ofFile(tmp));
            if (response.statusCode() != 200) {
                throw new IOException("Download failed with HTTP " + response.statusCode() + " for " + url);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted for " + url, e);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void untar(Path archive, Path dest) throws IOException {
        Process process = new ProcessBuilder("tar", "-xzf", archive.toString(), "-C", dest.toString())
                .redirectErrorStream(true)
                .start();
        try (InputStream ignored = process.getInputStream()) {
            ignored.transferTo(java.io.OutputStream.nullOutputStream());
            if (!process.waitFor(5, TimeUnit.MINUTES) || process.exitValue() != 0) {
                throw new IOException("tar could not extract " + archive);
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Extraction interrupted", e);
        }
    }

    private static void unzip(Path archive, Path dest) throws IOException {
        Path root = dest.toAbsolutePath().normalize();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path target = root.resolve(entry.getName()).normalize();
                if (!target.startsWith(root)) {
                    throw new IOException("Unsafe entry in Piper archive: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
