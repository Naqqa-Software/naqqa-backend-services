package com.naqqa.sso.contract;

import com.naqqa.sso.audit.AuditStore;
import com.naqqa.sso.audit.InMemoryAuditStore;
import com.naqqa.sso.mfa.InMemoryRecoveryStore;
import com.naqqa.sso.mfa.InMemoryTotpCredentialStore;
import com.naqqa.sso.mfa.InMemoryTotpStepStore;
import com.naqqa.sso.mfa.RecoveryStore;
import com.naqqa.sso.mfa.TotpCredentialStore;
import com.naqqa.sso.mfa.TotpStepStore;
import com.naqqa.sso.oauth.InMemoryStateStore;
import com.naqqa.sso.oauth.StateStore;
import com.naqqa.sso.oidc.ClientStore;
import com.naqqa.sso.oidc.ConsentStore;
import com.naqqa.sso.oidc.InMemoryClientStore;
import com.naqqa.sso.oidc.InMemoryConsentStore;
import com.naqqa.sso.oidc.InMemoryOidcStore;
import com.naqqa.sso.oidc.OidcStore;
import com.naqqa.sso.otp.InMemoryMagicLinkStore;
import com.naqqa.sso.otp.InMemoryOtpStore;
import com.naqqa.sso.otp.InMemoryPushApprovalStore;
import com.naqqa.sso.otp.MagicLinkStore;
import com.naqqa.sso.otp.OtpStore;
import com.naqqa.sso.otp.PushApprovalStore;
import com.naqqa.sso.ratelimit.InMemoryRateLimitStore;
import com.naqqa.sso.ratelimit.RateLimitStore;
import com.naqqa.sso.saml.InMemoryReplayCache;
import com.naqqa.sso.saml.ReplayCache;
import com.naqqa.sso.saml.sp.InMemorySamlRequestStore;
import com.naqqa.sso.saml.sp.SamlRequestStore;
import com.naqqa.sso.scim.InMemoryScimGroupStore;
import com.naqqa.sso.scim.InMemoryScimUserStore;
import com.naqqa.sso.scim.ScimGroupStore;
import com.naqqa.sso.scim.ScimUserStore;
import com.naqqa.sso.session.InMemoryRefreshStore;
import com.naqqa.sso.session.InMemorySessionStore;
import com.naqqa.sso.session.RefreshStore;
import com.naqqa.sso.session.SessionStore;
import com.naqqa.sso.tokens.ApiTokenStore;
import com.naqqa.sso.tokens.InMemoryApiTokenStore;
import com.naqqa.sso.webauthn.ChallengeStore;
import com.naqqa.sso.webauthn.CredentialStore;
import com.naqqa.sso.webauthn.InMemoryChallengeStore;
import com.naqqa.sso.webauthn.InMemoryCredentialStore;
import org.junit.jupiter.api.Nested;

import java.time.Clock;

class InMemoryStoreContractsTest {

    @Nested
    class ApiTokens extends ApiTokenStoreContract {
        @Override
        protected ApiTokenStore newStore() {
            return new InMemoryApiTokenStore();
        }
    }

    @Nested
    class Sessions extends SessionStoreContract {
        @Override
        protected SessionStore newStore() {
            return new InMemorySessionStore();
        }
    }

    @Nested
    class Refresh extends RefreshStoreContract {
        @Override
        protected RefreshStore newStore() {
            return new InMemoryRefreshStore();
        }
    }

    @Nested
    class Otp extends OtpStoreContract {
        @Override
        protected OtpStore newStore() {
            return new InMemoryOtpStore();
        }
    }

    @Nested
    class MagicLinks extends MagicLinkStoreContract {
        @Override
        protected MagicLinkStore newStore() {
            return new InMemoryMagicLinkStore();
        }
    }

    @Nested
    class Push extends PushApprovalStoreContract {
        @Override
        protected PushApprovalStore newStore() {
            return new InMemoryPushApprovalStore();
        }
    }

    @Nested
    class Challenges extends ChallengeStoreContract {
        @Override
        protected ChallengeStore newStore() {
            return new InMemoryChallengeStore(Clock.systemUTC());
        }
    }

    @Nested
    class Credentials extends CredentialStoreContract {
        @Override
        protected CredentialStore newStore() {
            return new InMemoryCredentialStore();
        }
    }

    @Nested
    class Totp extends TotpStoresContract {
        @Override
        protected TotpStepStore newStepStore() {
            return new InMemoryTotpStepStore();
        }

        @Override
        protected TotpCredentialStore newCredentialStore() {
            return new InMemoryTotpCredentialStore();
        }
    }

    @Nested
    class Recovery extends RecoveryStoreContract {
        @Override
        protected RecoveryStore newStore() {
            return new InMemoryRecoveryStore();
        }
    }

    @Nested
    class SingleUse extends SingleUseStoresContract {
        @Override
        protected StateStore newStateStore() {
            return new InMemoryStateStore(Clock.systemUTC());
        }

        @Override
        protected ReplayCache newReplayCache() {
            return new InMemoryReplayCache(Clock.systemUTC());
        }
    }

    @Nested
    class SamlRequests extends SamlRequestStoreContract {
        @Override
        protected SamlRequestStore newStore() {
            return new InMemorySamlRequestStore(Clock.systemUTC());
        }
    }

    @Nested
    class Oidc extends OidcStoreContract {
        @Override
        protected OidcStore newStore() {
            return new InMemoryOidcStore();
        }
    }

    @Nested
    class Scim extends ScimStoreContract {
        @Override
        protected ScimUserStore newUserStore() {
            return new InMemoryScimUserStore();
        }

        @Override
        protected ScimGroupStore newGroupStore() {
            return new InMemoryScimGroupStore();
        }
    }

    @Nested
    class Ledgers extends LedgerStoresContract {
        @Override
        protected AuditStore newAuditStore() {
            return new InMemoryAuditStore();
        }

        @Override
        protected RateLimitStore newRateLimitStore() {
            return new InMemoryRateLimitStore();
        }

        @Override
        protected ConsentStore newConsentStore() {
            return new InMemoryConsentStore();
        }

        @Override
        protected ClientStore newClientStore() {
            return new InMemoryClientStore();
        }
    }
}
