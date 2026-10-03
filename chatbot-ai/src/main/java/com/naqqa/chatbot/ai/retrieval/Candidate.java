package com.naqqa.chatbot.ai.retrieval;

import com.naqqa.chatbot.i18n.ChatLanguages;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

public record Candidate(String type, Long id, String slug, Map<String, String> titles, String imageId,
                        Double price, Double originalPrice, Double discount, Long companyId,
                        LocalDate validTo, Long freshnessMillis, Double score, Boolean sponsored, Double sponsorWeight,
                        LocalDate sponsorFrom, LocalDate sponsorTo, String path) {

    public Candidate {
        titles = titles == null ? new LinkedHashMap<>() : new LinkedHashMap<>(titles);
    }

    public static Map<String, String> titles(String ro, String ru) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("ro", ro);
        out.put("ru", ru);
        return out;
    }

    public String key() {
        return type + ":" + id;
    }

    public String title(String lang) {
        return ChatLanguages.pick(titles, lang);
    }

    public boolean hasTitle() {
        return title(null) != null;
    }

    public boolean sponsorActive(LocalDate today) {
        if (!Boolean.TRUE.equals(sponsored)) {
            return false;
        }
        if (sponsorFrom != null && sponsorFrom.isAfter(today)) {
            return false;
        }
        return sponsorTo == null || !sponsorTo.isBefore(today);
    }

    public Candidate withScore(Double value) {
        return new Candidate(type, id, slug, titles, imageId, price, originalPrice, discount, companyId, validTo,
                freshnessMillis, value, sponsored, sponsorWeight, sponsorFrom, sponsorTo, path);
    }

    public Candidate withPath(String value) {
        return new Candidate(type, id, slug, titles, imageId, price, originalPrice, discount, companyId, validTo,
                freshnessMillis, score, sponsored, sponsorWeight, sponsorFrom, sponsorTo, value);
    }
}
