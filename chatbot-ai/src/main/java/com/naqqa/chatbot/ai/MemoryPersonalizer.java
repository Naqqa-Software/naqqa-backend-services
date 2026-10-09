package com.naqqa.chatbot.ai;

import com.naqqa.chatbot.ai.retrieval.CompanyRef;
import com.naqqa.chatbot.ai.retrieval.RankedItem;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public final class MemoryPersonalizer {

    public static final double STORE_SHARE = 0.6;
    public static final double BRAND_SHARE = 0.4;

    private MemoryPersonalizer() {
    }

    public static double factor(RankedItem item, MemoryContext memory) {
        if (item == null || memory == null) {
            return 1.0;
        }
        double cap = memory.boostCap();
        double max = 0;
        for (MemoryContext.Pref p : memory.stores()) {
            max = Math.max(max, p.weight());
        }
        double store = 0;
        MemoryContext.Pref pref = memory.store(item.candidate().companyId());
        if (pref != null && max > 0) {
            store = pref.explicit() ? 1.0 : Math.min(1.0, pref.weight() / max);
        }
        double brand = 0;
        if (!memory.brands().isEmpty()) {
            String title = TextNormalizer.compact(String.join(" ", item.candidate().titles().values().stream()
                    .filter(java.util.Objects::nonNull).toList()));
            for (MemoryContext.Pref b : memory.brands()) {
                String compact = TextNormalizer.compact(b.label());
                if (compact.length() >= 3 && title.contains(compact)) {
                    brand = 1.0;
                    break;
                }
            }
        }
        double boost = cap * (STORE_SHARE * store + BRAND_SHARE * brand);
        return 1.0 + Math.min(cap, Math.max(0, boost));
    }

    public static List<RankedItem> boost(List<RankedItem> ranked, MemoryContext memory, int keep) {
        if (ranked == null || ranked.size() < 2 || memory == null || memory.stores().isEmpty() && memory.brands().isEmpty()) {
            return ranked;
        }
        List<RankedItem> sponsored = new ArrayList<>();
        List<RankedItem> regular = new ArrayList<>();
        for (RankedItem r : ranked) {
            (r.sponsored() ? sponsored : regular).add(r);
        }
        Map<RankedItem, Double> boosted = new IdentityHashMap<>();
        for (RankedItem r : regular) {
            boosted.put(r, r.score() * factor(r, memory));
        }
        List<RankedItem> sorted = new ArrayList<>(regular);
        sorted.sort(Comparator.comparingDouble((RankedItem r) -> boosted.get(r)).reversed());
        RankedItem cheapest = null;
        for (RankedItem r : regular) {
            Double price = r.candidate().price();
            if (price != null && price > 0 && (cheapest == null || price < cheapest.candidate().price())) {
                cheapest = r;
            }
        }
        int slots = Math.max(1, keep - sponsored.size());
        if (cheapest != null && sorted.indexOf(cheapest) >= slots && sorted.size() >= slots) {
            sorted.remove(cheapest);
            sorted.add(slots - 1, cheapest);
        }
        List<RankedItem> out = new ArrayList<>(sponsored);
        out.addAll(sorted);
        return out;
    }

    public static Long storeFor(MemoryContext memory, ConversationContext previous) {
        if (previous != null && previous.companyId() != null) {
            return previous.companyId();
        }
        if (memory == null) {
            return null;
        }
        ConversationContext last = memory.last() == null ? null : memory.last().parsed();
        if (last != null && last.companyId() != null) {
            return last.companyId();
        }
        if (memory.defaultStoreId() != null) {
            return memory.defaultStoreId();
        }
        MemoryContext.Pref top = memory.topStore();
        return top == null ? null : top.id();
    }

    public static long daysAgo(Instant at, Instant now) {
        if (at == null) {
            return -1;
        }
        ZoneId zone = ZoneId.systemDefault();
        LocalDate then = at.atZone(zone).toLocalDate();
        LocalDate today = now.atZone(zone).toLocalDate();
        return Math.max(0, ChronoUnit.DAYS.between(then, today));
    }

    public static boolean known(CompanyRef company) {
        return company != null && company.id() != null && company.name() != null && !company.name().isBlank();
    }
}
