package com.naqqa.sso.ldap;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

final class TestTls {
    final SSLContext server;
    final SSLContext client;

    private TestTls(SSLContext server, SSLContext client) {
        this.server = server;
        this.client = client;
    }

    static TestTls create(Path dir, String name, String san) throws Exception {
        Path ks = dir.resolve(name + ".p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        Process p = new ProcessBuilder(keytool, "-genkeypair", "-alias", "t", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=test", "-ext", "san=" + san, "-validity", "2", "-storetype", "PKCS12",
                "-keystore", ks.toString(), "-storepass", "changeit", "-keypass", "changeit")
                .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        if (p.waitFor() != 0) {
            throw new IllegalStateException("keytool failed");
        }
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(ks)) {
            store.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, "changeit".toCharArray());
        SSLContext s = SSLContext.getInstance("TLS");
        s.init(kmf.getKeyManagers(), null, null);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(store);
        SSLContext c = SSLContext.getInstance("TLS");
        c.init(null, tmf.getTrustManagers(), null);
        return new TestTls(s, c);
    }
}
