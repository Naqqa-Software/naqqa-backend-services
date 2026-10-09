package com.naqqa.analytics.banners.engine;

import com.naqqa.analytics.banners.model.BannerCampaign;
import com.naqqa.analytics.banners.model.BannerCreative;
import com.naqqa.analytics.banners.model.BannerStatus;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;
import java.util.random.RandomGenerator;

public final class BannerSelector {

    public record Candidate(BannerCampaign campaign, List<BannerCreative> creatives) {
    }

    public record Selection(BannerCampaign campaign, BannerCreative creative, long campaignStep, long creativeStep) {
        public Selection(BannerCampaign campaign, BannerCreative creative) {
            this(campaign, creative, 1, 1);
        }
    }

    public record Pick<T>(T item, long step) {
    }

    public record Fairness(double slack, double catchUp) {
        public static final Fairness DEFAULT = new Fairness(1.0, 100.0);

        public Fairness {
            slack = Math.max(0, slack);
            catchUp = Math.max(0, catchUp);
        }
    }

    public enum Rejection {
        STATUS,
        SCHEDULE,
        TARGETING,
        BUDGET,
        PACING,
        FREQUENCY,
        RESERVED,
        COMPETITOR,
        NO_CREATIVE
    }

    private final BannerTargetingEngine targeting;
    private final BannerPacingCalculator pacing;
    private final boolean excludeCompetitorsOnCompanyPage;
    private final Fairness fairness;

    public BannerSelector(BannerTargetingEngine targeting, BannerPacingCalculator pacing, boolean excludeCompetitorsOnCompanyPage) {
        this(targeting, pacing, excludeCompetitorsOnCompanyPage, Fairness.DEFAULT);
    }

    public BannerSelector(BannerTargetingEngine targeting, BannerPacingCalculator pacing, boolean excludeCompetitorsOnCompanyPage,
                          Fairness fairness) {
        this.targeting = targeting;
        this.pacing = pacing;
        this.excludeCompetitorsOnCompanyPage = excludeCompetitorsOnCompanyPage;
        this.fairness = fairness == null ? Fairness.DEFAULT : fairness;
    }

    public Selection select(List<Candidate> candidates, BannerRequest request, ToIntFunction<String> servedTodayToVisitor,
                            RandomGenerator random) {
        return select(candidates, request, servedTodayToVisitor, null, random);
    }

    public Selection select(List<Candidate> candidates, BannerRequest request, ToIntFunction<String> servedTodayToVisitor,
                            ToLongFunction<String> rotation, RandomGenerator random) {
        List<Candidate> eligible = eligible(candidates, request, servedTodayToVisitor);
        if (eligible.isEmpty()) {
            return null;
        }
        int top = eligible.stream().mapToInt(c -> rank(c.campaign())).max().orElse(0);
        List<Candidate> tier = eligible.stream().filter(c -> rank(c.campaign()) == top).toList();
        if (rotation == null) {
            Candidate chosen = weighted(tier, c -> Math.max(1, c.campaign().getWeight()), random);
            BannerCreative creative = pickCreative(chosen.campaign(), compatible(chosen.creatives(), request.slot()), request.vid(), random);
            return creative == null ? null : new Selection(chosen.campaign(), creative);
        }
        ToIntFunction<Candidate> seen = request.vid() == null || servedTodayToVisitor == null ? null
                : c -> servedTodayToVisitor.applyAsInt(c.campaign().getId());
        Pick<Candidate> chosen = fairPick(tier, c -> Math.max(1, c.campaign().getWeight()), c -> rotation.applyAsLong(c.campaign().getId()),
                seen, fairness, random);
        BannerCampaign campaign = chosen.item().campaign();
        List<BannerCreative> creatives = compatible(chosen.item().creatives(), request.slot());
        if (campaign.isAbTest() || creatives.size() < 2) {
            BannerCreative creative = pickCreative(campaign, creatives, request.vid(), random);
            return creative == null ? null : new Selection(campaign, creative, chosen.step(), 1);
        }
        Pick<BannerCreative> creative = fairPick(creatives, cr -> Math.max(1, cr.getWeight()), cr -> rotation.applyAsLong(creativeKey(cr.getId())),
                null, fairness, random);
        return new Selection(campaign, creative.item(), chosen.step(), creative.step());
    }

    public static String creativeKey(String creativeId) {
        return "cr:" + creativeId;
    }

    public static <T> T fair(List<T> items, ToIntFunction<T> weight, ToLongFunction<T> served, ToIntFunction<T> seenByVisitor,
                             Fairness fairness, RandomGenerator random) {
        Pick<T> pick = fairPick(items, weight, served, seenByVisitor, fairness, random);
        return pick == null ? null : pick.item();
    }

    public static <T> Pick<T> fairPick(List<T> items, ToIntFunction<T> weight, ToLongFunction<T> served, ToIntFunction<T> seenByVisitor,
                                       Fairness fairness, RandomGenerator random) {
        if (items.isEmpty()) {
            return null;
        }
        if (items.size() == 1) {
            return new Pick<>(items.get(0), 1);
        }
        int n = items.size();
        double[] raw = new double[n];
        double[] load = new double[n];
        int[] weights = new int[n];
        double max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            weights[i] = Math.max(1, weight.applyAsInt(items.get(i)));
            raw[i] = Math.max(0L, served.applyAsLong(items.get(i))) / (double) weights[i];
            max = Math.max(max, raw[i]);
        }
        double min = Double.POSITIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            load[i] = fairness.catchUp() > 0 ? Math.max(raw[i], max - fairness.catchUp()) : raw[i];
            min = Math.min(min, load[i]);
        }
        List<Integer> band = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            if (load[i] <= min + fairness.slack() + 1e-9) {
                band.add(i);
            }
        }
        if (seenByVisitor != null && band.size() > 1) {
            int[] seen = new int[band.size()];
            int least = Integer.MAX_VALUE;
            for (int i = 0; i < band.size(); i++) {
                seen[i] = seenByVisitor.applyAsInt(items.get(band.get(i)));
                least = Math.min(least, seen[i]);
            }
            List<Integer> fresh = new ArrayList<>(band.size());
            for (int i = 0; i < band.size(); i++) {
                if (seen[i] == least) {
                    fresh.add(band.get(i));
                }
            }
            band = fresh;
        }
        int chosen = band.get(random.nextInt(band.size()));
        long lift = (long) Math.ceil((load[chosen] - raw[chosen]) * weights[chosen] - 1e-9);
        return new Pick<>(items.get(chosen), 1 + Math.max(0, lift));
    }

    public List<Candidate> eligible(List<Candidate> candidates, BannerRequest request, ToIntFunction<String> servedTodayToVisitor) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        boolean ownerExclusive = request.companyPage() && candidates.stream()
                .map(Candidate::campaign)
                .anyMatch(c -> c.isExcludeCompetitorsOnCompanyPage() && Objects.equals(c.getCompanyId(), request.companyId())
                        && c.getStatus() == BannerStatus.ACTIVE);
        List<Candidate> out = new ArrayList<>();
        for (Candidate c : candidates) {
            if (reject(c, request, servedTodayToVisitor, ownerExclusive) == null) {
                out.add(c);
            }
        }
        return out;
    }

    public Rejection reject(Candidate candidate, BannerRequest request, ToIntFunction<String> servedTodayToVisitor, boolean ownerExclusive) {
        BannerCampaign c = candidate.campaign();
        if (c == null || c.getStatus() != BannerStatus.ACTIVE) {
            return Rejection.STATUS;
        }
        if (!targeting.inSchedule(c, request.now())) {
            return Rejection.SCHEDULE;
        }
        if (!targeting.matches(c, request)) {
            return Rejection.TARGETING;
        }
        if (BannerPacingCalculator.exhausted(c)) {
            return Rejection.BUDGET;
        }
        if (!pacing.allows(c, request.now())) {
            return Rejection.PACING;
        }
        Integer cap = c.getFrequencyCapPerDay();
        if (cap != null && cap > 0 && servedTodayToVisitor != null && request.vid() != null
                && servedTodayToVisitor.applyAsInt(c.getId()) >= cap) {
            return Rejection.FREQUENCY;
        }
        if (BannerSlots.reservedForCompany(request.slot())
                && (request.companyId() == null || !Objects.equals(c.getCompanyId(), request.companyId()))) {
            return Rejection.RESERVED;
        }
        if (request.companyPage() && c.getCompanyId() != null && !Objects.equals(c.getCompanyId(), request.companyId())
                && (excludeCompetitorsOnCompanyPage || ownerExclusive)) {
            return Rejection.COMPETITOR;
        }
        if (compatible(candidate.creatives(), request.slot()).isEmpty()) {
            return Rejection.NO_CREATIVE;
        }
        return null;
    }

    public static List<BannerCreative> compatible(List<BannerCreative> creatives, String slot) {
        if (creatives == null) {
            return List.of();
        }
        List<BannerCreative> out = new ArrayList<>();
        for (BannerCreative cr : creatives) {
            if (cr == null || !cr.isActive() || (cr.getDesktop() == null && cr.getMobile() == null)) {
                continue;
            }
            if (cr.getSlots() == null || cr.getSlots().isEmpty() || (slot != null && cr.getSlots().stream().anyMatch(slot::equalsIgnoreCase))) {
                out.add(cr);
            }
        }
        return out;
    }

    public static BannerCreative pickCreative(BannerCampaign campaign, List<BannerCreative> creatives, String vid, RandomGenerator random) {
        if (creatives.isEmpty()) {
            return null;
        }
        if (creatives.size() == 1) {
            return creatives.get(0);
        }
        if (campaign.isAbTest() && vid != null && !vid.isBlank()) {
            List<BannerCreative> sorted = creatives.stream()
                    .sorted((a, b) -> String.valueOf(a.getId()).compareTo(String.valueOf(b.getId())))
                    .toList();
            long total = sorted.stream().mapToLong(cr -> Math.max(1, cr.getWeight())).sum();
            long bucket = Integer.toUnsignedLong(fnv(vid + "|" + campaign.getId())) % total;
            long acc = 0;
            for (BannerCreative cr : sorted) {
                acc += Math.max(1, cr.getWeight());
                if (bucket < acc) {
                    return cr;
                }
            }
            return sorted.get(sorted.size() - 1);
        }
        return weighted(creatives, cr -> Math.max(1, cr.getWeight()), random);
    }

    static int fnv(String value) {
        int h = 0x811c9dc5;
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            h ^= b & 0xff;
            h *= 0x01000193;
        }
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        return h;
    }

    private static int rank(BannerCampaign c) {
        return c.getPriority() == null ? 0 : c.getPriority().rank();
    }

    static <T> T weighted(List<T> items, ToIntFunction<T> weight, RandomGenerator random) {
        long total = 0;
        for (T item : items) {
            total += weight.applyAsInt(item);
        }
        long pick = random.nextLong(total);
        long acc = 0;
        for (T item : items) {
            acc += weight.applyAsInt(item);
            if (pick < acc) {
                return item;
            }
        }
        return items.get(items.size() - 1);
    }
}
