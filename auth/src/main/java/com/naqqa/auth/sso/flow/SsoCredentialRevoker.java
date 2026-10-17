package com.naqqa.auth.sso.flow;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.repository.redis.PasswordResetRepository;
import com.naqqa.auth.service.security.CredentialChangeListener;
import com.naqqa.auth.service.security.RefreshTokenManager;
import com.naqqa.auth.sso.oidc.BackchannelLogoutDispatcher;
import com.naqqa.auth.sso.oidc.MongoOidcStore;
import com.naqqa.auth.sso.store.MongoMagicLinkStore;
import com.naqqa.auth.sso.store.MongoOtpStore;
import com.naqqa.auth.sso.store.MongoPushApprovalStore;
import com.naqqa.auth.sso.support.SsoTicketStore;
import com.naqqa.sso.SsoException;
import com.naqqa.sso.oidc.BackchannelLogout;
import com.naqqa.sso.oidc.GrantRecord;
import com.naqqa.sso.oidc.OidcProvider;
import com.naqqa.sso.otp.Channel;
import com.naqqa.sso.session.Session;
import com.naqqa.sso.session.SessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "naqqa.sso", name = "enabled", havingValue = "true")
public class SsoCredentialRevoker implements CredentialChangeListener {

    private final SsoSessionManager sessions;
    private final RefreshTokenManager refreshTokens;
    private final SsoTicketStore tickets;
    private final MfaTokenService mfaTokens;
    private final MongoMagicLinkStore magicLinks;
    private final MongoOtpStore otps;
    private final MongoPushApprovalStore pushes;
    private final ObjectProvider<OidcProvider> oidc;
    private final ObjectProvider<BackchannelLogoutDispatcher> backchannel;
    private final ObjectProvider<MongoOidcStore> oidcStore;
    private final SessionService sessionService;
    private final SsoAudit audit;
    private final ObjectProvider<PasswordResetRepository> passwordResets;

    public SsoCredentialRevoker(SsoSessionManager sessions, RefreshTokenManager refreshTokens, SsoTicketStore tickets,
                                MfaTokenService mfaTokens, MongoMagicLinkStore magicLinks, MongoOtpStore otps,
                                MongoPushApprovalStore pushes, ObjectProvider<OidcProvider> oidc,
                                ObjectProvider<BackchannelLogoutDispatcher> backchannel, ObjectProvider<MongoOidcStore> oidcStore,
                                SessionService sessionService, SsoAudit audit,
                                ObjectProvider<PasswordResetRepository> passwordResets) {
        this.sessions = sessions;
        this.refreshTokens = refreshTokens;
        this.tickets = tickets;
        this.mfaTokens = mfaTokens;
        this.magicLinks = magicLinks;
        this.otps = otps;
        this.pushes = pushes;
        this.oidc = oidc;
        this.backchannel = backchannel;
        this.oidcStore = oidcStore;
        this.sessionService = sessionService;
        this.audit = audit;
        this.passwordResets = passwordResets;
    }

    @Override
    public void onCredentialsChanged(UserEntity user, String reason) {
        revoke(user, null, reason);
    }

    public void revoke(UserEntity user, String keepSessionId, String reason) {
        String userId = String.valueOf(user.getId());
        int oidcSessions = endOidcSessions(userId, keepSessionId);
        int oidcGrants = revokeOidcGrants(userId);
        int ssoSessions = sessions.revokeOthers(userId, keepSessionId);
        int impersonations = sessions.endImpersonations(userId);
        refreshTokens.revokeAllExcept(user, keepSessionId);
        tickets.removeAllForUser(userId);
        mfaTokens.revokeIssuedBefore(userId);
        magicLinks.deleteByUser(userId);
        pushes.deleteByUser(userId);
        otps.deleteByPurposes(otpPurposes(user));
        deleteReset(user.getEmail());
        audit.record("credentials.revoke_all", userId, userId, "success", Map.of(
                "reason", reason == null ? "unspecified" : reason,
                "keptCurrent", Boolean.toString(keepSessionId != null),
                "ssoSessions", Integer.toString(ssoSessions),
                "impersonations", Integer.toString(impersonations),
                "oidcSessions", Integer.toString(oidcSessions),
                "oidcGrants", Integer.toString(oidcGrants)));
    }

    public void revokeAfterAddressChange(UserEntity user, String previousEmail, String reason) {
        if (previousEmail != null && !previousEmail.equals(user.getEmail())) {
            otps.deleteByPurposes(List.of(SecondFactors.loginPurpose(Channel.EMAIL, previousEmail)));
            deleteReset(previousEmail);
        }
        revoke(user, null, reason);
    }

    private void deleteReset(String email) {
        PasswordResetRepository resets = passwordResets.getIfAvailable();
        if (resets != null && email != null) {
            resets.deleteById(email);
        }
    }

    public int revokeOidcGrants(String userId) {
        MongoOidcStore store = oidcStore.getIfAvailable();
        OidcProvider provider = oidc.getIfAvailable();
        if (store == null || provider == null) {
            return 0;
        }
        int revoked = 0;
        for (GrantRecord g : store.grantsForUser(userId)) {
            provider.revokeGrant(g.grantId());
            if (!g.revoked()) {
                revoked++;
            }
        }
        return revoked;
    }

    public int endOidcSession(String userId, String idHash) {
        return idHash == null ? 0 : endOidcSessions(userId, "", idHash);
    }

    public int endOidcSessions(String userId, String keepSessionId) {
        return endOidcSessions(userId, keepSessionId, null);
    }

    private int endOidcSessions(String userId, String keepSessionId, String only) {
        OidcProvider provider = oidc.getIfAvailable();
        if (provider == null) {
            return 0;
        }
        List<BackchannelLogout> logouts = new ArrayList<>();
        int ended = 0;
        try {
            if (keepSessionId == null) {
                ended = sessionService.list(userId).size();
                logouts.addAll(provider.endAllSessions(userId));
            } else {
                for (Session s : sessionService.list(userId)) {
                    if (!s.idHash().equals(keepSessionId) && (only == null || only.equals(s.idHash()))) {
                        logouts.addAll(provider.sessionEnded(s));
                        ended++;
                    }
                }
            }
        } catch (SsoException e) {
            log.warn("oidc end sessions failed: {}", e.code());
        }
        BackchannelLogoutDispatcher dispatcher = backchannel.getIfAvailable();
        if (dispatcher != null) {
            dispatcher.dispatch(logouts);
        }
        return ended;
    }

    static List<String> otpPurposes(UserEntity user) {
        List<String> purposes = new ArrayList<>();
        purposes.add(SecondFactors.mfaPurpose(String.valueOf(user.getId())));
        if (user.getEmail() != null) {
            purposes.add(SecondFactors.loginPurpose(Channel.EMAIL, user.getEmail()));
        }
        if (user.getPhoneNumber() != null) {
            purposes.add(SecondFactors.loginPurpose(Channel.SMS, user.getPhoneNumber()));
        }
        return purposes;
    }
}
