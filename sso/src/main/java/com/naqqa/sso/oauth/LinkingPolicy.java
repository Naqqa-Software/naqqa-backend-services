package com.naqqa.sso.oauth;

import com.naqqa.sso.SsoException;

import java.util.Locale;
import java.util.Optional;

public record LinkingPolicy(boolean autoLinkEnabled) {
    public enum Outcome {
        LOGIN_EXISTING_LINK,
        LINK_TO_AUTHENTICATED_USER,
        LINK_TO_EXISTING_ACCOUNT,
        CREATE_ACCOUNT,
        LINK_REQUIRES_LOGIN
    }

    public record Decision(Outcome outcome, String userId, boolean emailTrusted) {
        public String code() {
            return outcome.name();
        }
    }

    public static LinkingPolicy defaults() {
        return new LinkingPolicy(true);
    }

    public Decision decide(ExternalIdentity identity, ProviderConfig provider, Optional<String> linkedUserId,
                           Optional<String> localUserIdByEmail, String authenticatedUserId) {
        if (!identity.provider().equals(provider.id())) {
            throw new SsoException("oauth.policy_provider_mismatch");
        }
        if (linkedUserId.isPresent()) {
            return new Decision(Outcome.LOGIN_EXISTING_LINK, linkedUserId.get(), false);
        }
        boolean trusted = emailTrusted(identity, provider);
        if (authenticatedUserId != null) {
            return new Decision(Outcome.LINK_TO_AUTHENTICATED_USER, authenticatedUserId, trusted);
        }
        if (localUserIdByEmail.isEmpty()) {
            return new Decision(Outcome.CREATE_ACCOUNT, null, trusted);
        }
        if (autoLinkEnabled && trusted) {
            return new Decision(Outcome.LINK_TO_EXISTING_ACCOUNT, localUserIdByEmail.get(), true);
        }
        return new Decision(Outcome.LINK_REQUIRES_LOGIN, localUserIdByEmail.get(), false);
    }

    public boolean emailTrusted(ExternalIdentity identity, ProviderConfig provider) {
        if (identity.email() == null || !identity.emailVerified()) {
            return false;
        }
        String domain = identity.emailDomain().toLowerCase(Locale.ROOT);
        if (provider.trustAllEmails()) {
            return true;
        }
        if (provider.trustedEmailDomains().contains(domain)) {
            return true;
        }
        if (provider.trustHostedDomain()) {
            Object hd = identity.claims().get("hd");
            return hd instanceof String && ((String) hd).toLowerCase(Locale.ROOT).equals(domain);
        }
        return false;
    }
}
