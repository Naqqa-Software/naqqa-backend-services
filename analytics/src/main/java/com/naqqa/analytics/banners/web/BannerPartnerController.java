package com.naqqa.analytics.banners.web;

import com.naqqa.analytics.banners.engine.BannerSlots;
import com.naqqa.analytics.banners.model.BannerCampaign;
import com.naqqa.analytics.banners.model.BannerCreative;
import com.naqqa.analytics.banners.model.BannerSlotSettings;
import com.naqqa.analytics.banners.model.BannerStatus;
import com.naqqa.analytics.banners.security.BannerAccess;
import com.naqqa.analytics.banners.service.BannerAssetService;
import com.naqqa.analytics.banners.service.BannerCampaignService;
import com.naqqa.analytics.banners.service.BannerCampaignService.PartnerScope;
import com.naqqa.analytics.banners.service.BannerException;
import com.naqqa.analytics.banners.service.BannerSlotRegistry;
import com.naqqa.analytics.banners.service.BannerStatsService;
import com.naqqa.analytics.banners.spi.BannerPartnerScope;
import com.naqqa.analytics.banners.store.BannerRepository;
import com.naqqa.analytics.banners.web.BannerDtos.AssetDto;
import com.naqqa.analytics.banners.web.BannerDtos.CampaignDetailDto;
import com.naqqa.analytics.banners.web.BannerDtos.PageDto;
import com.naqqa.analytics.config.NaqqaAnalyticsProperties;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

@RestController
@RequestMapping("${naqqa.analytics.banners.partner-path:/api/partner/ad-campaigns}")
public class BannerPartnerController {

    private final BannerCampaignService campaigns;
    private final BannerRepository repository;
    private final BannerAssetService assets;
    private final BannerStatsService stats;
    private final Supplier<BannerPartnerScope> scopes;
    private final NaqqaAnalyticsProperties.Permissions permissions;
    private final BannerSlotRegistry slots;

    public BannerPartnerController(BannerCampaignService campaigns, BannerRepository repository, BannerAssetService assets,
                                   BannerStatsService stats, Supplier<BannerPartnerScope> scopes,
                                   NaqqaAnalyticsProperties.Permissions permissions) {
        this(campaigns, repository, assets, stats, scopes, permissions, null);
    }

    public BannerPartnerController(BannerCampaignService campaigns, BannerRepository repository, BannerAssetService assets,
                                   BannerStatsService stats, Supplier<BannerPartnerScope> scopes,
                                   NaqqaAnalyticsProperties.Permissions permissions, BannerSlotRegistry slots) {
        this.campaigns = campaigns;
        this.repository = repository;
        this.assets = assets;
        this.stats = stats;
        this.scopes = scopes;
        this.permissions = permissions;
        this.slots = slots;
    }

    @GetMapping
    public PageDto<BannerCampaign> list(@RequestParam(required = false) BannerStatus status,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size,
                                        Authentication authentication) {
        PartnerScope scope = scope(authentication);
        BannerRepository.Page<BannerCampaign> p = repository.list(
                new BannerRepository.CampaignFilter(null, status, null, null, scope.companyIds(), null), page, size);
        return new PageDto<>(p.content(), p.total(), p.page(), p.size());
    }

    @GetMapping("/slots")
    public List<BannerSlotSettings> slots(Authentication authentication) {
        scope(authentication);
        return BannerSlots.ALL.stream()
                .filter(BannerSlots.Slot::mounted)
                .filter(s -> slots == null || slots.enabled(s.id()))
                .map(s -> slots == null ? BannerSlotRegistry.defaults(s) : slots.settings(s.id()))
                .toList();
    }

    @GetMapping("/companies")
    public Set<String> companies(Authentication authentication) {
        return scope(authentication).companyIds();
    }

    @GetMapping("/stats")
    public Map<String, Object> overview(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                      Authentication authentication) {
        PartnerScope scope = scope(authentication);
        return BannerStatsService.view(stats.overview(from, to, scope.companyIds()));
    }

    @GetMapping("/{id}")
    public CampaignDetailDto get(@PathVariable String id, Authentication authentication) {
        return campaigns.detail(campaigns.requireOwned(id, scope(authentication)));
    }

    @PostMapping
    public ResponseEntity<CampaignDetailDto> propose(@RequestBody BannerCampaign body, @RequestParam(defaultValue = "true") boolean submit,
                                                     Authentication authentication) {
        BannerCampaign saved = campaigns.partnerCreate(body, scope(authentication), submit);
        return ResponseEntity.status(HttpStatus.CREATED).body(campaigns.detail(saved));
    }

    @PutMapping("/{id}")
    public CampaignDetailDto update(@PathVariable String id, @RequestBody BannerCampaign body,
                                    @RequestParam(defaultValue = "true") boolean submit, Authentication authentication) {
        return campaigns.detail(campaigns.partnerUpdate(id, body, scope(authentication), submit));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, Authentication authentication) {
        campaigns.partnerDelete(id, scope(authentication));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/pause")
    public CampaignDetailDto pause(@PathVariable String id, Authentication authentication) {
        PartnerScope scope = scope(authentication);
        campaigns.partnerPause(id, scope, true);
        return campaigns.detail(campaigns.requireOwned(id, scope));
    }

    @PostMapping("/{id}/resume")
    public CampaignDetailDto resume(@PathVariable String id, Authentication authentication) {
        PartnerScope scope = scope(authentication);
        campaigns.partnerPause(id, scope, false);
        return campaigns.detail(campaigns.requireOwned(id, scope));
    }

    @PostMapping("/{id}/creatives")
    public BannerCreative addCreative(@PathVariable String id, @RequestBody BannerCreative body, Authentication authentication) {
        return campaigns.saveCreative(editable(id, scope(authentication)), null, body);
    }

    @PutMapping("/{id}/creatives/{creativeId}")
    public BannerCreative updateCreative(@PathVariable String id, @PathVariable String creativeId, @RequestBody BannerCreative body,
                                         Authentication authentication) {
        return campaigns.saveCreative(editable(id, scope(authentication)), creativeId, body);
    }

    @DeleteMapping("/{id}/creatives/{creativeId}")
    public ResponseEntity<Void> deleteCreative(@PathVariable String id, @PathVariable String creativeId, Authentication authentication) {
        campaigns.deleteCreative(editable(id, scope(authentication)), creativeId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/assets")
    public AssetDto upload(@RequestPart("file") MultipartFile file, @RequestParam(required = false) String slot,
                           @RequestParam(required = false) String variant, Authentication authentication) throws IOException {
        scope(authentication);
        return assets.upload(file.getBytes(), slot, variant);
    }

    @GetMapping("/{id}/stats")
    public Map<String, Object> campaignStats(@PathVariable String id,
                                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                           Authentication authentication) {
        return BannerStatsService.view(stats.campaign(campaigns.requireOwned(id, scope(authentication)), from, to));
    }

    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable String id,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         Authentication authentication) {
        return BannerCsvResponse.of(stats.campaign(campaigns.requireOwned(id, scope(authentication)), from, to));
    }

    private BannerCampaign editable(String id, PartnerScope scope) {
        BannerCampaign c = campaigns.requireOwned(id, scope);
        if (c.getStatus() != BannerStatus.DRAFT && c.getStatus() != BannerStatus.PENDING && c.getStatus() != BannerStatus.REJECTED) {
            throw BannerException.conflict("banners.locked", "Campaign can no longer be edited");
        }
        return c;
    }

    private PartnerScope scope(Authentication authentication) {
        BannerAccess access = BannerAccess.of(authentication);
        if (!access.has(permissions.getBannersPropose())) {
            throw BannerException.forbidden();
        }
        BannerPartnerScope resolver = scopes.get();
        Set<String> ids = resolver == null ? Set.of() : resolver.companyIds(authentication);
        if (ids == null || ids.isEmpty()) {
            throw BannerException.forbidden();
        }
        return new PartnerScope(access.userId(), Set.copyOf(ids));
    }
}
