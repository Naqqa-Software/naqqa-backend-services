package com.naqqa.auth.sso.flow;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.security.CookieUtils;
import com.naqqa.auth.service.security.PasswordService;
import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.auth.sso.ldap.DirectoryLogin;
import com.naqqa.auth.sso.store.MongoRateLimitStore;
import com.naqqa.auth.sso.support.SsoApiException;
import com.naqqa.auth.sso.support.SsoWeb;
import com.naqqa.sso.SsoException;
import com.naqqa.sso.mfa.Factor;
import com.naqqa.sso.mfa.RecoveryCodes;
import com.naqqa.sso.mfa.TotpEnrollment;
import com.naqqa.sso.otp.Channel;
import com.naqqa.sso.otp.OtpService;
import com.naqqa.sso.otp.PushApproval;
import com.naqqa.sso.ratelimit.SlidingWindowLimiter;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "naqqa.sso", name = "enabled", havingValue = "true")
public class SecondFactors {

    public static final String TOTP = "totp";
    public static final String HOTP = "hotp";
    public static final String WEBAUTHN = "webauthn";
    public static final String RECOVERY = "recovery";
    public static final String EMAIL_OTP = "email_otp";
    public static final String SMS_OTP = "sms_otp";
    public static final String PUSH = "push";
    public static final String PASSWORD = "password";

    public static final String OTP_COOKIE = "sso_otp";
    public static final String WEBAUTHN_COOKIE = "sso_wa_bind";
    static final int OTP_COOKIE_SECONDS = 15 * 60;
    static final int WEBAUTHN_COOKIE_SECONDS = 10 * 60;

    private final TotpEnrollment totp;
    private final HotpEnrollment hotp;
    private final RecoveryCodes recovery;
    private final OtpService emailOtp;
    private final OtpService smsOtp;
    private final PushApproval push;
    private final WebAuthnService webauthn;
    private final PasswordService passwordService;
    private final ObjectProvider<DirectoryLogin> directories;
    private final SlidingWindowLimiter emailSends;
    private final SlidingWindowLimiter smsSends;

    public SecondFactors(TotpEnrollment totp, HotpEnrollment hotp, RecoveryCodes recovery, @Qualifier("ssoEmailOtpService") OtpService emailOtp,
                         @Qualifier("ssoSmsOtpService") OtpService smsOtp, PushApproval push, WebAuthnService webauthn,
                         PasswordService passwordService, ObjectProvider<DirectoryLogin> directories, MongoRateLimitStore limits, Clock clock,
                         SsoProperties properties) {
        this.totp = totp;
        this.hotp = hotp;
        this.recovery = recovery;
        this.emailOtp = emailOtp;
        this.smsOtp = smsOtp;
        this.push = push;
        this.webauthn = webauthn;
        this.passwordService = passwordService;
        this.directories = directories;
        this.emailSends = new SlidingWindowLimiter(limits, clock, Math.max(1, properties.getEmailOtp().getMaxSendsPerHour()), Duration.ofHours(1));
        this.smsSends = new SlidingWindowLimiter(limits, clock, Math.max(1, properties.getSmsOtp().getMaxSendsPerHour()), Duration.ofHours(1));
    }

    public static String normalizeDestination(Channel channel, String destination) {
        String d = destination == null ? "" : destination.trim();
        return channel == Channel.EMAIL ? d.toLowerCase(Locale.ROOT) : d.replaceAll("[\\s()-]", "");
    }

    public static String loginPurpose(Channel channel, String destination) {
        String norm = normalizeDestination(channel, destination);
        return "login|" + Bytes.b64url(Hashes.sha256(Bytes.utf8(channel + "|" + norm))).substring(0, 22);
    }

    public static String stepUpPurpose(UserEntity user, String method) {
        if (EMAIL_OTP.equals(method)) {
            return loginPurpose(Channel.EMAIL, user.getEmail());
        }
        if (SMS_OTP.equals(method) && user.getPhoneNumber() != null) {
            return loginPurpose(Channel.SMS, user.getPhoneNumber());
        }
        return null;
    }

    public static boolean isRecovery(String method) {
        return RECOVERY.equals(method);
    }

    public static String mfaPurpose(String userId) {
        return "mfa|" + userId;
    }

    public OtpService otp(Channel channel) {
        return channel == Channel.SMS ? smsOtp : emailOtp;
    }

    public void requestOtp(Channel channel, String destination, String purpose, boolean known, HttpServletResponse response) {
        String sendKey = "otp-send:" + Bytes.b64url(Hashes.sha256(Bytes.utf8(channel + "|" + normalizeDestination(channel, destination))));
        if (!(channel == Channel.SMS ? smsSends : emailSends).tryAcquire(sendKey).allowed()) {
            return;
        }
        try {
            OtpService.Requested requested = otp(channel).request(channel, normalizeDestination(channel, destination), purpose, known);
            CookieUtils.setClientCookie(response, OTP_COOKIE, requested.challengeId(), OTP_COOKIE_SECONDS, true);
        } catch (SsoException e) {
            if (!"otp_resend_cooldown".equals(e.code()) && !"otp_daily_cap".equals(e.code())) {
                throw e;
            }
        }
    }

    public boolean verifyOtp(Channel channel, String purpose, String code, HttpServletRequest request, HttpServletResponse response) {
        String challenge = SsoWeb.cookie(request, OTP_COOKIE);
        if (challenge == null || code == null) {
            return false;
        }
        OtpService.Outcome outcome;
        try {
            outcome = otp(channel).verify(challenge, purpose, code.trim());
        } catch (OptimisticLockingFailureException e) {
            return false;
        }
        if (outcome == OtpService.Outcome.OK) {
            CookieUtils.setClientCookie(response, OTP_COOKIE, "", 0, true);
            return true;
        }
        return false;
    }

    public String webauthnBinding(HttpServletRequest request, HttpServletResponse response, boolean fresh, String sessionId) {
        String value = fresh ? null : SsoWeb.cookie(request, WEBAUTHN_COOKIE);
        if (value == null || value.isEmpty()) {
            if (!fresh) {
                throw SsoApiException.badRequest("WEBAUTHN_BINDING_MISSING", "WebAuthn ceremony must start in this browser");
            }
            value = Bytes.randomToken(32);
            CookieUtils.setClientCookie(response, WEBAUTHN_COOKIE, value, WEBAUTHN_COOKIE_SECONDS, true);
        }
        return sessionId == null ? value : value + "|" + sessionId;
    }

    public Map<String, Object> challenge(UserEntity user, String method, HttpServletRequest request, HttpServletResponse response) {
        String userId = String.valueOf(user.getId());
        Map<String, Object> out = new LinkedHashMap<>();
        switch (method) {
            case EMAIL_OTP -> {
                requestOtp(Channel.EMAIL, user.getEmail(), mfaPurpose(userId), true, response);
                out.put("status", "sent");
            }
            case SMS_OTP -> {
                if (user.getPhoneNumber() == null) {
                    throw SsoApiException.badRequest("METHOD_NOT_AVAILABLE", "No phone number on file");
                }
                requestOtp(Channel.SMS, user.getPhoneNumber(), mfaPurpose(userId), true, response);
                out.put("status", "sent");
            }
            case PUSH -> {
                PushApproval.Challenge c = push.create(userId, "user:" + userId);
                out.put("status", "sent");
                out.put("pushId", c.id());
                out.put("number", c.displayNumber());
            }
            case WEBAUTHN -> {
                String binding = webauthnBinding(request, response, true, null);
                return webauthn.authenticationOptions(userId, null, binding, true);
            }
            default -> throw SsoApiException.badRequest("NO_CHALLENGE_NEEDED", "This method does not need a challenge");
        }
        return out;
    }

    public List<String> verify(UserEntity user, String method, String code, String credential, String otpPurpose,
                               HttpServletRequest request, HttpServletResponse response) {
        String userId = String.valueOf(user.getId());
        List<String> amr = new ArrayList<>();
        switch (method == null ? "" : method) {
            case TOTP -> {
                if (code == null || !(totp.verify(userId, code.trim()) || hotp.verify(userId, code.trim()))) {
                    return null;
                }
                amr.add("otp");
            }
            case RECOVERY -> {
                RecoveryCodes.Outcome outcome;
                try {
                    outcome = code == null ? RecoveryCodes.Outcome.INVALID : recovery.redeem(userId, code);
                } catch (OptimisticLockingFailureException e) {
                    outcome = RecoveryCodes.Outcome.INVALID;
                }
                if (outcome == RecoveryCodes.Outcome.THROTTLED) {
                    throw SsoApiException.rateLimited(900);
                }
                if (outcome != RecoveryCodes.Outcome.OK) {
                    return null;
                }
                amr.add("otp");
            }
            case EMAIL_OTP -> {
                if (!verifyOtp(Channel.EMAIL, otpPurpose != null ? otpPurpose : mfaPurpose(userId), code, request, response)) {
                    return null;
                }
                amr.add("otp");
            }
            case SMS_OTP -> {
                if (!verifyOtp(Channel.SMS, otpPurpose != null ? otpPurpose : mfaPurpose(userId), code, request, response)) {
                    return null;
                }
                amr.add("sms");
            }
            case PUSH -> {
                if (code == null || !push.consume(code, userId)) {
                    return null;
                }
                amr.addAll(Factor.PUSH.amr());
            }
            case WEBAUTHN -> {
                if (credential == null) {
                    return null;
                }
                String binding = webauthnBinding(request, response, false, null);
                WebAuthnService.LoginResult r;
                try {
                    r = webauthn.finishAuthentication(credential, binding);
                } catch (SsoException e) {
                    return null;
                }
                if (!userId.equals(r.userId())) {
                    return null;
                }
                amr.addAll(WebAuthnService.amrFor(r.kind(), r.userVerified()));
            }
            case PASSWORD -> {
                DirectoryLogin directory = directories.getIfAvailable();
                String encoded = directory != null && directory.isDirectoryManaged(userId) ? null : user.getPassword();
                if (code == null || !passwordService.matches(code, encoded)) {
                    return null;
                }
                amr.add("pwd");
            }
            default -> throw SsoApiException.badRequest("METHOD_NOT_ALLOWED", "Unsupported method");
        }
        return amr;
    }

    public PushApproval push() {
        return push;
    }

    public TotpEnrollment totp() {
        return totp;
    }

    public HotpEnrollment hotp() {
        return hotp;
    }

    public static String canonical(String method) {
        return HOTP.equals(method) ? TOTP : method;
    }

    public RecoveryCodes recovery() {
        return recovery;
    }
}
