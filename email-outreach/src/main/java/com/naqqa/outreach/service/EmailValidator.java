package com.naqqa.outreach.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.naming.NameNotFoundException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Email gatekeeper — faithful port of emailValidator.js: clean → format → personal-domain →
 * generic/role prefix → MX record. MX check fails OPEN on timeout/SERVFAIL (only a definitive
 * "no records" rejects), matching the original.
 */
@Service
@Slf4j
public class EmailValidator {

    /** {valid, reason, email}. reason ∈ empty|invalid_format|personal_email|generic_email|no_mx_record|ok. */
    public record Result(boolean valid, String reason, String email) {
    }

    private final Map<String, Boolean> mxCache = new ConcurrentHashMap<>();

    public Result validate(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Result(false, "empty", null);
        }
        String email = clean(raw);
        if (!OutreachConstants.EMAIL_FORMAT.matcher(email).matches()) {
            return new Result(false, "invalid_format", email);
        }
        int at = email.lastIndexOf('@');
        String local = email.substring(0, at);
        String domain = email.substring(at + 1);

        if (OutreachConstants.PERSONAL_DOMAINS.contains(domain)) {
            return new Result(false, "personal_email", email);
        }
        String localAlpha = local.replaceAll("[^a-z]", "");
        if (OutreachConstants.GENERIC_PREFIXES.contains(localAlpha)) {
            return new Result(false, "generic_email", email);
        }
        if (!hasMxRecord(domain)) {
            return new Result(false, "no_mx_record", email);
        }
        return new Result(true, "ok", email);
    }

    /** cleanEmail(): strip mailto:, url-decode, drop ?#, remove whitespace, trim junk, lowercase. */
    public String clean(String raw) {
        String e = raw.trim();
        if (e.toLowerCase().startsWith("mailto:")) {
            e = e.substring(7);
        }
        if (e.matches(".*%[0-9a-fA-F]{2}.*")) {
            try {
                e = URLDecoder.decode(e, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                // keep as-is
            }
        }
        e = e.split("[?#]", 2)[0];
        e = e.replaceAll("\\s+", "");
        e = e.replaceAll("^[^a-zA-Z0-9]+", "").replaceAll("[^a-zA-Z0-9]+$", "");
        return e.toLowerCase();
    }

    /** MX lookup against Google/Cloudflare DNS, 5s timeout, cached; fails open (see class doc). */
    public boolean hasMxRecord(String domain) {
        Boolean cached = mxCache.get(domain);
        if (cached != null) {
            return cached;
        }
        try {
            Hashtable<String, String> env = new Hashtable<>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            env.put("java.naming.provider.url", "dns://8.8.8.8 dns://1.1.1.1 dns://8.8.4.4 dns://1.0.0.1");
            env.put("com.sun.jndi.dns.timeout.initial", "5000");
            env.put("com.sun.jndi.dns.timeout.retries", "1");
            InitialDirContext ctx = new InitialDirContext(env);
            Attributes attrs = ctx.getAttributes(domain, new String[]{"MX"});
            Attribute mx = attrs.get("MX");
            boolean result = mx != null && mx.size() > 0;
            ctx.close();
            mxCache.put(domain, result);
            return result;
        } catch (NameNotFoundException e) {
            mxCache.put(domain, false); // definitive no-records → reject
            return false;
        } catch (Exception e) {
            // timeout / SERVFAIL / network → fail open (do NOT cache)
            return true;
        }
    }
}
