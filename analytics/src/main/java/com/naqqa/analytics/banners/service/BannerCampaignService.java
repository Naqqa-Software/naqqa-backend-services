package com.naqqa.analytics.banners.service;

import com.naqqa.analytics.banners.engine.BannerPacingCalculator;
import com.naqqa.analytics.banners.model.BannerCampaign;
import com.naqqa.analytics.banners.model.BannerCreative;
import com.naqqa.analytics.banners.model.BannerPriority;
import com.naqqa.analytics.banners.model.BannerStatus;
import com.naqqa.analytics.banners.model.BannerTargeting;
import com.naqqa.analytics.banners.store.BannerCampaignCache;
import com.naqqa.analytics.banners.store.BannerRepository;
import com.naqqa.analytics.banners.web.BannerDtos.CampaignDetailDto;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class BannerCampaignService {

    public record PartnerScope(String userId, Set<String> companyIds) {
        public boolean owns(BannerCampaign c) {
            return c != null && c.getCompanyId() != null && companyIds != null && companyIds.contains(c.getCompanyId());
        }
    }

    private static final Set<BannerStatus> PARTNER_EDITABLE = EnumSet.of(BannerStatus.DRAFT, BannerStatus.PENDING, BannerStatus.REJECTED);

    private final BannerRepository repository;
    private final BannerCampaignCache cache;
    private final BannerPacingCalculator pacing;
    private final Clock clock;
    private final double ratioTolerance;

    public BannerCampaignService(BannerRepository repository, BannerCampaignCache cache, BannerPacingCalculator pacing, Clock clock,
                                 double ratioTolerance) {
        this.repository = repository;
        this.cache = cache;
        this.pacing = pacing;
        this.clock = clock;
        this.ratioTolerance = ratioTolerance;
    }

    public CampaignDetailDto detail(BannerCampaign c) {
        return new CampaignDetailDto(c, repository.creatives(c.getId()), pacing.pacing(c, clock.instant()));
    }

    public BannerCampaign require(String id) {
        BannerCampaign c = repository.campaign(id);
        if (c == null) {
            throw BannerException.notFound();
        }
        return c;
    }

    public BannerCampaign requireOwned(String id, PartnerScope scope) {
        BannerCampaign c = repository.campaign(id);
        if (c == null || !scope.owns(c)) {
            throw BannerException.notFound();
        }
        return c;
    }

    public BannerCampaign adminCreate(BannerCampaign input, String userId, boolean canApprove) {
        BannerCampaign c = sanitize(input);
        if (c.getStatus() == null) {
            c.setStatus(BannerStatus.DRAFT);
        }
        if (c.getStatus() == BannerStatus.ACTIVE && !canApprove) {
            c.setStatus(BannerStatus.PENDING);
        }
        if (c.getStatus() == BannerStatus.REJECTED || c.getStatus() == BannerStatus.ENDED) {
            c.setStatus(BannerStatus.DRAFT);
        }
        c.setCreatedBy(userId);
        validate(c);
        BannerCampaign saved = repository.insert(c);
        cache.invalidate();
        return saved;
    }

    public BannerCampaign adminUpdate(String id, BannerCampaign input, boolean canApprove) {
        BannerCampaign current = require(id);
        BannerCampaign c = sanitize(input);
        c.setId(id);
        if (c.getStatus() == null) {
            c.setStatus(current.getStatus());
        }
        if (c.getStatus() == BannerStatus.ACTIVE && current.getStatus() != BannerStatus.ACTIVE && !canApprove
                && current.getStatus() != BannerStatus.PAUSED) {
            throw new BannerException(HttpStatus.FORBIDDEN, "banners.approve_required", "Approval permission required");
        }
        c.setRejectionReason(c.getStatus() == BannerStatus.REJECTED ? current.getRejectionReason() : null);
        validate(c);
        BannerCampaign saved = repository.updateEditable(c);
        cache.invalidate();
        return saved;
    }

    public void setStatus(String id, BannerStatus status, String userId, boolean canApprove) {
        BannerCampaign current = require(id);
        if (status == BannerStatus.ACTIVE && !canApprove && current.getStatus() != BannerStatus.PAUSED) {
            throw new BannerException(HttpStatus.FORBIDDEN, "banners.approve_required", "Approval permission required");
        }
        if (status == BannerStatus.ACTIVE && repository.creatives(id).stream().noneMatch(BannerCreative::isActive)) {
            throw BannerException.conflict("banners.no_creatives", "Campaign has no active creatives");
        }
        repository.setStatus(id, status, status == BannerStatus.ACTIVE ? userId : null, null);
        cache.invalidate();
    }

    public void approve(String id, String reviewer) {
        BannerCampaign c = require(id);
        if (c.getStatus() != BannerStatus.PENDING && c.getStatus() != BannerStatus.DRAFT && c.getStatus() != BannerStatus.REJECTED) {
            throw BannerException.conflict("banners.invalid_status", "Campaign is not awaiting approval");
        }
        if (repository.creatives(id).stream().noneMatch(BannerCreative::isActive)) {
            throw BannerException.conflict("banners.no_creatives", "Campaign has no active creatives");
        }
        repository.setStatus(id, BannerStatus.ACTIVE, reviewer, null);
        cache.invalidate();
    }

    public void reject(String id, String reviewer, String reason) {
        BannerCampaign c = require(id);
        if (c.getStatus() != BannerStatus.PENDING) {
            throw BannerException.conflict("banners.invalid_status", "Campaign is not awaiting approval");
        }
        String r = reason == null ? "" : reason.trim();
        if (r.length() > 500) {
            r = r.substring(0, 500);
        }
        repository.setStatus(id, BannerStatus.REJECTED, reviewer, r);
        cache.invalidate();
    }

    public void delete(String id) {
        require(id);
        repository.deleteCampaign(id);
        cache.invalidate();
    }

    public BannerCampaign partnerCreate(BannerCampaign input, PartnerScope scope, boolean submit) {
        String companyId = companyFor(input.getCompanyId(), scope);
        BannerCampaign c = sanitize(input);
        c.setCompanyId(companyId);
        c.setProposedByCompanyId(companyId);
        c.setPriority(BannerPriority.PAID);
        c.setPaid(true);
        c.setStatus(submit ? BannerStatus.PENDING : BannerStatus.DRAFT);
        c.setCreatedBy(scope.userId());
        c.setRejectionReason(null);
        restrictPartnerTargeting(c, companyId);
        validate(c);
        BannerCampaign saved = repository.insert(c);
        cache.invalidate();
        return saved;
    }

    public BannerCampaign partnerUpdate(String id, BannerCampaign input, PartnerScope scope, boolean submit) {
        BannerCampaign current = requireOwned(id, scope);
        if (!PARTNER_EDITABLE.contains(current.getStatus())) {
            throw BannerException.conflict("banners.locked", "Campaign can no longer be edited");
        }
        BannerCampaign c = sanitize(input);
        c.setId(id);
        c.setCompanyId(current.getCompanyId());
        c.setPriority(BannerPriority.PAID);
        c.setPaid(true);
        c.setStatus(submit ? BannerStatus.PENDING : (current.getStatus() == BannerStatus.PENDING ? BannerStatus.PENDING : BannerStatus.DRAFT));
        c.setRejectionReason(null);
        restrictPartnerTargeting(c, current.getCompanyId());
        validate(c);
        BannerCampaign saved = repository.updateEditable(c);
        cache.invalidate();
        return saved;
    }

    public void partnerPause(String id, PartnerScope scope, boolean pause) {
        BannerCampaign current = requireOwned(id, scope);
        if (pause && current.getStatus() == BannerStatus.ACTIVE) {
            repository.setStatus(id, BannerStatus.PAUSED, null, null);
        } else if (!pause && current.getStatus() == BannerStatus.PAUSED && current.getReviewedAt() != null) {
            repository.setStatus(id, BannerStatus.ACTIVE, null, null);
        } else {
            throw BannerException.conflict("banners.invalid_status", "Status change not allowed");
        }
        cache.invalidate();
    }

    public void partnerDelete(String id, PartnerScope scope) {
        BannerCampaign current = requireOwned(id, scope);
        if (!PARTNER_EDITABLE.contains(current.getStatus())) {
            throw BannerException.conflict("banners.locked", "Only drafts, pending or rejected campaigns can be deleted");
        }
        repository.deleteCampaign(id);
        cache.invalidate();
    }

    public BannerCreative saveCreative(BannerCampaign campaign, String creativeId, BannerCreative input) {
        BannerCreative cr = input == null ? new BannerCreative() : input;
        if (creativeId != null) {
            BannerCreative existing = repository.creative(creativeId);
            if (existing == null || !campaign.getId().equals(existing.getCampaignId())) {
                throw BannerException.notFound();
            }
            cr.setCreatedAt(existing.getCreatedAt());
        }
        cr.setId(creativeId);
        cr.setCampaignId(campaign.getId());
        if (cr.getName() != null && cr.getName().length() > 120) {
            cr.setName(cr.getName().substring(0, 120));
        }
        cr.setAlt(trimTexts(cr.getAlt(), 250));
        cr.setTitle(trimTexts(cr.getTitle(), 120));
        cr.setCta(trimTexts(cr.getCta(), 40));
        Map<String, String> errors = BannerValidation.creative(cr, campaign, ratioTolerance);
        if (!errors.isEmpty()) {
            throw new BannerException(HttpStatus.BAD_REQUEST, "banners.validation", "Invalid creative", Map.of("errors", errors));
        }
        BannerCreative saved = repository.saveCreative(cr);
        cache.invalidate();
        return saved;
    }

    public void deleteCreative(BannerCampaign campaign, String creativeId) {
        BannerCreative existing = repository.creative(creativeId);
        if (existing == null || !campaign.getId().equals(existing.getCampaignId())) {
            throw BannerException.notFound();
        }
        repository.deleteCreative(creativeId);
        cache.invalidate();
    }

    public static String companyFor(String requested, PartnerScope scope) {
        Set<String> ids = scope.companyIds();
        if (ids == null || ids.isEmpty()) {
            throw BannerException.forbidden();
        }
        if (requested != null && !requested.isBlank()) {
            if (!ids.contains(requested)) {
                throw BannerException.forbidden();
            }
            return requested;
        }
        if (ids.size() > 1) {
            throw BannerException.invalid("companyId", "banners.validation.company_required", "Company is required");
        }
        return ids.iterator().next();
    }

    static void restrictPartnerTargeting(BannerCampaign c, String companyId) {
        BannerTargeting t = c.getTargeting();
        if (t == null) {
            return;
        }
        if (t.getSlots() != null && t.getSlots().contains("company_top") && t.getCompanyIds() != null && !t.getCompanyIds().isEmpty()
                && !t.getCompanyIds().equals(List.of(companyId))) {
            throw BannerException.invalid("targeting.companyIds", "banners.validation.company_scope", "Company page slots can target only your company");
        }
    }

    private void validate(BannerCampaign c) {
        Map<String, String> errors = BannerValidation.campaign(c);
        if (!errors.isEmpty()) {
            throw new BannerException(HttpStatus.BAD_REQUEST, "banners.validation", "Invalid campaign", Map.of("errors", errors));
        }
    }

    private static BannerCampaign sanitize(BannerCampaign input) {
        BannerCampaign c = input == null ? new BannerCampaign() : input;
        if (c.getName() != null) {
            c.setName(c.getName().trim());
        }
        if (c.getTargeting() == null) {
            c.setTargeting(new BannerTargeting());
        }
        if (c.getPriority() == null) {
            c.setPriority(BannerPriority.INTERNAL);
        }
        if (c.getWeight() <= 0) {
            c.setWeight(1);
        }
        if (c.getPriority() == BannerPriority.PAID) {
            c.setPaid(true);
        }
        BannerTargeting t = c.getTargeting();
        t.setSlots(lower(t.getSlots()));
        t.setLangs(lower(t.getLangs()));
        t.setDevices(lower(t.getDevices()));
        t.setPageTypes(lower(t.getPageTypes()));
        return c;
    }

    private static List<String> lower(List<String> values) {
        if (values == null) {
            return new java.util.ArrayList<>();
        }
        return new java.util.ArrayList<>(values.stream().filter(v -> v != null && !v.isBlank()).map(v -> v.trim().toLowerCase()).distinct().toList());
    }

    private static Map<String, String> trimTexts(Map<String, String> values, int max) {
        Map<String, String> out = new LinkedHashMap<>();
        if (values == null) {
            return out;
        }
        values.forEach((k, v) -> {
            if (k != null && v != null && !v.isBlank()) {
                String t = v.trim();
                out.put(k.toLowerCase(), t.length() > max ? t.substring(0, max) : t);
            }
        });
        return out;
    }
}
