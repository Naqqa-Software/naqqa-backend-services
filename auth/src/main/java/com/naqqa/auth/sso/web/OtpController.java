package com.naqqa.auth.sso.web;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.repository.UserRepository;
import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.auth.sso.flow.AssuranceEngine;
import com.naqqa.auth.sso.flow.SecondFactors;
import com.naqqa.auth.sso.flow.SsoAccounts;
import com.naqqa.auth.sso.flow.SsoAudit;
import com.naqqa.auth.sso.flow.SsoLoginFlow;
import com.naqqa.auth.sso.store.MongoRateLimitStore;
import com.naqqa.auth.sso.support.SsoApiException;
import com.naqqa.sso.otp.Channel;
import com.naqqa.sso.ratelimit.Limit;
import com.naqqa.sso.ratelimit.SlidingWindowLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("${auth.api.base-path:/api/auth}/otp")
@ConditionalOnProperty(prefix = "naqqa.sso", name = "enabled", havingValue = "true")
public class OtpController {

    private final SecondFactors factors;
    private final SsoLoginFlow loginFlow;
    private final UserRepository userRepository;
    private final SsoAudit audit;
    private final SlidingWindowLimiter requestLimiter;
    private final SsoAccounts accounts;

    public OtpController(SecondFactors factors, SsoLoginFlow loginFlow, UserRepository userRepository,
                         SsoAudit audit, MongoRateLimitStore store, Clock clock, SsoProperties properties, SsoAccounts accounts) {
        this.accounts = accounts;
        this.factors = factors;
        this.loginFlow = loginFlow;
        this.userRepository = userRepository;
        this.audit = audit;
        this.requestLimiter = new SlidingWindowLimiter(store, clock, properties.getRateLimits().getOtp().getMax(),
                properties.getRateLimits().getOtp().getWindow());
    }

    static Channel channel(String value) {
        if ("email".equalsIgnoreCase(value)) {
            return Channel.EMAIL;
        }
        if ("sms".equalsIgnoreCase(value)) {
            return Channel.SMS;
        }
        throw SsoApiException.badRequest("INVALID_CHANNEL", "channel must be email or sms");
    }

    private UserEntity lookup(Channel channel, String destination) {
        String norm = SecondFactors.normalizeDestination(channel, destination);
        return (channel == Channel.EMAIL ? userRepository.findByEmailAnyCase(destination) : userRepository.findByPhoneNumber(norm))
                .orElse(null);
    }

    private void checkEnabled(Channel channel, SsoLoginFlow.Context ctx) {
        if (!AssuranceEngine.enabled(ctx.policy(), channel == Channel.EMAIL ? SecondFactors.EMAIL_OTP : SecondFactors.SMS_OTP)) {
            throw SsoApiException.disabled(channel == Channel.EMAIL ? "emailOtp" : "smsOtp");
        }
    }

    @PostMapping("/request")
    public ResponseEntity<Map<String, Object>> request(@RequestBody SsoRequests.OtpRequest body, HttpServletRequest request,
                                                       HttpServletResponse response) {
        if (body == null || body.destination() == null || body.destination().isBlank() || body.destination().length() > 320) {
            throw SsoApiException.badRequest("INVALID_REQUEST", "channel and destination are required");
        }
        Channel channel = channel(body.channel());
        SsoLoginFlow.Context ctx = loginFlow.context(request, response, null, null, false);
        checkEnabled(channel, ctx);
        Limit limit = requestLimiter.tryAcquire("otp-request:" + ctx.ip());
        if (!limit.allowed()) {
            throw SsoApiException.rateLimited((limit.retryAfterMillis() + 999) / 1000);
        }
        UserEntity user = lookup(channel, body.destination());
        boolean known = user != null && user.canSignIn();
        factors.requestOtp(channel, body.destination(), SecondFactors.loginPurpose(channel, body.destination()), known, response);
        audit.record("otp.request", known ? String.valueOf(user.getId()) : null,
                known ? String.valueOf(user.getId()) : SsoLoginFlow.accountRef(body.destination()), "accepted", Map.of("channel", channel.name()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("status", "sent"));
    }

    @PostMapping("/verify")
    public Map<String, Object> verify(@RequestBody SsoRequests.OtpVerify body, HttpServletRequest request, HttpServletResponse response) {
        if (body == null || body.destination() == null || body.code() == null) {
            throw SsoApiException.badRequest("INVALID_REQUEST", "channel, destination and code are required");
        }
        Channel channel = channel(body.channel());
        SsoLoginFlow.Context ctx = loginFlow.context(request, response, null, null, false);
        checkEnabled(channel, ctx);
        String key = "otp:" + SecondFactors.normalizeDestination(channel, body.destination());
        UserEntity user = loginFlow.attempt(key, ctx.ip(), () -> {
            boolean ok = factors.verifyOtp(channel, SecondFactors.loginPurpose(channel, body.destination()), body.code(), request, response);
            UserEntity found = ok ? lookup(channel, body.destination()) : null;
            return found == null || !found.canSignIn() ? null : found;
        });
        if (user == null) {
            audit.record("login.otp", null, SsoLoginFlow.accountRef(body.destination()), "failure", Map.of("channel", channel.name()));
            throw SsoApiException.unauthorized("OTP_INVALID", "The code is invalid or expired");
        }
        if (channel == Channel.EMAIL) {
            accounts.markEmailVerified(user);
        }
        String userId = String.valueOf(user.getId());
        audit.record("login.otp", userId, userId, "success", Map.of("channel", channel.name()));
        String method = channel == Channel.EMAIL ? SecondFactors.EMAIL_OTP : SecondFactors.SMS_OTP;
        return loginFlow.afterFirstFactor(user, List.of(channel == Channel.EMAIL ? "otp" : "sms"), ctx, response, Set.of(method));
    }
}
