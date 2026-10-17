package com.naqqa.auth.sso.oidc;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.crypto.KeyRing;
import com.naqqa.sso.crypto.Pem;
import com.naqqa.sso.crypto.SigningAlgorithm;
import com.naqqa.sso.crypto.SigningKey;
import com.naqqa.sso.mfa.SecretSealer;
import com.naqqa.sso.util.Bytes;
import org.bson.Document;
import org.bson.types.Binary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

public final class OidcSigningKeys {

    public static final String COLLECTION = "sso_oidc_keys";
    static final byte[] AAD = Bytes.utf8("naqqa-sso/oidc-signing-key");

    private final KeyRing ring;

    private OidcSigningKeys(KeyRing ring) {
        this.ring = ring;
    }

    public KeyRing ring() {
        return ring;
    }

    public static OidcSigningKeys load(String privatePem, String algorithm, SigningKey authKey, MongoTemplate template,
                                       SecretSealer sealer, Clock clock) {
        SigningAlgorithm alg = SigningAlgorithm.valueOf(algorithm == null ? "RS256" : algorithm.trim().toUpperCase(Locale.ROOT)
                .replace("EDDSA", "EdDSA"));
        SigningKey key = privatePem != null && !privatePem.isBlank()
                ? fromPem(privatePem.trim(), alg)
                : persisted(alg, template, sealer, clock);
        if (key.alg() != alg) {
            throw new IllegalStateException("naqqa.sso.oidc-provider signing key does not match the configured algorithm");
        }
        if (authKey != null && Arrays.equals(key.publicKey().getEncoded(), authKey.publicKey().getEncoded())) {
            throw new IllegalStateException("naqqa.sso.oidc-provider must not reuse the naqqa-auth access token key");
        }
        return new OidcSigningKeys(KeyRing.of(clock, key));
    }

    private static SigningKey fromPem(String pem, SigningAlgorithm alg) {
        int split = pem.indexOf("-----BEGIN PUBLIC KEY-----");
        if (split > 0) {
            return Pem.signingKey(pem.substring(0, split).trim(), pem.substring(split).trim());
        }
        if (alg != SigningAlgorithm.RS256) {
            throw new IllegalStateException("naqqa.sso.oidc-provider EC and EdDSA keys need the public key PEM appended");
        }
        return Pem.signingKey(pem, null);
    }

    private static SigningKey persisted(SigningAlgorithm alg, MongoTemplate template, SecretSealer sealer, Clock clock) {
        String id = "active-" + alg.name();
        Document existing = template.findById(id, Document.class, COLLECTION);
        if (existing == null) {
            SigningKey generated = alg == SigningAlgorithm.RS256 ? SigningKey.generateRsa(2048) : SigningKey.generate(alg);
            String pem = Pem.encodePrivateKey(generated.privateKey()) + "\n" + Pem.encodePublicKey(generated.publicKey());
            byte[] sealed = sealer.seal(pem.getBytes(StandardCharsets.US_ASCII), AAD);
            try {
                template.insert(new Document("_id", id).append("sealed", new Binary(sealed)).append("createdAt", Date.from(clock.instant())),
                        COLLECTION);
            } catch (DuplicateKeyException ignored) {
            }
            existing = template.findById(id, Document.class, COLLECTION);
        }
        if (existing == null) {
            throw new SsoException("oidc.keyring_missing");
        }
        Object raw = existing.get("sealed");
        byte[] sealed = raw instanceof Binary b ? b.getData() : (byte[]) raw;
        byte[] opened = sealer.open(sealed, AAD);
        try {
            return fromPem(new String(opened, StandardCharsets.US_ASCII), alg);
        } finally {
            Arrays.fill(opened, (byte) 0);
        }
    }
}
