package com.naqqa.outreach.controller;

import com.naqqa.outreach.dto.OutreachDtos.ProfileDeliverability;
import com.naqqa.outreach.dto.OutreachDtos.ProfileUpsert;
import com.naqqa.outreach.dto.OutreachDtos.ProfileView;
import com.naqqa.outreach.dto.OutreachDtos.OutreachStats;
import com.naqqa.outreach.dto.OutreachDtos.SentEmailRow;
import com.naqqa.outreach.dto.OutreachDtos.SentEmailUpdate;
import com.naqqa.outreach.dto.OutreachDtos.SettingsUpdate;
import com.naqqa.outreach.dto.OutreachDtos.SettingsView;
import com.naqqa.outreach.service.OutreachAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Outreach admin API — the "Emails sent" tracking table plus sender-profile config and a manual
 * run trigger. Guarded by {@code outreach:read} / {@code outreach:manage} (the host declares them;
 * ADMIN holds all).
 */
@RestController
@RequestMapping("/api/outreach")
@RequiredArgsConstructor
public class OutreachController {

    private final OutreachAdminService admin;

    @GetMapping("/sent-emails")
    @PreAuthorize("hasAuthority('outreach:read')")
    public Map<String, Object> sentEmails(@RequestParam(required = false) String profileKey,
                                          @RequestParam(required = false) String email,
                                          @RequestParam(required = false) String company,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "10") int pageSize,
                                          @RequestParam(name = "sortKey", defaultValue = "sentAt") String sortKey,
                                          @RequestParam(name = "sortDir", defaultValue = "desc") String sortDir) {
        return admin.sentEmails(profileKey, email, company, status, page, pageSize, sortKey, sortDir);
    }

    @PutMapping("/sent-emails/{id}")
    @PreAuthorize("hasAuthority('outreach:manage')")
    public SentEmailRow updateSentEmail(@PathVariable String id, @RequestBody SentEmailUpdate body) {
        return admin.updateSentEmail(id, body);
    }

    @DeleteMapping("/sent-emails/{id}")
    @PreAuthorize("hasAuthority('outreach:manage')")
    public void deleteSentEmail(@PathVariable String id) {
        admin.deleteSentEmail(id);
    }

    @GetMapping("/profiles")
    @PreAuthorize("hasAuthority('outreach:read')")
    public List<ProfileView> profiles() {
        return admin.listProfiles();
    }

    /** Per-sender deliverability (7-day sent/bounced + rate, throttle state, today's cap vs sent). */
    @GetMapping("/deliverability")
    @PreAuthorize("hasAuthority('outreach:read')")
    public List<ProfileDeliverability> deliverability() {
        return admin.deliverability();
    }

    @PutMapping("/profiles/{key}")
    @PreAuthorize("hasAuthority('outreach:manage')")
    public ProfileView upsert(@PathVariable String key, @RequestBody ProfileUpsert body) {
        return admin.upsertProfile(key, body);
    }

    @PostMapping("/run/{key}")
    @PreAuthorize("hasAuthority('outreach:manage')")
    public Map<String, Object> runNow(@PathVariable String key) {
        admin.runNow(key);
        return Map.of("started", true, "profile", key);
    }

    /** Deep inbox scan across all profiles (default 180 days) — catches replies to older campaigns. */
    @PostMapping("/inbox-scan")
    @PreAuthorize("hasAuthority('outreach:manage')")
    public Map<String, Object> deepScan(@RequestParam(defaultValue = "180") int days) {
        int profiles = admin.deepScanInbox(days);
        return Map.of("started", true, "profiles", profiles, "days", days);
    }

    @GetMapping("/settings")
    @PreAuthorize("hasAuthority('outreach:read')")
    public SettingsView settings() {
        return admin.settings();
    }

    @PutMapping("/settings")
    @PreAuthorize("hasAuthority('outreach:manage')")
    public SettingsView updateSettings(@RequestBody SettingsUpdate body) {
        return admin.updateSettings(body);
    }

    @GetMapping("/stats")
    @PreAuthorize("hasAuthority('outreach:read')")
    public OutreachStats stats(@RequestParam(required = false) String from,
                               @RequestParam(required = false) String to,
                               @RequestParam(required = false) String profileKey) {
        return admin.stats(dayStart(from), dayEnd(to), profileKey);
    }

    /** Parse an ISO date (yyyy-MM-dd) to the start of that day UTC, or null. */
    private static Instant dayStart(String d) {
        if (d == null || d.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(d.trim()).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    /** Parse an ISO date (yyyy-MM-dd) to the end of that day UTC (inclusive), or null. */
    private static Instant dayEnd(String d) {
        if (d == null || d.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(d.trim()).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusMillis(1);
        } catch (Exception e) {
            return null;
        }
    }
}
