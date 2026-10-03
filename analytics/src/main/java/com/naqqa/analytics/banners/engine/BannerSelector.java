package com.naqqa.analytics.banners.engine;

import com.naqqa.analytics.banners.model.BannerCampaign;
import com.naqqa.analytics.banners.model.BannerCreative;
import com.naqqa.analytics.banners.model.BannerStatus;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.ToIntFunction;
import java.util.random.RandomGenerator;

public final class BannerSelector {

    public record Candidate(BannerCampaign campaign, List<BannerCreative> creatives) {
    }

    public record Selection(BannerCampaign campaign, BannerCreative creative) {
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

    public BannerSelector(BannerTargetingEngine targeting, BannerPacingCalculator pacing, boolean excludeCompetitorsOnCompanyPage) {
        this.targeting = targeting;
        this.pacing = pacing;
        this.excludeCompetitorsOnCompanyPage = excludeCompetitorsOnCompanyPage;
    }

    public Selection select(List<Candidate> candidates, BannerRequest request, ToIntFunction<String> servedTodayToVisitor,
                            RandomGenerator random) {
        List<Candidate> eligible = eligible(candidates, request, servedTodayToVisitor);
        if (eligible.isEmpty()) {
            return null;
        }
        int top = eligible.stream().mapToInt(c -> rank(c.campaign())).max().orElse(0);
        List<Candidate> tier = eligible.stream().filter(c -> rank(c.campaign()) == top).toList();
        Candidate chosen = weighted(tier, c -> Math.max(1, c.campaign().getWeight()), random);
        BannerCreative creative = pickCreative(chosen.campaign(), compatible(chosen.creatives(), request.slot()), request.vid(), random);
        return creative == null ? null : new Selection(chosen.campaign(), creative);
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
