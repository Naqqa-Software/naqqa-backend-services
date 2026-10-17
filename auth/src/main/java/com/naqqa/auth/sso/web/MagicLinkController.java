package com.naqqa.auth.sso.web;

import com.naqqa.auth.config.auth.EmailMessages;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.repository.UserRepository;
import com.naqqa.auth.security.CookieUtils;
import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.auth.sso.flow.AssuranceEngine;
import com.naqqa.auth.sso.flow.SecondFactors;
import com.naqqa.auth.sso.flow.SsoAccounts;
import com.naqqa.auth.sso.flow.SsoAudit;
import com.naqqa.auth.sso.flow.SsoLoginFlow;
import com.naqqa.auth.sso.store.MongoMagicLinkStore;
import com.naqqa.auth.sso.store.MongoRateLimitStore;
import com.naqqa.auth.sso.support.SsoApiException;
import com.naqqa.auth.sso.support.SsoWeb;
import com.naqqa.sso.SsoException;
import com.naqqa.sso.otp.Channel;
import com.naqqa.sso.otp.MagicLink;
import com.naqqa.sso.otp.Transport;
import com.naqqa.sso.ratelimit.Limit;
import com.naqqa.sso.ratelimit.LoginThrottle;
import com.naqqa.sso.ratelimit.SlidingWindowLimiter;
import com.naqqa.sso.util.Bytes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("${auth.api.base-path:/api/auth}/magic-link")
@ConditionalOnProperty(prefix = "naqqa.sso", name = "enabled", havingValue = "true")
public class MagicLinkController {

    public static final String BINDING_COOKIE = "sso_ml_bind";

    private final SsoLoginFlow loginFlow;
    private final UserRepository userRepository;
    private final MongoMagicLinkStore store;
    private final Transport transport;
    private final SsoAudit audit;
    private final SsoProperties properties;
    private final EmailMessages emailMessages;
    private final LoginThrottle throttle;
    private final Clock clock;
    private final SlidingWindowLimiter requestLimiter;
    private final SlidingWindowLimiter addressLimiter;
    private final SsoAccounts accounts;

    public MagicLinkController(SsoLoginFlow loginFlow, UserRepository userRepository, MongoMagicLinkStore store, Transport transport,
                               SsoAudit audit, SsoProperties properties, EmailMessages emailMessages, LoginThrottle throttle,
                               MongoRateLimitStore limits, Clock clock, SsoAccounts accounts) {
        this.accounts = accounts;
        this.loginFlow = loginFlow;
        this.userRepository = userRepository;
        this.store = store;
        this.transport = transport;
        this.audit = audit;
        this.properties = properties;
        this.emailMessages = emailMessages;
        this.throttle = throttle;
        this.clock = clock;
        this.requestLimiter = new SlidingWindowLimiter(limits, clock, properties.getRateLimits().getMagicLink().getMax(),
                properties.getRateLimits().getMagicLink().getWindow());
        this.addressLimiter = new SlidingWindowLimiter(limits, clock, properties.getRateLimits().getAccountMail().getMax(),
                properties.getRateLimits().getAccountMail().getWindow());
    }

    @PostMapping("/request")
    public ResponseEntity<Map<String, Object>> request(@RequestBody SsoRequests.MagicLinkRequest body, HttpServletRequest request,
                                                       HttpServletResponse response) {
        SsoLoginFlow.Context ctx = loginFlow.context(request, response, null, null, false);
        if (!AssuranceEngine.enabled(ctx.policy(), AssuranceEngine.MAGIC_LINK)) {
            throw SsoApiException.disabled("magicLink");
        }
        if (body == null || body.email() == null || body.email().isBlank() || body.email().length() > 320) {
            throw SsoApiException.badRequest("INVALID_REQUEST", "email is required");
        }
        String redirect = body.redirect() == null || body.redirect().isBlank() ? "/" : body.redirect();
        if (!SsoWeb.isSafeRelativeRedirect(redirect)) {
            throw SsoApiException.badRequest("INVALID_REDIRECT", "redirect must be a relative path");
        }
        Limit limit = requestLimiter.tryAcquire("magic-request:" + ctx.ip());
        if (!limit.allowed()) {
            throw SsoApiException.rateLimited((limit.retryAfterMillis() + 999) / 1000);
        }
        String binding = null;
        if (properties.getMagicLink().isBindToBrowser()) {
            binding = Bytes.randomToken(32);
            CookieUtils.setClientCookie(response, BINDING_COOKIE, binding, (int) properties.getMagicLink().getTtl().getSeconds(), true);
        }
        boolean withinAddressCap = addressLimiter.tryAcquire("magic-account:" + SsoLoginFlow.accountRef(body.email())).allowed();
        UserEntity user = userRepository.findByEmailAnyCase(body.email()).orElse(null);
        if (user != null && user.isEnabled() && withinAddressCap) {
            MagicLink links = new MagicLink(clock, store, Set.of(redirect), properties.getMagicLink().getTtl());
            MagicLink.Issued issued = links.issue(String.valueOf(user.getId()), redirect, binding);
            String link = emailMessages.getAppUrl() + "/auth/magic-link?token=" + URLEncoder.encode(issued.token(), StandardCharsets.UTF_8);
            transport.send(Channel.EMAIL, user.getEmail(), "magic_link", Map.of("link", link,
                    "ttlMinutes", Long.toString(properties.getMagicLink().getTtl().toMinutes())));
            audit.record("magic_link.request", String.valueOf(user.getId()), String.valueOf(user.getId()), "accepted", Map.of());
        } else {
            audit.record("magic_link.request", null, SsoLoginFlow.accountRef(body.email()), "accepted", Map.of());
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("status", "sent"));
    }

    @PostMapping("/consume")
    public Map<String, Object> consume(@RequestBody SsoRequests.MagicLinkConsume body, HttpServletRequest request, HttpServletResponse response) {
        SsoLoginFlow.Context ctx = loginFlow.context(request, response, null, null, false);
        if (!AssuranceEngine.enabled(ctx.policy(), AssuranceEngine.MAGIC_LINK)) {
            throw SsoApiException.disabled("magicLink");
        }
        String key = "magic:" + ctx.ip();
        loginFlow.guard(throttle.check(key, ctx.ip(), false));
        if (body == null || body.token() == null) {
            throw SsoApiException.badRequest("INVALID_REQUEST", "token is required");
        }
        String binding = properties.getMagicLink().isBindToBrowser() ? SsoWeb.cookie(request, BINDING_COOKIE) : null;
        MagicLink.Consumed consumed;
        try {
            consumed = new MagicLink(clock, store, Set.of(), properties.getMagicLink().getTtl()).consume(body.token(), binding);
        } catch (SsoException e) {
            throttle.recordFailure(key, ctx.ip());
            audit.record("login.magic_link", null, null, "failure", Map.of("reason", e.code()));
            throw SsoApiException.unauthorized("MAGIC_LINK_INVALID", "The link is invalid or expired");
        }
        String redirect = SsoWeb.isSafeRelativeRedirect(consumed.redirect()) ? consumed.redirect() : "/";
        UserEntity user = loginFlow.loadUser(consumed.userId());
        if (user == null || !user.isEnabled()) {
            throw SsoApiException.unauthorized("MAGIC_LINK_INVALID", "The link is invalid or expired");
        }
        CookieUtils.setClientCookie(response, BINDING_COOKIE, "", 0, true);
        accounts.markEmailVerified(user);
        String userId = String.valueOf(user.getId());
        audit.record("login.magic_link", userId, userId, "success", Map.of());
        Map<String, Object> out = new LinkedHashMap<>(loginFlow.afterFirstFactor(user, List.of("otp"), ctx, response, Set.of(SecondFactors.EMAIL_OTP)));
        out.put("redirect", redirect);
        return out;
    }
}
