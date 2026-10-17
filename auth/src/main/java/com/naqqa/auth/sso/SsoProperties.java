package com.naqqa.auth.sso;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@ConfigurationProperties(prefix = "naqqa.sso")
public class SsoProperties {

    private boolean enabled = false;
    private boolean devMode = false;
    private String issuer;
    private String rpId;
    private String rpName;
    private List<String> allowedOrigins = new ArrayList<>();

    private Password password = new Password();
    private MagicLink magicLink = new MagicLink();
    private EmailOtp emailOtp = new EmailOtp();
    private SmsOtp smsOtp = new SmsOtp();
    private Totp totp = new Totp();
    private Hotp hotp = new Hotp();
    private Recovery recovery = new Recovery();
    private Passkey passkey = new Passkey();
    private Biometric biometric = new Biometric();
    private SecurityKey securityKey = new SecurityKey();
    private Push push = new Push();
    private Social social = new Social();
    private Saml saml = new Saml();
    private Ldap ldap = new Ldap();
    private OidcProvider oidcProvider = new OidcProvider();
    private Scim scim = new Scim();
    private DeviceCode deviceCode = new DeviceCode();
    private ClientCredentials clientCredentials = new ClientCredentials();
    private ApiTokens apiTokens = new ApiTokens();
    private Mfa mfa = new Mfa();
    private Session session = new Session();
    private RateLimits rateLimits = new RateLimits();
    private Risk risk = new Risk();
    private Audit audit = new Audit();
    private Secrets secrets = new Secrets();
    private Tenants tenants = new Tenants();
    private Map<String, Client> clients = new HashMap<>();

    @Getter
    @Setter
    public static class Password {
        private boolean enabled = true;
        private int minLength = 8;
        private int maxLength = 128;
        private int iterations = 210000;
        private boolean rehashLegacy = true;
    }

    @Getter
    @Setter
    public static class MagicLink {
        private boolean enabled = false;
        private Duration ttl = Duration.ofMinutes(15);
        private boolean bindToBrowser = true;
    }

    @Getter
    @Setter
    public static class EmailOtp {
        private boolean enabled = false;
        private int length = 6;
        private Duration ttl = Duration.ofMinutes(10);
        private int maxAttempts = 5;
        private Duration resendInterval = Duration.ofSeconds(60);
        private int maxSendsPerHour = 5;
    }

    @Getter
    @Setter
    public static class SmsOtp {
        private boolean enabled = false;
        private int length = 6;
        private Duration ttl = Duration.ofMinutes(5);
        private int maxAttempts = 5;
        private Duration resendInterval = Duration.ofSeconds(60);
        private int maxSendsPerHour = 5;
    }

    @Getter
    @Setter
    public static class Totp {
        private boolean enabled = true;
        private String algorithm = "SHA1";
        private int digits = 6;
        private int stepSeconds = 30;
        private int window = 1;
        private Duration pendingTtl = Duration.ofMinutes(15);
    }

    @Getter
    @Setter
    public static class Hotp {
        private boolean enabled = false;
        private String algorithm = "SHA1";
        private int digits = 6;
        private int lookAhead = 10;
        private int resyncWindow = 100;
        private Duration pendingTtl = Duration.ofMinutes(15);
    }

    @Getter
    @Setter
    public static class Recovery {
        private boolean enabled = true;
        private int count = 10;
        private int maxFailedAttempts = 10;
    }

    @Getter
    @Setter
    public static class Passkey {
        private boolean enabled = false;
        private boolean usernameless = true;
        private boolean requireUserVerification = true;
        private String attestation = "none";
        private Duration challengeTtl = Duration.ofMinutes(5);
    }

    @Getter
    @Setter
    public static class Biometric {
        private boolean enabled = false;
    }

    @Getter
    @Setter
    public static class SecurityKey {
        private boolean enabled = false;
        private boolean requireUserVerification = false;
    }

    @Getter
    @Setter
    public static class Push {
        private boolean enabled = false;
        private Duration ttl = Duration.ofMinutes(2);
        private int maxMismatches = 2;
    }

    @Getter
    @Setter
    public static class Social {
        private List<String> providers = new ArrayList<>();
        private boolean linkVerifiedEmailOnly = true;
        private boolean enabled = false;
        private boolean autoLink = true;
        private boolean createAccounts = true;
        private boolean testMode = false;
        private String successUrl;
        private String failureUrl;
        private Map<String, SocialProvider> registrations = new LinkedHashMap<>();
    }

    @Getter
    @Setter
    public static class SocialProvider {
        private String kind;
        private String clientId;
        private String clientSecretEnv;
        private String redirectUri;
        private String issuer;
        private String tenant;
        private List<String> allowedTenants = new ArrayList<>();
        private List<String> scopes = new ArrayList<>();
        private List<String> trustedEmailDomains = new ArrayList<>();
        private Boolean trustAllEmails;
        private String authorizationEndpoint;
        private String tokenEndpoint;
        private String jwksUri;
        private String userinfoEndpoint;
        private String emailsEndpoint;
        private String debugTokenEndpoint;
        private String appleTeamId;
        private String appleKeyId;
        private String applePrivateKeyEnv;
    }

    @Getter
    @Setter
    public static class Ldap {
        private boolean enabled = false;
        private Map<String, Directory> directories = new LinkedHashMap<>();
    }

    @Getter
    @Setter
    public static class Directory {
        private String host;
        private int port = 636;
        private String tls = "LDAPS";
        private boolean allowPlainForTests = false;
        private String baseDn;
        private String serviceDn;
        private String servicePasswordEnv;
        private List<String> loginAttributes = new ArrayList<>();
        private String groupAttribute = "memberOf";
        private String bindMode = "DN";
        private String upnSuffix;
        private String emailAttribute = "mail";
        private String nameAttribute = "displayName";
        private Map<String, String> groupRoles = new LinkedHashMap<>();
        private String defaultRole;
        private boolean localFallback = true;
    }

    @Getter
    @Setter
    public static class Saml {
        private boolean enabled = false;
        private List<String> idps = new ArrayList<>();
        private Duration clockSkew = Duration.ofSeconds(60);
        private Duration assertionTtl = Duration.ofMinutes(5);
        private Idp idp = new Idp();
        private Sp sp = new Sp();
    }

    @Getter
    @Setter
    public static class Sp {
        private boolean enabled = false;
        private String certificate;
        private String privateKey;
        private Duration requestTtl = Duration.ofMinutes(10);
        private String successUrl;
        private String failureUrl;
        private Map<String, SpIdp> tenants = new LinkedHashMap<>();
    }

    @Getter
    @Setter
    public static class SpIdp {
        private boolean enabled = false;
        private String name;
        private String entityId;
        private String ssoUrl;
        private List<String> signingCertificates = new ArrayList<>();
        private String nameIdFormat = "urn:oasis:names:tc:SAML:2.0:nameid-format:persistent";
        private String emailAttribute = "email";
        private String nameAttribute = "name";
        private String groupsAttribute = "groups";
        private List<String> allowedDomains = new ArrayList<>();
        private List<String> authoritativeDomains = new ArrayList<>();
        private boolean jitProvisioning = false;
        private Map<String, String> groupRoles = new LinkedHashMap<>();
        private String defaultRole;
        private boolean trustedForAssurance = false;
        private List<String> mfaAuthnContexts = new ArrayList<>(List.of("https://refeds.org/profile/mfa"));
        private boolean allowUnsolicited = false;
        private boolean requireSignedAssertion = true;
        private boolean requireSignedResponse = false;
        private boolean requireEncryptedAssertion = false;
    }

    @Getter
    @Setter
    public static class Idp {
        private String entityId;
        private String certificate;
        private String privateKey;
        private String loginUrl;
        private boolean wantAuthnRequestsSigned = false;
        private Duration pendingTtl = Duration.ofMinutes(10);
        private List<ServiceProvider> serviceProviders = new ArrayList<>();
    }

    @Getter
    @Setter
    public static class ServiceProvider {
        private String entityId;
        private List<String> acsUrls = new ArrayList<>();
        private String sloUrl;
        private String signingCertificate;
        private String encryptionCertificate;
        private boolean requireSignedRequests = false;
        private boolean signResponse = false;
        private boolean encryptAssertion = false;
        private String defaultNameIdFormat;
        private List<String> nameIdFormats = new ArrayList<>();
    }

    @Getter
    @Setter
    public static class OidcProvider {
        private boolean enabled = false;
        private String signingAlgorithm = "RS256";
        private Duration accessTokenTtl = Duration.ofMinutes(15);
        private Duration idTokenTtl = Duration.ofMinutes(15);
        private Duration authorizationCodeTtl = Duration.ofMinutes(1);
        private boolean requirePkce = true;
        private String issuer;
        private String signingKeyEnv = "NAQQA_SSO_OIDC_SIGNING_KEY";
        private String loginUrl;
        private String consentUrl;
        private String logoutUrl;
        private String deviceUrl;
        private Duration pendingTtl = Duration.ofMinutes(10);
        private boolean dynamicRegistration = false;
        private String registrationTokenEnv = "NAQQA_SSO_OIDC_REGISTRATION_TOKEN";
        private Backchannel backchannel = new Backchannel();
        private Map<String, OidcClient> clients = new LinkedHashMap<>();
    }

    @Getter
    @Setter
    public static class Backchannel {
        private Duration timeout = Duration.ofSeconds(5);
        private int maxAttempts = 3;
        private Duration backoff = Duration.ofSeconds(2);
        private boolean testMode = false;
    }

    @Getter
    @Setter
    public static class OidcClient {
        private String tenant = "default";
        private String name;
        private boolean publicClient = false;
        private String authMethod = "client_secret_basic";
        private String secretEnv;
        private String jwks;
        private List<String> redirectUris = new ArrayList<>();
        private List<String> postLogoutRedirectUris = new ArrayList<>();
        private List<String> grantTypes = new ArrayList<>();
        private List<String> scopes = new ArrayList<>();
        private boolean firstParty = false;
        private String backchannelLogoutUri;
        private List<String> allowedAcr = new ArrayList<>();
        private String minAcr;
        private boolean requirePar = false;
        private boolean requireDpop = false;
        private boolean requireState = false;
        private List<String> audience = new ArrayList<>();
        private Duration accessTokenTtl;
        private Duration idTokenTtl;
    }

    @Getter
    @Setter
    public static class Scim {
        private boolean enabled = false;
        private List<String> accountTenants = new ArrayList<>(List.of("default"));
        private int maxResults = 100;
        private int maxScan = 2000;
        private int maxStartIndex = 10000;
        private String baseUrl;
    }

    @Getter
    @Setter
    public static class DeviceCode {
        private boolean enabled = false;
        private Duration ttl = Duration.ofMinutes(10);
        private Duration pollInterval = Duration.ofSeconds(5);
    }

    @Getter
    @Setter
    public static class ClientCredentials {
        private boolean enabled = false;
        private Duration accessTokenTtl = Duration.ofMinutes(15);
    }

    @Getter
    @Setter
    public static class ApiTokens {
        private boolean enabled = false;
        private int maxFailedAttempts = 10;
        private Duration lockDuration = Duration.ofMinutes(15);
        private Duration rotationGrace = Duration.ofHours(24);
    }

    @Getter
    @Setter
    public static class Mfa {
        private List<String> requiredRoles = new ArrayList<>();
        private boolean requiredForAll = false;
        private boolean requiredForAdmin = true;
        private List<String> adminRoles = new ArrayList<>(List.of("ADMIN"));
        private List<String> adminAuthorities = new ArrayList<>(List.of("SSO_ADMIN_*", "SSO_IMPERSONATE", "SSO_AUDIT",
                "SSO_AUDIT_EXPORT", "SSO_API_TOKENS", "users:create", "users:update", "users:delete", "users:disable", "users:enable",
                "roles:create", "roles:update", "roles:delete", "sub_roles:create", "sub_roles:update", "sub_roles:delete"));
        private List<String> enrollmentAllowedRoutes = new ArrayList<>(List.of("GET /api/profile/me"));
        private List<String> enrollmentProfileRoutes = new ArrayList<>(List.of("GET /api/profile/me"));
        private Duration stepUpMaxAge = Duration.ofMinutes(10);
        private Duration mfaTokenTtl = Duration.ofMinutes(5);
        private boolean rememberDevice = false;
        private Duration rememberDeviceTtl = Duration.ofDays(30);
    }

    @Getter
    @Setter
    public static class Session {
        private Duration idleTimeout = Duration.ofHours(8);
        private Duration absoluteTimeout = Duration.ofDays(30);
        private Duration accessTokenTtl = Duration.ofMinutes(15);
        private Duration refreshLifetime = Duration.ofDays(30);
        private boolean bindToDevice = true;
        private boolean cookieSecure = true;
        private String cookieSameSite = "Lax";
    }

    @Getter
    @Setter
    public static class RateLimits {
        private Limit login = new Limit(10, Duration.ofMinutes(1));
        private Limit otp = new Limit(5, Duration.ofMinutes(1));
        private Limit magicLink = new Limit(5, Duration.ofMinutes(10));
        private Limit accountMail = new Limit(5, Duration.ofMinutes(10));
        private Limit mfaVerify = new Limit(10, Duration.ofMinutes(5));
        private Limit token = new Limit(60, Duration.ofMinutes(1));
        private Limit samlSp = new Limit(30, Duration.ofMinutes(1));
        private int lockoutThreshold = 10;
        private Duration lockoutBase = Duration.ofSeconds(30);
        private Duration lockoutMax = Duration.ofHours(1);
    }

    @Getter
    @Setter
    public static class Limit {
        private int max;
        private Duration window;

        public Limit() {
        }

        public Limit(int max, Duration window) {
            this.max = max;
            this.window = window;
        }
    }

    @Getter
    @Setter
    public static class Risk {
        private boolean enabled = false;
        private int stepUpThreshold = 50;
        private int denyThreshold = 90;
        private int newDeviceScore = 30;
        private int newIpRangeScore = 20;
        private int impossibleTravelScore = 60;
        private int failureVelocityScore = 40;
        private boolean signalOverride = false;
        private Duration profileRetention = Duration.ofDays(180);
    }

    @Getter
    @Setter
    public static class Audit {
        private Duration checkpointInterval = Duration.ofMinutes(5);
        private Duration retention = Duration.ofDays(365);
        private Duration purgeInterval = Duration.ofHours(1);
    }

    @Getter
    @Setter
    public static class Tenants {
        private Map<String, String> plans = new LinkedHashMap<>(Map.of("default", "ENTERPRISE"));
        private String configuredPlan = "PRO";
        private String unknownPlan = "FREE";
    }

    @Getter
    @Setter
    public static class Secrets {
        private String pepperEnv = "NAQQA_SSO_PEPPER";
        private String sealingKeyEnv = "NAQQA_SSO_SEALING_KEY";
        private String sealingKeyIdEnv = "NAQQA_SSO_SEALING_KEY_ID";
        private String auditKeyEnv = "NAQQA_SSO_AUDIT_KEY";
        private String auditPreviousKeysEnv = "NAQQA_SSO_AUDIT_PREVIOUS_KEYS";
        private String signingKeyEnv = "NAQQA_SSO_SIGNING_KEY";
    }

    @Getter
    @Setter
    public static class Client {
        private Boolean enabled;
        private Boolean password;
        private Boolean magicLink;
        private Boolean emailOtp;
        private Boolean smsOtp;
        private Boolean totp;
        private Boolean recovery;
        private Boolean passkey;
        private Boolean biometric;
        private Boolean securityKey;
        private Boolean push;
        private Boolean saml;
        private Boolean samlSp;
        private List<String> socialProviders;
        private List<String> mfaRequiredRoles;
        private Boolean mfaRequiredForAll;
        private List<String> allowedOrigins;
        private List<String> redirectUris;
        private String ldapDirectory;
    }
}
