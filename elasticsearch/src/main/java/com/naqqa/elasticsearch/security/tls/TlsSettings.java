package com.naqqa.elasticsearch.security.tls;

import java.nio.file.Path;
import java.util.List;

public final class TlsSettings {

    private Path keyStorePath;
    private char[] keyStorePassword;
    private String keyStoreType = "PKCS12";
    private char[] keyPassword;
    private Path trustStorePath;
    private char[] trustStorePassword;
    private String trustStoreType = "PKCS12";
    private ClientAuthMode clientAuthMode = ClientAuthMode.NONE;
    private VerificationMode verificationMode = VerificationMode.FULL;
    private List<String> protocols = List.of("TLSv1.3", "TLSv1.2");
    private List<String> cipherSuites;

    public Path keyStorePath() {
        return keyStorePath;
    }

    public TlsSettings keyStorePath(Path keyStorePath) {
        this.keyStorePath = keyStorePath;
        return this;
    }

    public char[] keyStorePassword() {
        return keyStorePassword;
    }

    public TlsSettings keyStorePassword(char[] keyStorePassword) {
        this.keyStorePassword = keyStorePassword;
        return this;
    }

    public String keyStoreType() {
        return keyStoreType;
    }

    public TlsSettings keyStoreType(String keyStoreType) {
        this.keyStoreType = keyStoreType;
        return this;
    }

    public char[] keyPassword() {
        return keyPassword != null ? keyPassword : keyStorePassword;
    }

    public TlsSettings keyPassword(char[] keyPassword) {
        this.keyPassword = keyPassword;
        return this;
    }

    public Path trustStorePath() {
        return trustStorePath;
    }

    public TlsSettings trustStorePath(Path trustStorePath) {
        this.trustStorePath = trustStorePath;
        return this;
    }

    public char[] trustStorePassword() {
        return trustStorePassword;
    }

    public TlsSettings trustStorePassword(char[] trustStorePassword) {
        this.trustStorePassword = trustStorePassword;
        return this;
    }

    public String trustStoreType() {
        return trustStoreType;
    }

    public TlsSettings trustStoreType(String trustStoreType) {
        this.trustStoreType = trustStoreType;
        return this;
    }

    public ClientAuthMode clientAuthMode() {
        return clientAuthMode;
    }

    public TlsSettings clientAuthMode(ClientAuthMode clientAuthMode) {
        this.clientAuthMode = clientAuthMode;
        return this;
    }

    public VerificationMode verificationMode() {
        return verificationMode;
    }

    public TlsSettings verificationMode(VerificationMode verificationMode) {
        this.verificationMode = verificationMode;
        return this;
    }

    public List<String> protocols() {
        return protocols;
    }

    public TlsSettings protocols(List<String> protocols) {
        this.protocols = protocols;
        return this;
    }

    public List<String> cipherSuites() {
        return cipherSuites;
    }

    public TlsSettings cipherSuites(List<String> cipherSuites) {
        this.cipherSuites = cipherSuites;
        return this;
    }
}
