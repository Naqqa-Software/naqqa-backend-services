package com.naqqa.auth.sso.flow;

import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.auth.sso.SsoSecrets;
import com.naqqa.auth.sso.store.MongoChallengeStore;
import com.naqqa.auth.sso.store.MongoCredentialStore;
import com.naqqa.auth.sso.support.SsoApiException;
import com.naqqa.auth.sso.support.SsoTicketStore;
import com.naqqa.sso.SsoException;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;
import com.naqqa.sso.webauthn.AuthenticationOptions;
import com.naqqa.sso.webauthn.AuthenticationRequest;
import com.naqqa.sso.webauthn.AuthenticationResponse;
import com.naqqa.sso.webauthn.AuthenticationResult;
import com.naqqa.sso.webauthn.CredentialRecord;
import com.naqqa.sso.webauthn.RegistrationOptions;
import com.naqqa.sso.webauthn.RegistrationRequest;
import com.naqqa.sso.webauthn.RegistrationResponse;
import com.naqqa.sso.webauthn.RegistrationResult;
import com.naqqa.sso.webauthn.ResidentKey;
import com.naqqa.sso.webauthn.UserVerification;
import com.naqqa.sso.webauthn.WebAuthnPolicy;
import com.naqqa.sso.webauthn.WebAuthnPreset;
import com.naqqa.sso.webauthn.WebAuthnServer;
import org.bson.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@ConditionalOnProperty(prefix = "naqqa.sso", name = "enabled", havingValue = "true")
public class WebAuthnService {

    public static final String META = "sso_webauthn_meta";
    public static final String BIOMETRIC = "biometric";
    public static final String SECURITY_KEY = "security_key";
    public static final String PASSKEY = "passkey";
    static final String REG_KIND = "wa-reg";

    public record Credential(String id, String userId, String label, String kind, java.time.Instant createdAt,
                             java.time.Instant lastUsedAt, boolean backedUp, boolean userVerified) {
    }

    public record LoginResult(String userId, String kind, boolean userVerified, String credentialId) {
    }

    private final MongoCredentialStore credentials;
    private final MongoTemplate template;
    private final SsoTicketStore tickets;
    private final Clock clock;
    private final byte[] fakeKey;
    private final WebAuthnServer passkey;
    private final WebAuthnServer biometric;
    private final WebAuthnServer securityKey;

    public WebAuthnService(SsoProperties properties, MongoCredentialStore credentials, MongoChallengeStore challenges,
                           MongoTemplate template, SsoTicketStore tickets, SsoSecrets secrets, Clock clock) {
        this.credentials = credentials;
        this.template = template;
        this.tickets = tickets;
        this.clock = clock;
        this.fakeKey = secrets.derived("naqqa-sso/webauthn-fake");
        String rpId = properties.getRpId();
        if (rpId == null || rpId.isBlank() || properties.getAllowedOrigins().isEmpty()) {
            this.passkey = null;
            this.biometric = null;
            this.securityKey = null;
            return;
        }
        String rpName = properties.getRpName() == null || properties.getRpName().isBlank() ? rpId : properties.getRpName();
        List<String> origins = properties.getAllowedOrigins();
        Duration ttl = properties.getPasskey().getChallengeTtl();
        this.passkey = new WebAuthnServer(WebAuthnPolicy.builder(rpId, rpName).origins(origins)
                .userVerification(properties.getPasskey().isRequireUserVerification() ? UserVerification.REQUIRED : UserVerification.PREFERRED)
                .residentKey(ResidentKey.PREFERRED).challengeTtl(ttl).build(), credentials, challenges, clock);
        this.biometric = new WebAuthnServer(WebAuthnPreset.BIOMETRIC.builder(rpId, rpName, origins).challengeTtl(ttl).build(),
                credentials, challenges, clock);
        this.securityKey = new WebAuthnServer(WebAuthnPreset.SECURITY_KEY.builder(rpId, rpName, origins)
                .userVerification(properties.getSecurityKey().isRequireUserVerification() ? UserVerification.REQUIRED : UserVerification.PREFERRED)
                .allowUnverifiedAttestation(true).challengeTtl(ttl).build(), credentials, challenges, clock);
    }

    public boolean configured() {
        return passkey != null;
    }

    private WebAuthnServer server(String kind) {
        if (!configured()) {
            throw SsoApiException.notConfigured("WebAuthn");
        }
        return switch (kind) {
            case BIOMETRIC -> biometric;
            case SECURITY_KEY -> securityKey;
            default -> passkey;
        };
    }

    private static String bindingKey(String binding) {
        return Bytes.b64url(Hashes.sha256(Bytes.utf8(binding)));
    }

    public Map<String, Object> registrationOptions(String userId, String email, String displayName, String kind, String label, String binding) {
        RegistrationOptions options = server(kind).startRegistration(new RegistrationRequest(userId, email,
                displayName == null ? email : displayName, null, binding));
        Map<String, String> data = new LinkedHashMap<>();
        data.put("kind", kind);
        data.put("label", label == null ? "" : label);
        data.put("challenge", Bytes.b64url(options.challenge()));
        tickets.put(REG_KIND, bindingKey(binding), userId, data, server(kind).policy().challengeTtl());
        return Map.of("publicKey", options.toMap());
    }

    public Credential finishRegistration(String userId, String credentialJson, String label, String binding) {
        SsoTicketStore.Ticket pending = tickets.take(REG_KIND, bindingKey(binding))
                .orElseThrow(() -> SsoApiException.badRequest("WEBAUTHN_CHALLENGE_INVALID", "Registration expired, start again"));
        if (!userId.equals(pending.userId())) {
            throw SsoApiException.badRequest("WEBAUTHN_CHALLENGE_INVALID", "Registration expired, start again");
        }
        String kind = pending.data().get("kind");
        RegistrationResult result = server(kind).finishRegistration(RegistrationResponse.fromJson(credentialJson, binding));
        CredentialRecord record = result.credential();
        if (!userId.equals(record.userId())) {
            credentials.delete(record.credentialId());
            throw SsoApiException.badRequest("WEBAUTHN_USER_MISMATCH", "Credential does not belong to this account");
        }
        String finalLabel = label != null && !label.isBlank() ? label : pending.data().get("label");
        String id = Bytes.b64url(record.credentialId());
        template.save(new Document("_id", id).append("userId", userId).append("label", clean(finalLabel, kind)).append("kind", kind)
                .append("createdAt", Date.from(clock.instant())), META);
        return toCredential(record, meta(id));
    }

    public Map<String, Object> authenticationOptions(String userId, String email, String binding, boolean securityKeysAllowed) {
        AuthenticationOptions options = server(PASSKEY).startAuthentication(new AuthenticationRequest(userId, binding));
        Map<String, Object> map = new LinkedHashMap<>(options.toMap());
        if (email != null && (userId == null || credentials.findByUserId(userId).isEmpty())) {
            map.put("allowCredentials", fakeDescriptors(fakeKey, email));
        }
        if (securityKeysAllowed && mayLackUserVerification(userId, email) && options.userVerification() == UserVerification.REQUIRED) {
            map.put("userVerification", UserVerification.PREFERRED.value());
        }
        return Map.of("publicKey", map);
    }

    private boolean mayLackUserVerification(String userId, String email) {
        if (securityKey.policy().userVerificationRequired()) {
            return false;
        }
        if (email != null) {
            return true;
        }
        return userId != null && credentials.findByUserId(userId).stream()
                .anyMatch(r -> !r.uvInitialized() && SECURITY_KEY.equals(kindOf(Bytes.b64url(r.credentialId()))));
    }

    private String kindOf(String id) {
        Document m = meta(id);
        return m == null ? PASSKEY : m.getString("kind");
    }

    static final List<List<String>> FAKE_TRANSPORTS = List.of(List.of("hybrid", "internal"), List.of("internal"), List.of("usb"),
            List.of("nfc", "usb"), List.of("hybrid", "internal", "usb"), List.of());
    static final int[] FAKE_ID_LENGTHS = {16, 20, 32, 64};

    public static List<Map<String, Object>> fakeDescriptors(byte[] key, String email) {
        String norm = email.trim().toLowerCase(java.util.Locale.ROOT);
        byte[] seed = Hashes.hmacSha256(key, Bytes.utf8("fake|" + norm));
        int v = seed[0] & 0xff;
        int count = v < 176 ? 1 : v < 232 ? 2 : 3;
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            byte[] a = Hashes.hmacSha256(key, Bytes.utf8("fake|" + norm + "|" + i + "|a"));
            byte[] b = Hashes.hmacSha256(key, Bytes.utf8("fake|" + norm + "|" + i + "|b"));
            byte[] material = new byte[64];
            System.arraycopy(a, 0, material, 0, 32);
            System.arraycopy(b, 0, material, 32, 32);
            int length = FAKE_ID_LENGTHS[(seed[1 + i * 2] & 0xff) % FAKE_ID_LENGTHS.length];
            List<String> transports = FAKE_TRANSPORTS.get((seed[2 + i * 2] & 0xff) % FAKE_TRANSPORTS.size());
            Map<String, Object> descriptor = new LinkedHashMap<>();
            descriptor.put("type", "public-key");
            descriptor.put("id", Bytes.b64url(java.util.Arrays.copyOf(material, length)));
            if (!transports.isEmpty()) {
                descriptor.put("transports", transports);
            }
            out.add(descriptor);
        }
        return out;
    }

    public LoginResult finishAuthentication(String credentialJson, String binding) {
        AuthenticationResponse response = AuthenticationResponse.fromJson(credentialJson, binding);
        String id = Bytes.b64url(response.credentialId());
        Document meta = meta(id);
        String kind = meta == null ? PASSKEY : meta.getString("kind");
        AuthenticationResult result = server(kind).finishAuthentication(response);
        return new LoginResult(result.userId(), kind, result.userVerified(), id);
    }

    public static List<String> amrFor(String kind, boolean userVerified) {
        List<String> amr = new ArrayList<>();
        amr.add(SECURITY_KEY.equals(kind) ? "hwk" : "swk");
        if (userVerified) {
            amr.add("user");
        }
        return amr;
    }

    public List<Credential> list(String userId) {
        List<Credential> out = new ArrayList<>();
        for (CredentialRecord r : credentials.findByUserId(userId)) {
            out.add(toCredential(r, meta(Bytes.b64url(r.credentialId()))));
        }
        return out;
    }

    public boolean hasCredentials(String userId) {
        return !credentials.findByUserId(userId).isEmpty();
    }

    public Optional<Credential> rename(String userId, String id, String label) {
        Optional<CredentialRecord> r = owned(userId, id);
        if (r.isEmpty()) {
            return Optional.empty();
        }
        Document m = meta(id);
        String kind = m == null ? PASSKEY : m.getString("kind");
        template.upsert(new Query(Criteria.where("_id").is(id)), new Update().set("label", clean(label, kind)).set("userId", userId)
                .setOnInsert("kind", kind), META);
        return Optional.of(toCredential(r.get(), meta(id)));
    }

    public boolean delete(String userId, String id) {
        Optional<CredentialRecord> r = owned(userId, id);
        if (r.isEmpty()) {
            return false;
        }
        credentials.delete(r.get().credentialId());
        template.remove(new Query(Criteria.where("_id").is(id)), META);
        return true;
    }

    private Optional<CredentialRecord> owned(String userId, String id) {
        byte[] raw;
        try {
            raw = Bytes.b64urlDecode(id);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        try {
            return credentials.findById(raw).filter(r -> r.userId().equals(userId));
        } catch (SsoException e) {
            return Optional.empty();
        }
    }

    private Document meta(String id) {
        return template.findById(id, Document.class, META);
    }

    private static String clean(String label, String kind) {
        String l = label == null ? "" : label.replaceAll("[\\p{Cntrl}<>]", "").trim();
        if (l.isEmpty()) {
            l = BIOMETRIC.equals(kind) ? "Biometric" : SECURITY_KEY.equals(kind) ? "Security key" : "Passkey";
        }
        return l.length() > 64 ? l.substring(0, 64) : l;
    }

    private static Credential toCredential(CredentialRecord r, Document meta) {
        String kind = meta == null ? PASSKEY : meta.getString("kind");
        String label = meta == null ? clean(null, kind) : meta.getString("label");
        return new Credential(Bytes.b64url(r.credentialId()), r.userId(), label, kind, r.createdAt(), r.lastUsedAt(),
                r.backupState(), r.uvInitialized());
    }
}
