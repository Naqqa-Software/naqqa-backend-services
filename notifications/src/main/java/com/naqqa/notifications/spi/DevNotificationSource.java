package com.naqqa.notifications.spi;

import com.naqqa.notifications.dto.Notification;
import com.naqqa.notifications.dto.NotificationCategory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DevNotificationSource implements NotificationSource {

    private record Seed(NotificationCategory category, String type, String actor, String action,
                        String text, String route, long minutes, boolean seen, Map<String, Object> extra) {
    }

    private static Map<String, Object> extra(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    private static final List<Seed> SEEDS = List.of(
            new Seed(NotificationCategory.BOARD, "TASK_ASSIGNED", "Ana Rusu", "assigned you a task", "Finalize the checkout redesign spec", "/portal/board?task=TSK-412", 3, false, extra("taskId", "TSK-412", "taskTitle", "Checkout redesign spec", "byName", "Ana Rusu")),
            new Seed(NotificationCategory.BOARD, "TASK_COMMENTED", "Ion Vrabie", "commented on your task", "Can we split this into two stories?", "/portal/board?task=TSK-398", 22, false, extra("taskId", "TSK-398", "taskTitle", "Billing retries", "byName", "Ion Vrabie")),
            new Seed(NotificationCategory.BOARD, "TASK_ASSIGNED", "Maria Popa", "moved a task to review", "Sidebar badge counts moved to Review", "/portal/board?task=TSK-377", 95, true, extra("taskId", "TSK-377", "taskTitle", "Sidebar badge counts", "byName", "Maria Popa")),
            new Seed(NotificationCategory.BOARD, "TASK_COMMENTED", "Ana Rusu", "mentioned you in a comment", "@you please review the API contract", "/portal/board?task=TSK-355", 210, true, extra("taskId", "TSK-355", "taskTitle", "API contract", "byName", "Ana Rusu")),
            new Seed(NotificationCategory.BOARD, "TASK_ASSIGNED", "Victor Lungu", "assigned you a task", "Add empty states to the datasets grid", "/portal/board?task=TSK-340", 540, true, extra("taskId", "TSK-340", "taskTitle", "Datasets empty states", "byName", "Victor Lungu")),

            new Seed(NotificationCategory.BUILDER, "COMMENT_ADDED", "Elena Cojocaru", "left a comment", "Hero spacing feels tight on mobile", "/portal/builder?page=home", 8, false, extra("pageName", "Home", "author", "Elena Cojocaru")),
            new Seed(NotificationCategory.BUILDER, "COMMENT_REPLY", "Dan Moraru", "replied to your comment", "Agreed, bumped the top padding to 48px", "/portal/builder?page=home", 40, false, extra("pageName", "Home", "author", "Dan Moraru")),
            new Seed(NotificationCategory.BUILDER, "BUILDER_PUBLISHED", "Dan Moraru", "published a website", "Metro Weekly is now live", "/portal/builder?project=metro-weekly", 150, true, extra("projectName", "Metro Weekly", "pages", 12, "byName", "Dan Moraru")),
            new Seed(NotificationCategory.BUILDER, "COMMENT_ADDED", "Elena Cojocaru", "left a comment", "Footer links point to the old domain", "/portal/builder?page=contact", 320, true, extra("pageName", "Contact", "author", "Elena Cojocaru")),

            new Seed(NotificationCategory.OBSERVABILITY, "ADMIN_MESSAGE", "Sentinel", "opened a new issue", "TypeError: cannot read 'id' of undefined in checkout.service.ts", "/portal/observability?tab=issues", 5, false, extra("message", "New error spike in checkout.service.ts")),
            new Seed(NotificationCategory.OBSERVABILITY, "ADMIN_MESSAGE", "Sentinel", "error rate alert", "HTTP 5xx rate crossed 2% on api-gateway", "/portal/observability?tab=metrics", 48, false, extra("message", "5xx rate above threshold")),
            new Seed(NotificationCategory.OBSERVABILITY, "ADMIN_MESSAGE", "Sentinel", "latency alert", "p95 latency on /api exceeded 800ms", "/portal/observability?tab=metrics", 180, true, extra("message", "p95 latency high")),
            new Seed(NotificationCategory.OBSERVABILITY, "ADMIN_MESSAGE", "Sentinel", "resolved an issue", "TimeoutError after 30000ms marked resolved", "/portal/observability?tab=issues", 600, true, extra("message", "Issue resolved")),

            new Seed(NotificationCategory.CLIENTS, "ADMIN_MESSAGE", "Natalia Frunza", "added a new client", "Darwin SRL was added to your portfolio", "/portal/clients/darwin-srl", 14, false, extra("message", "New client: Darwin SRL")),
            new Seed(NotificationCategory.CLIENTS, "ADMIN_MESSAGE", "Natalia Frunza", "updated a client", "Linella billing contact changed", "/portal/clients/linella", 70, false, extra("message", "Client updated: Linella")),
            new Seed(NotificationCategory.CLIENTS, "ADMIN_MESSAGE", "Sergiu Balan", "left a note", "Kaufland wants a call next week", "/portal/clients/kaufland", 260, true, extra("message", "Note on Kaufland")),
            new Seed(NotificationCategory.CLIENTS, "ADMIN_MESSAGE", "Sergiu Balan", "changed client status", "Orange moved to Active", "/portal/clients/orange", 700, true, extra("message", "Orange status: Active")),

            new Seed(NotificationCategory.OFFERS, "ADMIN_MESSAGE", "Cristina Munteanu", "created an offer", "Q4 maintenance offer drafted for Metro", "/portal/offers/OFR-2201", 18, false, extra("message", "Offer OFR-2201 created")),
            new Seed(NotificationCategory.OFFERS, "ADMIN_MESSAGE", "Client Portal", "offer accepted", "Linella accepted the redesign offer", "/portal/offers/OFR-2188", 90, false, extra("message", "Offer OFR-2188 accepted")),
            new Seed(NotificationCategory.OFFERS, "ADMIN_MESSAGE", "Cristina Munteanu", "offer expiring", "Kaufland offer expires in 2 days", "/portal/offers/OFR-2170", 400, true, extra("message", "Offer OFR-2170 expiring")),
            new Seed(NotificationCategory.OFFERS, "ADMIN_MESSAGE", "Client Portal", "offer declined", "Darwin declined the SEO package", "/portal/offers/OFR-2155", 900, true, extra("message", "Offer OFR-2155 declined")),

            new Seed(NotificationCategory.TENDERS, "TENDER_GREAT_FIT", "Tender Radar", "found a matching tender", "Public portal rebuild for Primaria Chisinau", "/portal/tenders/TND-7781", 11, false, extra("tenderId", "TND-7781", "title", "Public portal rebuild", "fitScore", 92, "country", "MD", "buyer", "Primaria Chisinau")),
            new Seed(NotificationCategory.TENDERS, "TENDER_GREAT_FIT", "Tender Radar", "found a matching tender", "E-commerce platform for Posta Moldovei", "/portal/tenders/TND-7760", 130, false, extra("tenderId", "TND-7760", "title", "E-commerce platform", "fitScore", 86, "country", "MD", "buyer", "Posta Moldovei")),
            new Seed(NotificationCategory.TENDERS, "TENDER_GREAT_FIT", "Tender Radar", "tender deadline soon", "CRM integration tender closes tomorrow", "/portal/tenders/TND-7742", 500, true, extra("tenderId", "TND-7742", "title", "CRM integration", "fitScore", 78, "country", "RO", "buyer", "ANAF")),

            new Seed(NotificationCategory.TIME, "ADMIN_MESSAGE", "Time Tracker", "timesheet reminder", "You have 3 unlogged hours for today", "/portal/time-tracking", 30, false, extra("message", "Unlogged hours today")),
            new Seed(NotificationCategory.TIME, "ADMIN_MESSAGE", "Maria Popa", "approved your timesheet", "Last week timesheet approved", "/portal/time-tracking?week=last", 240, false, extra("message", "Timesheet approved")),
            new Seed(NotificationCategory.TIME, "ADMIN_MESSAGE", "Time Tracker", "timer still running", "Timer on Metro Weekly has run for 6h", "/portal/time-tracking", 480, true, extra("message", "Long running timer")),
            new Seed(NotificationCategory.TIME, "ADMIN_MESSAGE", "Maria Popa", "requested a correction", "Please fix Tuesday entry on project Orange", "/portal/time-tracking?week=last", 1300, true, extra("message", "Correction requested")),

            new Seed(NotificationCategory.SHIELD, "ADMIN_MESSAGE", "Shield", "blocked a threat", "Blocked 142 bot requests in the last hour", "/portal/shield?tab=threats", 7, false, extra("message", "Bot traffic blocked")),
            new Seed(NotificationCategory.SHIELD, "ADMIN_MESSAGE", "Shield", "new login location", "Sign-in from a new device in Bucuresti", "/portal/shield?tab=sessions", 60, false, extra("message", "New login location")),
            new Seed(NotificationCategory.SHIELD, "ADMIN_MESSAGE", "Shield", "rate limit triggered", "Login rate limit triggered for 1 IP", "/portal/shield?tab=rules", 300, true, extra("message", "Rate limit triggered")),
            new Seed(NotificationCategory.SHIELD, "ADMIN_MESSAGE", "Shield", "certificate expiring", "TLS certificate for dev.omy.md expires in 10 days", "/portal/shield?tab=certificates", 1000, true, extra("message", "Certificate expiring")),

            new Seed(NotificationCategory.USERS, "ADMIN_MESSAGE", "Admin", "invited a user", "alex@naqqa.dev was invited as Moderator", "/portal/admin/users", 16, false, extra("message", "User invited")),
            new Seed(NotificationCategory.USERS, "ADMIN_MESSAGE", "Admin", "changed a role", "Ion Vrabie is now an Administrator", "/portal/admin/roles", 120, false, extra("message", "Role changed")),
            new Seed(NotificationCategory.USERS, "ADMIN_MESSAGE", "Admin", "deactivated a user", "Former contractor account deactivated", "/portal/admin/users", 420, true, extra("message", "User deactivated")),
            new Seed(NotificationCategory.USERS, "ADMIN_MESSAGE", "Admin", "updated permissions", "Observability access granted to Support", "/portal/admin/permissions", 1500, true, extra("message", "Permissions updated")),

            new Seed(NotificationCategory.SYSTEM, "ADMIN_MESSAGE", "NaqqaOS", "maintenance window", "Scheduled maintenance this Sunday 02:00-03:00", "/portal/notifications", 25, false, extra("message", "Scheduled maintenance")),
            new Seed(NotificationCategory.SYSTEM, "ADMIN_MESSAGE", "NaqqaOS", "release notes", "Version 1.5.0 is now available", "/portal/notifications", 200, false, extra("message", "Release 1.5.0")),
            new Seed(NotificationCategory.SYSTEM, "ADMIN_MESSAGE", "NaqqaOS", "backup completed", "Nightly backup completed successfully", "/portal/notifications", 800, true, extra("message", "Backup completed")),
            new Seed(NotificationCategory.SYSTEM, "ADMIN_MESSAGE", "NaqqaOS", "usage report ready", "Your monthly usage report is ready", "/portal/notifications", 2000, true, extra("message", "Usage report ready"))
    );

    @Override
    public List<Notification> all() {
        List<Notification> out = new ArrayList<>(SEEDS.size());
        Instant now = Instant.now();
        for (int i = 0; i < SEEDS.size(); i++) {
            Seed s = SEEDS.get(i);
            String id = String.format("ntf_%04d", i + 1);
            String createdAt = now.minusSeconds(s.minutes() * 60).toString();
            out.add(new Notification(id, s.type(), s.category(), s.seen(), createdAt,
                    s.actor(), s.action(), s.text(), s.route(), s.extra()));
        }
        return out;
    }
}
