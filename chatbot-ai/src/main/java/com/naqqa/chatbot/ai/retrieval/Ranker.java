package com.naqqa.chatbot.ai.retrieval;

import com.naqqa.chatbot.entities.ChatSettingsEntity;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class Ranker {

    public static final double SPONSOR_MIN_RELEVANCE = 0.5;
    public static final double MIN_RELEVANCE = 0.15;
    public static final List<String> DEFAULT_EXCLUDE_PATTERNS = List.of("(?i)\\btest\\b", "(?i)\\bqa\\b", "(?i)\\bdemo\\b");

    private static final Map<String, Pattern> PATTERN_CACHE = new ConcurrentHashMap<>();
    private static final Pattern INVALID = Pattern.compile("(?!)");

    public record Options(int maxItems, int maxSponsored, double sponsorBoost, List<String> excludePatterns) {

        public static Options from(ChatSettingsEntity settings, int maxItems) {
            if (settings == null) {
                return new Options(maxItems, 2, 1.5, DEFAULT_EXCLUDE_PATTERNS);
            }
            List<String> patterns = settings.getExcludeTitlePatterns() == null || settings.getExcludeTitlePatterns().isEmpty()
                    ? DEFAULT_EXCLUDE_PATTERNS : settings.getExcludeTitlePatterns();
            return new Options(maxItems, Math.max(0, settings.getMaxSponsoredPerReply()),
                    Math.max(0, settings.getSponsorBoost()), patterns);
        }
    }

    private final java.util.function.Function<String, ChatItemType> types;

    public Ranker(java.util.function.Function<String, ChatItemType> types) {
        this.types = types == null ? t -> null : types;
    }

    private double prior(String type) {
        ChatItemType t = types.apply(type);
        return t == null ? 1.0 : t.prior();
    }

    private int order(String type) {
        ChatItemType t = types.apply(type);
        return t == null ? Integer.MAX_VALUE : t.order();
    }

    public List<RankedItem> rank(List<Candidate> candidates, Options options, LocalDate today) {
        List<Candidate> kept = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<Pattern> exclude = compile(options.excludePatterns());
        for (Candidate c : candidates == null ? List.<Candidate>of() : candidates) {
            if (c == null || c.id() == null || c.type() == null || !seen.add(c.key())) {
                continue;
            }
            if (c.validTo() != null && c.validTo().isBefore(today)) {
                continue;
            }
            if (excluded(c, exclude)) {
                continue;
            }
            kept.add(c);
        }
        double maxRaw = 0;
        for (Candidate c : kept) {
            if (c.score() != null && c.score() > maxRaw) {
                maxRaw = c.score();
            }
        }
        long todayMillis = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        List<RankedItem> sponsored = new ArrayList<>();
        List<RankedItem> regular = new ArrayList<>();
        for (Candidate c : kept) {
            double relevance = c.score() == null || maxRaw <= 0 ? 1.0 : c.score() / maxRaw;
            if (relevance < MIN_RELEVANCE) {
                continue;
            }
            boolean active = c.sponsorActive(today);
            if (active && relevance < SPONSOR_MIN_RELEVANCE) {
                continue;
            }
            double freshness = freshness(c.freshnessMillis(), todayMillis);
            double discount = c.discount() == null ? 0 : Math.max(0, Math.min(90, c.discount())) / 100.0;
            double weight = c.sponsorWeight() == null ? 1.0 : Math.max(0, Math.min(10, c.sponsorWeight()));
            double boost = active ? 1 + options.sponsorBoost() * weight : 1;
            double score = relevance * freshness * boost * (1 + discount) * prior(c.type());
            RankedItem item = new RankedItem(c, score, relevance, active);
            (active ? sponsored : regular).add(item);
        }
        Comparator<RankedItem> order = Comparator.comparingDouble(RankedItem::score).reversed()
                .thenComparingInt(i -> order(i.candidate().type()));
        sponsored.sort(order);
        regular.sort(order);
        List<RankedItem> out = new ArrayList<>();
        for (RankedItem item : sponsored) {
            if (out.size() >= Math.min(options.maxSponsored(), options.maxItems())) {
                break;
            }
            out.add(item);
        }
        for (RankedItem item : regular) {
            if (out.size() >= options.maxItems()) {
                break;
            }
            out.add(item);
        }
        return out;
    }

    public static List<RankedItem> displayOrder(List<RankedItem> items) {
        List<RankedItem> sponsored = new ArrayList<>();
        List<RankedItem> regular = new ArrayList<>();
        for (RankedItem item : items == null ? List.<RankedItem>of() : items) {
            (item.sponsored() ? sponsored : regular).add(item);
        }
        regular.sort(Comparator.<RankedItem>comparingDouble(i -> i.candidate().discount() == null ? -1 : i.candidate().discount())
                .reversed()
                .thenComparing(Comparator.comparingDouble(RankedItem::relevance).reversed())
                .thenComparing(Comparator.comparingDouble(RankedItem::score).reversed()));
        List<RankedItem> out = new ArrayList<>(sponsored);
        out.addAll(regular);
        return out;
    }

    static double freshness(Long millis, long todayMillis) {
        if (millis == null) {
            return 0.8;
        }
        double ageDays = Math.max(0, (todayMillis - millis) / 86_400_000.0);
        return 0.6 + 0.4 * Math.exp(-ageDays / 30.0);
    }

    static boolean excluded(Candidate c, List<Pattern> patterns) {
        for (Pattern p : patterns) {
            for (String title : c.titles().values()) {
                if (title != null && p.matcher(title).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    static List<Pattern> compile(List<String> patterns) {
        List<Pattern> out = new ArrayList<>();
        if (patterns == null) {
            return out;
        }
        for (String p : patterns) {
            if (p == null || p.isBlank() || p.length() > 200) {
                continue;
            }
            Pattern compiled = PATTERN_CACHE.computeIfAbsent(p, key -> {
                try {
                    return Pattern.compile(key);
                } catch (PatternSyntaxException e) {
                    return INVALID;
                }
            });
            if (compiled != INVALID) {
                out.add(compiled);
            }
        }
        return out;
    }
}
