package com.naqqa.analytics.banners.service;

import com.naqqa.analytics.banners.engine.BannerRequest;
import com.naqqa.analytics.banners.engine.BannerSlotDefaults;
import com.naqqa.analytics.banners.engine.BannerSlotDefaults.ParamSpec;
import com.naqqa.analytics.banners.engine.BannerSlots;
import com.naqqa.analytics.banners.engine.BannerTargetingEngine;
import com.naqqa.analytics.banners.model.BannerSlotSettings;
import com.naqqa.analytics.banners.store.BannerSlotRepository;
import com.naqqa.analytics.banners.web.BannerDtos.PublicSlotDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
public class BannerSlotRegistry {

    private static final Set<String> TEXT_LANGS = Set.of("ro", "ru", "en");
    private static final int MAX_SIDE = 4000;

    private final BannerSlotRepository repository;
    private final Clock clock;
    private final long ttlMs;
    private final Object lock = new Object();
    private volatile Map<String, BannerSlotSettings> snapshot = Map.of();
    private volatile long loadedAt;

    public BannerSlotRegistry(BannerSlotRepository repository, Clock clock, long ttlMs) {
        this.repository = repository;
        this.clock = clock;
        this.ttlMs = Math.max(1000L, ttlMs);
    }

    public static BannerSlotSettings defaults(BannerSlots.Slot slot) {
        BannerSlotDefaults.Defaults d = BannerSlotDefaults.get(slot.id());
        BannerSlotSettings s = new BannerSlotSettings();
        s.setId(slot.id());
        s.setEnabled(BannerSlotDefaults.enabledByDefault(slot.id()));
        s.setName(new LinkedHashMap<>(d.name()));
        s.setDescription(new LinkedHashMap<>(d.description()));
        s.setPage(slot.page());
        s.setDesktop(size(slot.desktop()));
        s.setMobile(size(slot.mobile()));
        s.setDesktopEnabled(true);
        s.setMobileEnabled(slot.mobile() != null);
        s.setPageTypes(new ArrayList<>());
        s.setParams(BannerSlotDefaults.params(slot.id()));
        s.setReservedForCompany(slot.reservedForCompany());
        s.setMounted(slot.mounted());
        return s;
    }

    public void seed() {
        try {
            int created = 0;
            for (BannerSlots.Slot slot : BannerSlots.ALL) {
                if (!repository.exists(slot.id())) {
                    BannerSlotSettings s = defaults(slot);
                    Instant now = clock.instant();
                    s.setCreatedAt(now);
                    s.setUpdatedAt(now);
                    try {
                        repository.insert(s);
                        created++;
                    } catch (RuntimeException e) {
                        log.debug("Banner slot {} was seeded concurrently: {}", slot.id(), e.getMessage());
                    }
                }
            }
            if (created > 0) {
                log.info("Seeded {} banner slot settings with defaults", created);
            }
        } catch (RuntimeException e) {
            log.warn("Banner slot settings could not be seeded: {}", e.getMessage());
        }
        invalidate();
    }

    public List<BannerSlotSettings> all() {
        Map<String, BannerSlotSettings> current = current();
        List<BannerSlotSettings> out = new ArrayList<>(BannerSlots.ALL.size());
        for (BannerSlots.Slot slot : BannerSlots.ALL) {
            out.add(current.get(slot.id()));
        }
        return out;
    }

    public BannerSlotSettings settings(String id) {
        BannerSlots.Slot slot = BannerSlots.get(id);
        return slot == null ? null : current().get(slot.id());
    }

    public BannerSlots.Slot slot(String id) {
        BannerSlots.Slot base = BannerSlots.get(id);
        if (base == null) {
            return null;
        }
        BannerSlotSettings s = current().get(base.id());
        if (s == null) {
            return base;
        }
        return new BannerSlots.Slot(base.id(), base.page(), size(s.getDesktop(), base.desktop()), s.getMobile() == null ? null : size(s.getMobile(), base.mobile()),
                base.reservedForCompany(), base.mounted());
    }

    public boolean enabled(String id) {
        BannerSlotSettings s = settings(id);
        return s != null && s.isEnabled() && s.isMounted() && (s.isDesktopEnabled() || s.isMobileEnabled());
    }

    public boolean servable(BannerRequest request) {
        BannerSlotSettings s = settings(request.slot());
        if (s == null || !s.isEnabled() || !s.isMounted()) {
            return false;
        }
        String device = BannerTargetingEngine.normalizeDevice(request.device());
        if ("mobile".equals(device) ? !s.isMobileEnabled() : !s.isDesktopEnabled()) {
            return false;
        }
        List<String> pageTypes = s.getPageTypes();
        if (pageTypes == null || pageTypes.isEmpty()) {
            return true;
        }
        String pageType = request.pageType();
        return pageType != null && pageTypes.stream().anyMatch(p -> p.equalsIgnoreCase(pageType.trim()));
    }

    public List<PublicSlotDto> publicView() {
        List<PublicSlotDto> out = new ArrayList<>();
        for (BannerSlotSettings s : all()) {
            out.add(PublicSlotDto.of(s));
        }
        return out;
    }

    public BannerSlotSettings update(String id, BannerSlotSettings input, String userId) {
        BannerSlots.Slot slot = BannerSlots.get(id);
        if (slot == null) {
            throw BannerException.notFound();
        }
        BannerSlotSettings current = current().get(slot.id());
        BannerSlotSettings next = sanitize(slot, input == null ? new BannerSlotSettings() : input);
        next.setCreatedAt(current != null && current.getCreatedAt() != null ? current.getCreatedAt() : clock.instant());
        next.setUpdatedAt(clock.instant());
        next.setUpdatedBy(userId);
        BannerSlotSettings saved = repository.save(next);
        invalidate();
        return normalize(slot, saved);
    }

    public BannerSlotSettings reset(String id, String userId) {
        BannerSlots.Slot slot = BannerSlots.get(id);
        if (slot == null) {
            throw BannerException.notFound();
        }
        BannerSlotSettings current = current().get(slot.id());
        BannerSlotSettings next = defaults(slot);
        next.setCreatedAt(current != null && current.getCreatedAt() != null ? current.getCreatedAt() : clock.instant());
        next.setUpdatedAt(clock.instant());
        next.setUpdatedBy(userId);
        BannerSlotSettings saved = repository.save(next);
        invalidate();
        return normalize(slot, saved);
    }

    public void invalidate() {
        loadedAt = 0;
    }

    private Map<String, BannerSlotSettings> current() {
        long now = clock.millis();
        if (loadedAt == 0 || now - loadedAt > ttlMs) {
            synchronized (lock) {
                if (loadedAt == 0 || now - loadedAt > ttlMs) {
                    reload();
                }
            }
        }
        return snapshot;
    }

    private void reload() {
        Map<String, BannerSlotSettings> stored = new HashMap<>();
        boolean ok = true;
        try {
            for (BannerSlotSettings s : repository.findAll()) {
                if (s.getId() != null) {
                    stored.put(s.getId(), s);
                }
            }
        } catch (RuntimeException e) {
            ok = false;
            log.warn("Banner slot settings could not be loaded: {}", e.getMessage());
        }
        if (!ok && !snapshot.isEmpty()) {
            loadedAt = clock.millis();
            return;
        }
        Map<String, BannerSlotSettings> out = new LinkedHashMap<>();
        for (BannerSlots.Slot slot : BannerSlots.ALL) {
            BannerSlotSettings s = stored.get(slot.id());
            out.put(slot.id(), s == null ? defaults(slot) : normalize(slot, s));
        }
        snapshot = Collections.unmodifiableMap(out);
        loadedAt = clock.millis();
    }

    static BannerSlotSettings normalize(BannerSlots.Slot slot, BannerSlotSettings s) {
        BannerSlotSettings d = defaults(slot);
        BannerSlotSettings out = new BannerSlotSettings();
        out.setId(slot.id());
        out.setEnabled(s.isEnabled());
        out.setName(texts(s.getName(), d.getName(), 80));
        out.setDescription(texts(s.getDescription(), d.getDescription(), 300));
        out.setPage(slot.page());
        out.setDesktop(validSize(s.getDesktop()) ? s.getDesktop() : d.getDesktop());
        out.setMobile(s.getMobile() == null ? null : validSize(s.getMobile()) ? s.getMobile() : d.getMobile());
        out.setDesktopEnabled(s.isDesktopEnabled());
        out.setMobileEnabled(s.isMobileEnabled() && out.getMobile() != null);
        out.setPageTypes(pageTypes(slot.id(), s.getPageTypes()));
        out.setShowLabel(s.getShowLabel());
        out.setLazy(s.getLazy());
        out.setReserve(s.getReserve());
        out.setEager(s.getEager());
        Map<String, Object> params = new LinkedHashMap<>(d.getParams());
        if (s.getParams() != null) {
            for (ParamSpec spec : BannerSlotDefaults.get(slot.id()).params()) {
                Object v = coerce(spec, s.getParams().get(spec.key()));
                if (v != null && inRange(spec, v)) {
                    params.put(spec.key(), v);
                }
            }
        }
        out.setParams(params);
        out.setReservedForCompany(slot.reservedForCompany());
        out.setMounted(slot.mounted());
        out.setUpdatedBy(s.getUpdatedBy());
        out.setCreatedAt(s.getCreatedAt());
        out.setUpdatedAt(s.getUpdatedAt());
        return out;
    }

    private static BannerSlotSettings sanitize(BannerSlots.Slot slot, BannerSlotSettings in) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (!validSize(in.getDesktop())) {
            errors.put("desktop", "banners.validation.range");
        }
        if (in.getMobile() != null && !validSize(in.getMobile())) {
            errors.put("mobile", "banners.validation.range");
        }
        if (in.getName() != null && in.getName().values().stream().anyMatch(v -> v != null && v.trim().length() > 80)) {
            errors.put("name", "banners.validation.too_long");
        }
        if (in.getDescription() != null && in.getDescription().values().stream().anyMatch(v -> v != null && v.trim().length() > 300)) {
            errors.put("description", "banners.validation.too_long");
        }
        List<String> options = BannerSlotDefaults.get(slot.id()).pageTypeOptions();
        if (in.getPageTypes() != null && in.getPageTypes().stream()
                .anyMatch(p -> p == null || !options.contains(p.trim().toLowerCase(Locale.ROOT)))) {
            errors.put("pageTypes", "banners.validation.unknown_page_type");
        }
        Map<String, Object> params = new LinkedHashMap<>(BannerSlotDefaults.params(slot.id()));
        Map<String, Object> raw = in.getParams() == null ? Map.of() : in.getParams();
        for (ParamSpec spec : BannerSlotDefaults.get(slot.id()).params()) {
            if (!raw.containsKey(spec.key())) {
                continue;
            }
            Object v = coerce(spec, raw.get(spec.key()));
            if (v == null || !inRange(spec, v)) {
                errors.put("params." + spec.key(), "banners.validation.range");
            } else {
                params.put(spec.key(), v);
            }
        }
        if (!errors.isEmpty()) {
            throw new BannerException(HttpStatus.BAD_REQUEST, "banners.validation", "Invalid slot settings", Map.of("errors", errors));
        }
        BannerSlotSettings out = new BannerSlotSettings();
        out.setId(slot.id());
        out.setEnabled(in.isEnabled());
        out.setName(clean(in.getName(), 80));
        out.setDescription(clean(in.getDescription(), 300));
        out.setPage(slot.page());
        out.setDesktop(new BannerSlotSettings.Size(in.getDesktop().getW(), in.getDesktop().getH()));
        out.setMobile(in.getMobile() == null ? null : new BannerSlotSettings.Size(in.getMobile().getW(), in.getMobile().getH()));
        out.setDesktopEnabled(in.isDesktopEnabled());
        out.setMobileEnabled(in.isMobileEnabled() && in.getMobile() != null);
        out.setPageTypes(pageTypes(slot.id(), in.getPageTypes()));
        out.setShowLabel(in.getShowLabel());
        out.setLazy(in.getLazy());
        out.setReserve(in.getReserve());
        out.setEager(in.getEager());
        out.setParams(params);
        out.setReservedForCompany(slot.reservedForCompany());
        out.setMounted(slot.mounted());
        return out;
    }

    private static List<String> pageTypes(String slot, List<String> values) {
        List<String> options = BannerSlotDefaults.get(slot).pageTypeOptions();
        List<String> out = new ArrayList<>();
        if (values == null) {
            return out;
        }
        for (String v : values) {
            if (v == null) {
                continue;
            }
            String p = v.trim().toLowerCase(Locale.ROOT);
            if (options.contains(p) && !out.contains(p)) {
                out.add(p);
            }
        }
        return out.size() == options.size() ? new ArrayList<>() : out;
    }

    private static Object coerce(ParamSpec spec, Object value) {
        if (value == null) {
            return null;
        }
        if (spec.type() == BannerSlotDefaults.ParamType.BOOL) {
            if (value instanceof Boolean b) {
                return b;
            }
            String s = String.valueOf(value).trim();
            return "true".equalsIgnoreCase(s) ? Boolean.TRUE : "false".equalsIgnoreCase(s) ? Boolean.FALSE : null;
        }
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return d == Math.rint(d) ? (int) d : null;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean inRange(ParamSpec spec, Object value) {
        if (spec.type() == BannerSlotDefaults.ParamType.BOOL) {
            return value instanceof Boolean;
        }
        return value instanceof Integer i && i >= spec.min() && i <= spec.max();
    }

    private static Map<String, String> texts(Map<String, String> values, Map<String, String> fallback, int max) {
        Map<String, String> out = new LinkedHashMap<>(fallback);
        out.putAll(clean(values, max));
        return out;
    }

    private static Map<String, String> clean(Map<String, String> values, int max) {
        Map<String, String> out = new LinkedHashMap<>();
        if (values == null) {
            return out;
        }
        values.forEach((k, v) -> {
            if (k != null && v != null && !v.isBlank() && TEXT_LANGS.contains(k.toLowerCase(Locale.ROOT))) {
                String t = v.trim();
                out.put(k.toLowerCase(Locale.ROOT), t.length() > max ? t.substring(0, max) : t);
            }
        });
        return out;
    }

    private static boolean validSize(BannerSlotSettings.Size size) {
        return size != null && size.getW() >= 1 && size.getH() >= 1 && size.getW() <= MAX_SIDE && size.getH() <= MAX_SIDE;
    }

    private static BannerSlotSettings.Size size(BannerSlots.Size size) {
        return size == null ? null : new BannerSlotSettings.Size(size.w(), size.h());
    }

    private static BannerSlots.Size size(BannerSlotSettings.Size size, BannerSlots.Size fallback) {
        return validSize(size) ? new BannerSlots.Size(size.getW(), size.getH()) : fallback;
    }
}
