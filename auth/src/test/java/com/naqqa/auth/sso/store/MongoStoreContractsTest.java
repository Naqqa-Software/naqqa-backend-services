package com.naqqa.auth.sso.store;

import com.naqqa.auth.sso.oidc.MongoClientStore;
import com.naqqa.auth.sso.oidc.MongoConsentStore;
import com.naqqa.auth.sso.oidc.MongoOidcStore;
import com.naqqa.auth.sso.saml.MongoSamlReplayCache;
import com.naqqa.auth.sso.saml.MongoSamlRequestStore;
import com.naqqa.auth.sso.scim.MongoScimGroupStore;
import com.naqqa.auth.sso.scim.MongoScimUserStore;
import com.naqqa.auth.sso.social.MongoOAuthStateStore;
import com.naqqa.sso.audit.AuditStore;
import com.naqqa.sso.contract.ApiTokenStoreContract;
import com.naqqa.sso.contract.ChallengeStoreContract;
import com.naqqa.sso.contract.CredentialStoreContract;
import com.naqqa.sso.contract.LedgerStoresContract;
import com.naqqa.sso.contract.MagicLinkStoreContract;
import com.naqqa.sso.contract.OidcStoreContract;
import com.naqqa.sso.contract.OtpStoreContract;
import com.naqqa.sso.contract.PushApprovalStoreContract;
import com.naqqa.sso.contract.RecoveryStoreContract;
import com.naqqa.sso.contract.RefreshStoreContract;
import com.naqqa.sso.contract.SamlRequestStoreContract;
import com.naqqa.sso.contract.ScimStoreContract;
import com.naqqa.sso.contract.SessionStoreContract;
import com.naqqa.sso.contract.SingleUseStoresContract;
import com.naqqa.sso.contract.TotpStoresContract;
import com.naqqa.sso.mfa.RecoveryStore;
import com.naqqa.sso.mfa.TotpCredentialStore;
import com.naqqa.sso.mfa.TotpStepStore;
import com.naqqa.sso.oauth.StateStore;
import com.naqqa.sso.oidc.ClientStore;
import com.naqqa.sso.oidc.ConsentStore;
import com.naqqa.sso.oidc.OidcStore;
import com.naqqa.sso.otp.MagicLinkStore;
import com.naqqa.sso.otp.OtpStore;
import com.naqqa.sso.otp.PushApprovalStore;
import com.naqqa.sso.ratelimit.RateLimitStore;
import com.naqqa.sso.saml.ReplayCache;
import com.naqqa.sso.saml.sp.SamlRequestStore;
import com.naqqa.sso.scim.ScimGroupStore;
import com.naqqa.sso.scim.ScimUserStore;
import com.naqqa.sso.session.RefreshStore;
import com.naqqa.sso.session.SessionStore;
import com.naqqa.sso.tokens.ApiTokenStore;
import com.naqqa.sso.webauthn.ChallengeStore;
import com.naqqa.sso.webauthn.CredentialStore;
import org.junit.jupiter.api.Nested;

import java.time.Duration;
import java.util.List;

class MongoStoreContractsTest extends MongoStoreTestBase {

    @Nested
    class ApiTokens extends ApiTokenStoreContract {
        @Override
        protected ApiTokenStore newStore() {
            return new MongoApiTokenStore(template);
        }
    }

    @Nested
    class Sessions extends SessionStoreContract {
        @Override
        protected SessionStore newStore() {
            return new MongoSessionStore(template);
        }
    }

    @Nested
    class Refresh extends RefreshStoreContract {
        @Override
        protected RefreshStore newStore() {
            return new MongoRefreshStore(template, CLOCK, Duration.ofDays(1));
        }
    }

    @Nested
    class Otp extends OtpStoreContract {
        @Override
        protected OtpStore newStore() {
            return new MongoOtpStore(template);
        }
    }

    @Nested
    class MagicLinks extends MagicLinkStoreContract {
        @Override
        protected MagicLinkStore newStore() {
            return new MongoMagicLinkStore(template, CLOCK);
        }
    }

    @Nested
    class Push extends PushApprovalStoreContract {
        @Override
        protected PushApprovalStore newStore() {
            return new MongoPushApprovalStore(template);
        }
    }

    @Nested
    class Challenges extends ChallengeStoreContract {
        @Override
        protected ChallengeStore newStore() {
            return new MongoChallengeStore(template, CLOCK);
        }
    }

    @Nested
    class Credentials extends CredentialStoreContract {
        @Override
        protected CredentialStore newStore() {
            return new MongoCredentialStore(template);
        }
    }

    @Nested
    class Totp extends TotpStoresContract {
        @Override
        protected TotpStepStore newStepStore() {
            return new MongoTotpStepStore(template, CLOCK);
        }

        @Override
        protected TotpCredentialStore newCredentialStore() {
            return new MongoTotpCredentialStore(template);
        }
    }

    @Nested
    class Recovery extends RecoveryStoreContract {
        @Override
        protected RecoveryStore newStore() {
            return new MongoRecoveryStore(template);
        }
    }

    @Nested
    class SingleUse extends SingleUseStoresContract {
        @Override
        protected StateStore newStateStore() {
            return new MongoOAuthStateStore(template);
        }

        @Override
        protected ReplayCache newReplayCache() {
            return new MongoSamlReplayCache(template, CLOCK);
        }
    }

    @Nested
    class SamlRequests extends SamlRequestStoreContract {
        @Override
        protected SamlRequestStore newStore() {
            return new MongoSamlRequestStore(template, CLOCK);
        }
    }

    @Nested
    class Oidc extends OidcStoreContract {
        @Override
        protected OidcStore newStore() {
            return new MongoOidcStore(template, CLOCK, Duration.ofDays(30));
        }
    }

    @Nested
    class Scim extends ScimStoreContract {
        @Override
        protected ScimUserStore newUserStore() {
            return new MongoScimUserStore(template);
        }

        @Override
        protected ScimGroupStore newGroupStore() {
            return new MongoScimGroupStore(template);
        }
    }

    @Nested
    class Ledgers extends LedgerStoresContract {
        @Override
        protected AuditStore newAuditStore() {
            return new MongoAuditStore(template);
        }

        @Override
        protected RateLimitStore newRateLimitStore() {
            return new MongoRateLimitStore(template, CLOCK, Duration.ofDays(1));
        }

        @Override
        protected ConsentStore newConsentStore() {
            return new MongoConsentStore(template);
        }

        @Override
        protected ClientStore newClientStore() {
            return new MongoClientStore(template, List.of());
        }
    }
}
