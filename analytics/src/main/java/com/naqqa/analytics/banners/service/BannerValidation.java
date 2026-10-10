package com.naqqa.analytics.banners.service;

import com.naqqa.analytics.banners.engine.BannerSlots;
import com.naqqa.analytics.banners.model.BannerCampaign;
import com.naqqa.analytics.banners.model.BannerCreative;
import com.naqqa.analytics.banners.model.BannerDestination;
import com.naqqa.analytics.banners.model.BannerImage;
import com.naqqa.analytics.banners.model.BannerTargeting;
import com.naqqa.analytics.banners.security.BannerUrlPolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public final class BannerValidation {

    public static final Set<String> LANGS = Set.of("ro", "ru", "en");
    public static final Set<String> DEVICES = Set.of("desktop", "mobile", "tablet");

    private BannerValidation() {
    }

    public static Map<String, String> campaign(BannerCampaign c) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (c.getName() == null || c.getName().isBlank()) {
            errors.put("name", "banners.validation.required");
        } else if (c.getName().length() > 120) {
            errors.put("name", "banners.validation.too_long");
        }
        if (c.getPriority() == null) {
            errors.put("priority", "banners.validation.required");
        }
        if (c.getStart() != null && c.getEnd() != null && !c.getStart().isBefore(c.getEnd())) {
            errors.put("end", "banners.validation.end_before_start");
        }
        if (c.getWeight() < 1 || c.getWeight() > 1000) {
            errors.put("weight", "banners.validation.range");
        }
        if (c.getFrequencyCapPerDay() != null && (c.getFrequencyCapPerDay() < 0 || c.getFrequencyCapPerDay() > 100)) {
            errors.put("frequencyCapPerDay", "banners.validation.range");
        }
        if (c.getBudgetImpressions() != null && c.getBudgetImpressions() < 0) {
            errors.put("budgetImpressions", "banners.validation.range");
        }
        if (c.getBudgetClicks() != null && c.getBudgetClicks() < 0) {
            errors.put("budgetClicks", "banners.validation.range");
        }
        BannerTargeting t = c.getTargeting();
        if (t == null || t.getSlots() == null || t.getSlots().isEmpty()) {
            errors.put("targeting.slots", "banners.validation.required");
        } else if (t.getSlots().stream().anyMatch(s -> !BannerSlots.exists(s))) {
            errors.put("targeting.slots", "banners.validation.unknown_slot");
        }
        if (t != null) {
            if (t.getHours() != null && t.getHours().stream().anyMatch(h -> h == null || h < 0 || h > 23)) {
                errors.put("targeting.hours", "banners.validation.range");
            }
            if (t.getDays() != null && t.getDays().stream().anyMatch(d -> d == null || d < 1 || d > 7)) {
                errors.put("targeting.days", "banners.validation.range");
            }
            if (t.getLangs() != null && t.getLangs().stream().anyMatch(l -> l == null || !LANGS.contains(l.toLowerCase()))) {
                errors.put("targeting.langs", "banners.validation.unknown_lang");
            }
            if (t.getDevices() != null && t.getDevices().stream().anyMatch(d -> d == null || !DEVICES.contains(d.toLowerCase()))) {
                errors.put("targeting.devices", "banners.validation.unknown_device");
            }
            if (t.getKeywords() != null && t.getKeywords().size() > 100) {
                errors.put("targeting.keywords", "banners.validation.too_many");
            }
        }
        return errors;
    }

    public static Map<String, String> creative(BannerCreative cr, BannerCampaign campaign, double ratioTolerance) {
        return creative(cr, campaign, ratioTolerance, BannerSlots::get);
    }

    public static Map<String, String> creative(BannerCreative cr, BannerCampaign campaign, double ratioTolerance,
                                               Function<String, BannerSlots.Slot> lookup) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (cr.getDesktop() == null && cr.getMobile() == null) {
            errors.put("desktop", "banners.validation.image_required");
        }
        imageUrl(errors, "desktop", cr.getDesktop());
        imageUrl(errors, "mobile", cr.getMobile());
        BannerDestination d = cr.getDestination();
        if (d == null || d.getType() == null) {
            errors.put("destination", "banners.validation.required");
        } else if (!BannerUrlPolicy.valid(d)) {
            errors.put(d.external() ? "destination.url" : "destination.path", "banners.validation.invalid_url");
        }
        if (cr.getAlt() == null || cr.getAlt().values().stream().allMatch(v -> v == null || v.isBlank())) {
            errors.put("alt", "banners.validation.alt_required");
        }
        if (cr.getWeight() < 1 || cr.getWeight() > 100) {
            errors.put("weight", "banners.validation.range");
        }
        List<String> slots = cr.getSlots() != null && !cr.getSlots().isEmpty() ? cr.getSlots()
                : campaign != null && campaign.getTargeting() != null ? campaign.getTargeting().getSlots() : List.of();
        if (cr.getSlots() != null && cr.getSlots().stream().anyMatch(s -> !BannerSlots.exists(s))) {
            errors.put("slots", "banners.validation.unknown_slot");
            return errors;
        }
        List<String> mismatched = new ArrayList<>();
        for (String s : slots) {
            BannerSlots.Slot slot = lookup.apply(s);
            if (slot == null) {
                continue;
            }
            if (!ratioOk(cr.getDesktop(), slot.desktop(), ratioTolerance) || (slot.mobile() != null && !ratioOk(cr.getMobile(), slot.mobile(), ratioTolerance))) {
                mismatched.add(slot.id());
            }
        }
        if (!mismatched.isEmpty()) {
            errors.put("slots", "banners.validation.ratio:" + String.join(",", mismatched));
        }
        return errors;
    }

    static boolean ratioOk(BannerImage image, BannerSlots.Size size, double tolerance) {
        if (image == null || size == null || image.getW() <= 0 || image.getH() <= 0) {
            return true;
        }
        double expected = size.ratio();
        double actual = (double) image.getW() / image.getH();
        return Math.abs(actual - expected) / expected <= tolerance;
    }

    private static void imageUrl(Map<String, String> errors, String field, BannerImage image) {
        if (image == null) {
            return;
        }
        String url = image.getUrl();
        if (url == null || url.isBlank()) {
            errors.put(field, "banners.validation.image_required");
            return;
        }
        // SEC-12: creative images must be https (or internal path / localhost for dev); plain http is no longer rewritten-and-accepted.
        if (!(BannerUrlPolicy.validExternal(url) || BannerUrlPolicy.validInternal(url) || url.startsWith("http://localhost:") || url.startsWith("http://localhost/"))) {
            errors.put(field, "banners.validation.invalid_url");
        }
    }
}
