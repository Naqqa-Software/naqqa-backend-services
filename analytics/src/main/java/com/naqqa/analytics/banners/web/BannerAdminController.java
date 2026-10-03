package com.naqqa.analytics.banners.web;

import com.naqqa.analytics.banners.engine.BannerSlots;
import com.naqqa.analytics.banners.model.BannerCampaign;
import com.naqqa.analytics.banners.model.BannerCreative;
import com.naqqa.analytics.banners.model.BannerPriority;
import com.naqqa.analytics.banners.model.BannerStatus;
import com.naqqa.analytics.banners.security.BannerAccess;
import com.naqqa.analytics.banners.service.BannerAssetService;
import com.naqqa.analytics.banners.service.BannerCampaignService;
import com.naqqa.analytics.banners.service.BannerException;
import com.naqqa.analytics.banners.service.BannerStatsService;
import com.naqqa.analytics.banners.store.BannerRepository;
import com.naqqa.analytics.banners.web.BannerDtos.AssetDto;
import com.naqqa.analytics.banners.web.BannerDtos.CampaignDetailDto;
import com.naqqa.analytics.banners.web.BannerDtos.DecisionRequest;
import com.naqqa.analytics.banners.web.BannerDtos.PageDto;
import com.naqqa.analytics.banners.web.BannerDtos.SlotDto;
import com.naqqa.analytics.banners.web.BannerDtos.SummaryDto;
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

@RestController
@RequestMapping("${naqqa.analytics.banners.admin-path:/api/admin/ad-campaigns}")
public class BannerAdminController {

    private final BannerCampaignService campaigns;
    private final BannerRepository repository;
    private final BannerAssetService assets;
    private final BannerStatsService stats;
    private final NaqqaAnalyticsProperties.Permissions permissions;

    public BannerAdminController(BannerCampaignService campaigns, BannerRepository repository, BannerAssetService assets,
                                 BannerStatsService stats, NaqqaAnalyticsProperties.Permissions permissions) {
        this.campaigns = campaigns;
        this.repository = repository;
        this.assets = assets;
        this.stats = stats;
        this.permissions = permissions;
    }

    @GetMapping
    public PageDto<BannerCampaign> list(@RequestParam(required = false) String q,
                                        @RequestParam(required = false) BannerStatus status,
                                        @RequestParam(required = false) BannerPriority priority,
                                        @RequestParam(required = false) String companyId,
                                        @RequestParam(required = false) String slot,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size,
                                        Authentication authentication) {
        read(authentication);
        BannerRepository.Page<BannerCampaign> p = repository.list(new BannerRepository.CampaignFilter(q, status, priority, companyId, null, slot), page, size);
        return new PageDto<>(p.content(), p.total(), p.page(), p.size());
    }

    @GetMapping("/summary")
    public SummaryDto summary(Authentication authentication) {
        read(authentication);
        return new SummaryDto(repository.countByStatus(BannerStatus.PENDING, null), repository.countByStatus(BannerStatus.ACTIVE, null));
    }

    @GetMapping("/slots")
    public List<SlotDto> slots(Authentication authentication) {
        read(authentication);
        return BannerSlots.ALL.stream().map(SlotDto::of).toList();
    }

    @GetMapping("/pending")
    public PageDto<BannerCampaign> pending(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
                                           Authentication authentication) {
        read(authentication);
        BannerRepository.Page<BannerCampaign> p = repository.list(new BannerRepository.CampaignFilter(null, BannerStatus.PENDING, null, null, null, null), page, size);
        return new PageDto<>(p.content(), p.total(), p.page(), p.size());
    }

    @GetMapping("/stats")
    public Map<String, Object> overview(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                      @RequestParam(required = false) String companyId,
                                                      Authentication authentication) {
        read(authentication);
        return BannerStatsService.view(stats.overview(from, to, companyId == null || companyId.isBlank() ? null : List.of(companyId)));
    }

    @GetMapping("/{id}")
    public CampaignDetailDto get(@PathVariable String id, Authentication authentication) {
        read(authentication);
        return campaigns.detail(campaigns.require(id));
    }

    @PostMapping
    public ResponseEntity<CampaignDetailDto> create(@RequestBody BannerCampaign body, Authentication authentication) {
        BannerAccess access = manage(authentication);
        BannerCampaign saved = campaigns.adminCreate(body, access.userId(), access.has(permissions.getBannersApprove()));
        return ResponseEntity.status(HttpStatus.CREATED).body(campaigns.detail(saved));
    }

    @PutMapping("/{id}")
    public CampaignDetailDto update(@PathVariable String id, @RequestBody BannerCampaign body, Authentication authentication) {
        BannerAccess access = manage(authentication);
        return campaigns.detail(campaigns.adminUpdate(id, body, access.has(permissions.getBannersApprove())));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, Authentication authentication) {
        manage(authentication);
        campaigns.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/status")
    public CampaignDetailDto status(@PathVariable String id, @RequestParam BannerStatus value, Authentication authentication) {
        BannerAccess access = manage(authentication);
        campaigns.setStatus(id, value, access.userId(), access.has(permissions.getBannersApprove()));
        return campaigns.detail(campaigns.require(id));
    }

    @PostMapping("/{id}/approve")
    public CampaignDetailDto approve(@PathVariable String id, Authentication authentication) {
        BannerAccess access = approver(authentication);
        campaigns.approve(id, access.userId());
        return campaigns.detail(campaigns.require(id));
    }

    @PostMapping("/{id}/reject")
    public CampaignDetailDto reject(@PathVariable String id, @RequestBody(required = false) DecisionRequest body, Authentication authentication) {
        BannerAccess access = approver(authentication);
        campaigns.reject(id, access.userId(), body == null ? null : body.reason());
        return campaigns.detail(campaigns.require(id));
    }

    @PostMapping("/{id}/creatives")
    public BannerCreative addCreative(@PathVariable String id, @RequestBody BannerCreative body, Authentication authentication) {
        manage(authentication);
        return campaigns.saveCreative(campaigns.require(id), null, body);
    }

    @PutMapping("/{id}/creatives/{creativeId}")
    public BannerCreative updateCreative(@PathVariable String id, @PathVariable String creativeId, @RequestBody BannerCreative body,
                                         Authentication authentication) {
        manage(authentication);
        return campaigns.saveCreative(campaigns.require(id), creativeId, body);
    }

    @DeleteMapping("/{id}/creatives/{creativeId}")
    public ResponseEntity<Void> deleteCreative(@PathVariable String id, @PathVariable String creativeId, Authentication authentication) {
        manage(authentication);
        campaigns.deleteCreative(campaigns.require(id), creativeId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/assets")
    public AssetDto upload(@RequestPart("file") MultipartFile file, @RequestParam(required = false) String slot,
                           @RequestParam(required = false) String variant, Authentication authentication) throws IOException {
        manage(authentication);
        return assets.upload(file.getBytes(), slot, variant);
    }

    @GetMapping("/{id}/stats")
    public Map<String, Object> campaignStats(@PathVariable String id,
                                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                           Authentication authentication) {
        read(authentication);
        return BannerStatsService.view(stats.campaign(campaigns.require(id), from, to));
    }

    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable String id,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         Authentication authentication) {
        read(authentication);
        return BannerCsvResponse.of(stats.campaign(campaigns.require(id), from, to));
    }

    private BannerAccess read(Authentication authentication) {
        BannerAccess access = BannerAccess.of(authentication);
        if (!access.has(permissions.getBannersManage()) && !access.has(permissions.getBannersApprove())) {
            throw BannerException.forbidden();
        }
        return access;
    }

    private BannerAccess manage(Authentication authentication) {
        BannerAccess access = BannerAccess.of(authentication);
        if (!access.has(permissions.getBannersManage())) {
            throw BannerException.forbidden();
        }
        return access;
    }

    private BannerAccess approver(Authentication authentication) {
        BannerAccess access = BannerAccess.of(authentication);
        if (!access.has(permissions.getBannersApprove())) {
            throw BannerException.forbidden();
        }
        return access;
    }
}
