package com.naqqa.seofarm.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.naqqa.seofarm.config.SeoFarmProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * SerpAPI client (Google SERP / autocomplete / trends / organic) — faithful port of the bot's
 * SerpAPI usage (the misnamed dataforseo.js + contentAnalyzer.js). All calls fail soft (empty list).
 */
@Service
@Slf4j
public class SerpApiClient {

    private static final String BASE = "https://serpapi.com/search";

    private final SeoFarmProperties props;
    private final RestClient http = RestClient.create();

    /** Index of the SerpAPI key currently in use (advances on a per-key 429 "out of searches"). */
    private volatile int keyIndex = 0;
    /** Set once ALL configured keys are out of searches — skips the rest of this run. Reset per run. */
    private volatile boolean outOfSearches = false;

    public SerpApiClient(SeoFarmProperties props) {
        this.props = props;
    }

    public boolean isConfigured() {
        return !props.getSerpApiKeys().isEmpty();
    }

    /** Call at the start of a keyword-research run so renewed quotas / all keys are retried afresh. */
    public void resetQuotaFlag() {
        keyIndex = 0;
        outOfSearches = false;
    }

    /** engine=google → related_searches[].query + people_also_ask[].question. */
    public List<String> relatedAndPaa(String term, String gl, String hl) {
        List<String> out = new ArrayList<>();
        JsonNode res = get(b -> b.queryParam("engine", "google").queryParam("q", term)
                .queryParam("gl", gl).queryParam("hl", hl).queryParam("num", 10));
        if (res != null) {
            for (JsonNode n : res.path("related_searches")) {
                if (n.hasNonNull("query")) out.add(n.get("query").asText());
            }
            for (JsonNode n : res.path("people_also_ask")) {
                if (n.hasNonNull("question")) out.add(n.get("question").asText());
            }
        }
        return out;
    }

    /** engine=google_autocomplete → suggestions[].value. */
    public List<String> autocomplete(String term, String gl, String hl) {
        List<String> out = new ArrayList<>();
        JsonNode res = get(b -> b.queryParam("engine", "google_autocomplete").queryParam("q", term)
                .queryParam("gl", gl).queryParam("hl", hl));
        if (res != null) {
            for (JsonNode n : res.path("suggestions")) {
                if (n.hasNonNull("value")) out.add(n.get("value").asText());
            }
        }
        return out;
    }

    /** engine=google_trends, RELATED_QUERIES → related_queries.rising/top[].query (geo UPPERCASE). */
    public List<String> trends(String term, String geoUpper, String hl) {
        List<String> out = new ArrayList<>();
        JsonNode res = get(b -> b.queryParam("engine", "google_trends").queryParam("q", term)
                .queryParam("data_type", "RELATED_QUERIES").queryParam("geo", geoUpper).queryParam("hl", hl));
        if (res != null) {
            JsonNode rq = res.path("related_queries");
            for (JsonNode n : rq.path("rising")) {
                if (n.hasNonNull("query")) out.add(n.get("query").asText());
            }
            for (JsonNode n : rq.path("top")) {
                if (n.hasNonNull("query")) out.add(n.get("query").asText());
            }
        }
        return out;
    }

    /** engine=google organic_results (top {@code num}) → competitor URLs. */
    public List<Organic> organic(String keyword, String gl, String hl, int num) {
        List<Organic> out = new ArrayList<>();
        JsonNode res = get(b -> b.queryParam("engine", "google").queryParam("q", keyword)
                .queryParam("gl", gl).queryParam("hl", hl).queryParam("num", num));
        if (res != null) {
            for (JsonNode n : res.path("organic_results")) {
                out.add(new Organic(n.path("title").asText(""), n.path("link").asText(""),
                        n.path("snippet").asText(""), n.path("position").asInt(0)));
            }
        }
        return out;
    }

    private JsonNode get(java.util.function.UnaryOperator<UriComponentsBuilder> params) {
        List<String> keys = props.getSerpApiKeys();
        if (keys.isEmpty() || outOfSearches) {
            return null;
        }
        // Try the current key; on a per-key 429 "out of searches", fail over to the next key.
        while (keyIndex < keys.size()) {
            try {
                UriComponentsBuilder b = params.apply(UriComponentsBuilder.fromUriString(BASE))
                        .queryParam("api_key", keys.get(keyIndex));
                return http.get().uri(b.build().toUri()).retrieve().body(JsonNode.class);
            } catch (HttpClientErrorException.TooManyRequests e) {
                log.warn("[seofarm] SerpAPI key #{}/{} out of searches (429) — failing over to next key.",
                        keyIndex + 1, keys.size());
                keyIndex++; // don't use this exhausted key again this run
            } catch (Exception e) {
                // Non-quota error (network / bad request) — treat as a transient miss for THIS call
                // without discarding the key.
                log.warn("[seofarm] SerpAPI call failed: {}", e.getMessage());
                return null;
            }
        }
        // Every key is exhausted — stop hammering SerpAPI for the rest of this run.
        outOfSearches = true;
        log.warn("[seofarm] All {} SerpAPI key(s) are out of searches — skipping remaining SerpAPI "
                + "calls this run. Renew/upgrade a SerpAPI plan (or add another key) and restart.", keys.size());
        return null;
    }

    /** One organic search result. */
    public record Organic(String title, String url, String snippet, int position) {
    }
}
