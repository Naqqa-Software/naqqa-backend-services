package com.naqqa.analytics.banners;

import com.naqqa.analytics.banners.engine.BannerPacingCalculator;
import com.naqqa.analytics.banners.model.BannerCampaign;
import com.naqqa.analytics.banners.model.BannerPriority;
import com.naqqa.analytics.banners.model.BannerStatus;
import com.naqqa.analytics.banners.service.BannerCampaignService;
import com.naqqa.analytics.banners.service.BannerCampaignService.PartnerScope;
import com.naqqa.analytics.banners.service.BannerException;
import com.naqqa.analytics.banners.store.BannerCampaignCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static com.naqqa.analytics.banners.BannerFixtures.campaign;
import static com.naqqa.analytics.banners.BannerFixtures.creative;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BannerPartnerScopeTest {

    private BannerFixtures.FakeRepository repository;
    private BannerCampaignService service;
    private final PartnerScope company8 = new PartnerScope("u1", Set.of("8"));
    private final PartnerScope company9 = new PartnerScope("u2", Set.of("9"));

    @BeforeEach
    void setUp() {
        repository = new BannerFixtures.FakeRepository();
        Clock clock = Clock.fixed(BannerFixtures.NOW, ZoneOffset.UTC);
        service = new BannerCampaignService(repository, new BannerCampaignCache(repository, clock, 30_000), new BannerPacingCalculator(), clock, 0.05);
    }

    private BannerCampaign proposal() {
        BannerCampaign c = campaign(null, BannerPriority.FALLBACK, "home_between_1");
        c.setStatus(BannerStatus.ACTIVE);
        c.setCompanyId(null);
        c.setServedImpressions(999);
        return c;
    }

    @Test
    void proposalIsForcedToOwnCompanyPendingAndPaid() {
        BannerCampaign saved = service.partnerCreate(proposal(), company8, true);
        assertThat(saved.getCompanyId()).isEqualTo("8");
        assertThat(saved.getProposedByCompanyId()).isEqualTo("8");
        assertThat(saved.getStatus()).isEqualTo(BannerStatus.PENDING);
        assertThat(saved.getPriority()).isEqualTo(BannerPriority.PAID);
        assertThat(saved.isPaid()).isTrue();
        assertThat(saved.getCreatedBy()).isEqualTo("u1");
        assertThat(service.partnerCreate(proposal(), company8, false).getStatus()).isEqualTo(BannerStatus.DRAFT);
    }

    @Test
    void cannotProposeForAnotherCompany() {
        BannerCampaign c = proposal();
        c.setCompanyId("9");
        assertThatThrownBy(() -> service.partnerCreate(c, company8, true))
                .isInstanceOf(BannerException.class)
                .satisfies(e -> assertThat(((BannerException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.partnerCreate(proposal(), new PartnerScope("u", Set.of()), true)).isInstanceOf(BannerException.class);
    }

    @Test
    void multiCompanyPartnerMustChooseOwnedCompany() {
        PartnerScope both = new PartnerScope("u", Set.of("8", "10"));
        assertThatThrownBy(() -> service.partnerCreate(proposal(), both, true)).isInstanceOf(BannerException.class);
        BannerCampaign c = proposal();
        c.setCompanyId("10");
        assertThat(service.partnerCreate(c, both, true).getCompanyId()).isEqualTo("10");
    }

    @Test
    void otherCompanyCannotReadOrEditOrDelete() {
        BannerCampaign saved = service.partnerCreate(proposal(), company8, true);
        assertThatThrownBy(() -> service.requireOwned(saved.getId(), company9))
                .isInstanceOf(BannerException.class)
                .satisfies(e -> assertThat(((BannerException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.partnerUpdate(saved.getId(), proposal(), company9, true)).isInstanceOf(BannerException.class);
        assertThatThrownBy(() -> service.partnerDelete(saved.getId(), company9)).isInstanceOf(BannerException.class);
        assertThatThrownBy(() -> service.partnerPause(saved.getId(), company9, true)).isInstanceOf(BannerException.class);
        assertThat(repository.campaigns).containsKey(saved.getId());
    }

    @Test
    void updateCannotMoveCampaignToAnotherCompany() {
        BannerCampaign saved = service.partnerCreate(proposal(), company8, false);
        BannerCampaign edit = proposal();
        edit.setCompanyId("9");
        edit.setName("Renamed");
        BannerCampaign updated = service.partnerUpdate(saved.getId(), edit, company8, true);
        assertThat(updated.getCompanyId()).isEqualTo("8");
        assertThat(updated.getName()).isEqualTo("Renamed");
        assertThat(updated.getStatus()).isEqualTo(BannerStatus.PENDING);
    }

    @Test
    void activeCampaignIsLockedForPartnerButCanBePausedAndResumed() {
        BannerCampaign saved = service.partnerCreate(proposal(), company8, true);
        repository.creatives.put("x", creative("x", saved.getId(), 1));
        service.approve(saved.getId(), "admin");
        assertThat(repository.campaign(saved.getId()).getStatus()).isEqualTo(BannerStatus.ACTIVE);
        assertThatThrownBy(() -> service.partnerUpdate(saved.getId(), proposal(), company8, true)).isInstanceOf(BannerException.class);
        assertThatThrownBy(() -> service.partnerDelete(saved.getId(), company8)).isInstanceOf(BannerException.class);
        service.partnerPause(saved.getId(), company8, true);
        assertThat(repository.campaign(saved.getId()).getStatus()).isEqualTo(BannerStatus.PAUSED);
        service.partnerPause(saved.getId(), company8, false);
        assertThat(repository.campaign(saved.getId()).getStatus()).isEqualTo(BannerStatus.ACTIVE);
    }

    @Test
    void neverApprovedCampaignCannotBeResumedByPartner() {
        BannerCampaign saved = service.partnerCreate(proposal(), company8, true);
        repository.campaigns.get(saved.getId()).setStatus(BannerStatus.PAUSED);
        assertThatThrownBy(() -> service.partnerPause(saved.getId(), company8, false)).isInstanceOf(BannerException.class);
    }

    @Test
    void approvalFlow() {
        BannerCampaign saved = service.partnerCreate(proposal(), company8, true);
        assertThatThrownBy(() -> service.approve(saved.getId(), "admin")).isInstanceOf(BannerException.class);
        service.reject(saved.getId(), "admin", "Imagine neclara");
        assertThat(repository.campaign(saved.getId()).getStatus()).isEqualTo(BannerStatus.REJECTED);
        assertThat(repository.campaign(saved.getId()).getRejectionReason()).isEqualTo("Imagine neclara");
        BannerCampaign resubmitted = service.partnerUpdate(saved.getId(), proposal(), company8, true);
        assertThat(resubmitted.getStatus()).isEqualTo(BannerStatus.PENDING);
        assertThat(resubmitted.getRejectionReason()).isNull();
    }

    @Test
    void companyTopTargetingLimitedToOwnCompany() {
        BannerCampaign c = campaign(null, BannerPriority.PAID, "company_top");
        c.getTargeting().setCompanyIds(List.of("9"));
        assertThatThrownBy(() -> service.partnerCreate(c, company8, true)).isInstanceOf(BannerException.class);
    }

    @Test
    void validationErrorsAreReported() {
        BannerCampaign c = proposal();
        c.setName(" ");
        c.getTargeting().setSlots(List.of("nowhere"));
        c.getTargeting().setHours(List.of(25));
        assertThatThrownBy(() -> service.partnerCreate(c, company8, true))
                .isInstanceOf(BannerException.class)
                .satisfies(e -> assertThat(((BannerException) e).getExtra().get("errors").toString()).contains("name", "targeting.slots", "targeting.hours"));
    }
}
