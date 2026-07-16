package com.naqqa.outreach.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.naqqa.outreach.config.OutreachProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * Decision-maker email finder — faithful port of apolloEmail.js. Order: (0) free website scrape →
 * (1) Apollo org enrich → (2) people search by target titles (scored) → (3) bulk match to reveal
 * emails. Returns the best contact or null.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApolloClient {

    public record Contact(String email, String name, String firstName, String title) {
    }

    private static final String[] SCRAPE_PATHS = {"", "contact", "contact-us", "about", "about-us", "team"};

    private final OutreachProperties props;
    private final EmailValidator validator;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestClient http = RestClient.builder().baseUrl("https://api.apollo.io/api/v1").build();

    public Contact findEmail(String website) {
        String domain = domainOf(website);
        if (domain == null) {
            return null;
        }
        Contact scraped = scrapeWebsite(domain);
        if (scraped != null) {
            return scraped;
        }
        if (props.getApolloApiKey() == null || props.getApolloApiKey().isBlank()) {
            return null;
        }
        try {
            String orgId = enrichOrgId(domain);
            if (orgId == null) {
                return null;
            }
            List<JsonNode> people = searchPeople(orgId);
            for (JsonNode person : people) {
                if (person.path("has_email").asBoolean(true) == false) {
                    continue;
                }
                Contact c = bulkMatch(person, domain);
                if (c != null) {
                    return c;
                }
            }
        } catch (Exception e) {
            log.warn("Apollo lookup failed for {}: {}", domain, e.getMessage());
        }
        return null;
    }

    // ── Step 0: free website scrape ──────────────────────────────────────────
    private Contact scrapeWebsite(String domain) {
        for (String path : SCRAPE_PATHS) {
            try {
                String html = Jsoup.connect("https://" + domain + "/" + path)
                        .userAgent("Mozilla/5.0 (compatible; lead-bot/1.0)")
                        .timeout(10000).ignoreHttpErrors(true).ignoreContentType(true).execute().body();
                Matcher m = OutreachConstants.EMAIL_SCRAPE.matcher(html);
                while (m.find()) {
                    String raw = m.group();
                    if (!raw.toLowerCase().endsWith("@" + domain) && !raw.toLowerCase().contains("@" + domain)) {
                        continue;
                    }
                    if (isRoleEmail(raw)) {
                        continue;
                    }
                    EmailValidator.Result v = validator.validate(raw);
                    if (v.valid() && v.email().endsWith("@" + domain)) {
                        return new Contact(v.email(), null, null, null);
                    }
                }
            } catch (Exception ignored) {
                // try next path
            }
        }
        return null;
    }

    // ── Step 1: org enrich ───────────────────────────────────────────────────
    private String enrichOrgId(String domain) {
        JsonNode res = http.get()
                .uri(uri -> uri.path("/organizations/enrich").queryParam("domain", domain).build())
                .headers(this::apolloHeaders)
                .retrieve().body(JsonNode.class);
        if (res == null) {
            return null;
        }
        JsonNode org = res.has("organization") ? res.get("organization") : res;
        return org.path("id").isMissingNode() ? null : org.path("id").asText(null);
    }

    // ── Step 2: people search (scored top 5) ─────────────────────────────────
    private List<JsonNode> searchPeople(String orgId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("person_titles", OutreachConstants.TARGET_TITLES);
        body.put("organization_ids", List.of(orgId));
        body.put("per_page", 10);
        body.put("page", 1);
        JsonNode res = http.post().uri("/mixed_people/api_search")
                .headers(this::apolloHeaders).contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(JsonNode.class);
        List<JsonNode> people = new ArrayList<>();
        if (res != null && res.has("people")) {
            res.get("people").forEach(people::add);
        }
        people.sort((a, b) -> Integer.compare(score(b), score(a)));
        return people.size() > 5 ? people.subList(0, 5) : people;
    }

    // ── Step 3: bulk match to reveal the email ───────────────────────────────
    private Contact bulkMatch(JsonNode person, String domain) {
        Map<String, Object> body = Map.of("details", List.of(Map.of("id", person.path("id").asText())));
        JsonNode res = http.post()
                .uri(uri -> uri.path("/people/bulk_match")
                        .queryParam("run_waterfall_email", false)
                        .queryParam("run_waterfall_phone", false)
                        .queryParam("reveal_personal_emails", true)
                        .queryParam("reveal_phone_number", false).build())
                .headers(this::apolloHeaders).contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(JsonNode.class);
        if (res == null) {
            return null;
        }
        JsonNode matches = res.has("matches") ? res.get("matches") : res.path("people");
        Contact fallback = null;
        for (JsonNode p : matches) {
            String email = p.path("email").asText(null);
            if (email == null || email.isBlank() || isRoleEmail(email)) {
                continue;
            }
            EmailValidator.Result v = validator.validate(email);
            if (!v.valid()) {
                continue;
            }
            String name = (p.path("first_name").asText("") + " " + p.path("last_name").asText("")).trim();
            Contact c = new Contact(v.email(), name.isBlank() ? null : name,
                    p.path("first_name").asText(null), p.path("title").asText(null));
            if (v.email().endsWith("@" + domain)) {
                return c;
            }
            if (fallback == null && emailMatchesPerson(v.email(),
                    p.path("first_name").asText(""), p.path("last_name").asText(""))) {
                fallback = c;
            }
        }
        return fallback;
    }

    private int score(JsonNode p) {
        String title = p.path("title").asText("").toLowerCase().trim().replaceAll("\\s+", " ");
        int score = 0;
        if (p.path("has_email").asBoolean(false)) score += 50;
        if (p.has("has_email") && !p.path("has_email").asBoolean(true)) score -= 200;
        if (!p.path("linkedin_url").asText("").isBlank()) score += 20;
        if (!p.path("first_name").asText("").isBlank()) score += 10;
        if (!p.path("last_name").asText("").isBlank()) score += 10;
        if (title.contains("founder")) score += 120;
        else if (title.contains("co-founder")) score += 110;
        else if (title.contains("chief executive officer") || title.equals("ceo")) score += 105;
        else if (title.contains("owner")) score += 95;
        else if (title.contains("managing director") || title.contains("geschäftsführer")) score += 90;
        else if (title.contains("cto") || title.contains("chief technology officer")) score += 80;
        else if (title.contains("head of engineering")) score += 70;
        else if (title.contains("director")) score += 50;
        return score;
    }

    private boolean isRoleEmail(String email) {
        String local = email.split("@")[0].toLowerCase().replaceAll("[^a-z]", "");
        return OutreachConstants.GENERIC_PREFIXES.contains(local);
    }

    private boolean emailMatchesPerson(String email, String first, String last) {
        String local = email.split("@")[0].toLowerCase();
        return (first != null && first.length() >= 3 && local.contains(first.toLowerCase()))
                || (last != null && last.length() >= 3 && local.contains(last.toLowerCase()));
    }

    private void apolloHeaders(org.springframework.http.HttpHeaders h) {
        h.set("x-api-key", props.getApolloApiKey());
        h.set("Cache-Control", "no-cache");
        h.setAccept(List.of(MediaType.APPLICATION_JSON));
    }

    private String domainOf(String website) {
        if (website == null || website.isBlank()) {
            return null;
        }
        String d = website.trim().toLowerCase()
                .replaceFirst("^https?://", "").replaceFirst("^www\\.", "");
        int slash = d.indexOf('/');
        if (slash > 0) {
            d = d.substring(0, slash);
        }
        return d.isBlank() ? null : d;
    }
}
