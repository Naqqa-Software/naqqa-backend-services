package com.naqqa.auth.sso.flow;

import com.naqqa.auth.config.auth.EmailMessages;
import com.naqqa.auth.service.auth.AuthEmailService;
import com.naqqa.sso.otp.Channel;
import com.naqqa.sso.otp.Transport;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
public class AuthTransport implements Transport {

    private final AuthEmailService emailService;
    private final EmailMessages emailMessages;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1000), r -> {
        Thread t = new Thread(r, "sso-transport");
        t.setDaemon(true);
        return t;
    }, new ThreadPoolExecutor.DiscardOldestPolicy());

    public AuthTransport(AuthEmailService emailService, EmailMessages emailMessages) {
        this.emailService = emailService;
        this.emailMessages = emailMessages;
    }

    @Override
    public void send(Channel channel, String destination, String templateId, Map<String, String> vars) {
        Map<String, String> copy = Map.copyOf(vars);
        executor.execute(() -> deliver(channel, destination, templateId, copy));
    }

    private void deliver(Channel channel, String destination, String templateId, Map<String, String> vars) {
        try {
            if (channel == Channel.EMAIL) {
                String template = templateId == null ? "" : templateId.split("\\|", 2)[0];
                emailService.sendEmail(destination, subject(template), body(template, vars));
            } else {
                log.warn("sso transport has no delivery for channel {}, message dropped", channel);
            }
        } catch (RuntimeException e) {
            log.warn("sso transport delivery failed for channel {}", channel);
        }
    }

    private String subject(String template) {
        String platform = emailMessages.getPlatformName();
        if (template.startsWith("magic")) {
            return platform + " sign-in link";
        }
        if (template.startsWith(AccountErasureService.TEMPLATE)) {
            return platform + " account deleted";
        }
        if (template.startsWith(SsoSecurityNotices.TEMPLATE)) {
            return platform + " security alert";
        }
        return platform + " verification code";
    }

    private String body(String template, Map<String, String> vars) {
        if (template.startsWith("magic")) {
            String link = escape(vars.getOrDefault("link", ""));
            return "<p>Use the link below to sign in. It expires soon and works once.</p><p><a href='" + link + "'>Sign in</a></p>";
        }
        if (template.startsWith(AccountErasureService.TEMPLATE)) {
            return "<p>Your account and the personal data linked to it have been deleted. Reference: " + escape(vars.getOrDefault("reference", ""))
                    + ".</p><p>If you did not request this, contact support right away.</p>";
        }
        if (template.startsWith(SsoSecurityNotices.TEMPLATE)) {
            return "<p>" + notice(vars.getOrDefault("event", "")) + "</p><p>If this was not you, contact support right away.</p>";
        }
        return "<p>Your verification code is <b>" + escape(vars.getOrDefault("code", "")) + "</b>.</p><p>It expires in "
                + escape(vars.getOrDefault("ttlMinutes", "10")) + " minutes. If you did not request it, ignore this email.</p>";
    }

    private static String notice(String event) {
        return switch (event) {
            case SsoSecurityNotices.RECOVERY_CODE_USED -> "A recovery code was used to sign in to your account.";
            case SsoSecurityNotices.FACTOR_ADDED -> "A sign-in method was added to your account.";
            case SsoSecurityNotices.FACTOR_REMOVED -> "A sign-in method was removed from your account.";
            case SsoSecurityNotices.RECOVERY_REGENERATED -> "New recovery codes were generated for your account.";
            case SsoSecurityNotices.FACTORS_RESET -> "An administrator reset the sign-in methods of your account.";
            case SsoSecurityNotices.PASSWORD_RESET -> "The password of your account was reset.";
            case SsoSecurityNotices.ACCOUNT_LINKED -> "An external sign-in was linked to your account.";
            default -> "The security settings of your account changed.";
        };
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;").replace("\"", "&quot;");
    }
}
