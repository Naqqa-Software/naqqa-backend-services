package com.naqqa.analytics.banners;

import com.naqqa.analytics.banners.engine.BannerRequest;
import com.naqqa.analytics.banners.engine.BannerSelector.Candidate;
import com.naqqa.analytics.banners.model.BannerCampaign;
import com.naqqa.analytics.banners.model.BannerCreative;
import com.naqqa.analytics.banners.model.BannerDestination;
import com.naqqa.analytics.banners.model.BannerImage;
import com.naqqa.analytics.banners.model.BannerPriority;
import com.naqqa.analytics.banners.model.BannerStatus;
import com.naqqa.analytics.banners.store.BannerRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class BannerFixtures {

    public static final Instant NOW = Instant.parse("2026-10-07T10:30:00Z");

    private BannerFixtures() {
    }

    public static BannerCampaign campaign(String id, BannerPriority priority, String... slots) {
        BannerCampaign c = new BannerCampaign();
        c.setId(id);
        c.setName("Campaign " + id);
        c.setStatus(BannerStatus.ACTIVE);
        c.setPriority(priority);
        c.getTargeting().setSlots(new ArrayList<>(List.of(slots)));
        return c;
    }

    public static BannerCreative creative(String id, String campaignId, int weight) {
        BannerCreative cr = new BannerCreative();
        cr.setId(id);
        cr.setCampaignId(campaignId);
        cr.setWeight(weight);
        cr.setDesktop(new BannerImage("https://cdn.example.com/" + id + ".webp", 1200, 150));
        cr.setMobile(new BannerImage("https://cdn.example.com/" + id + "-m.webp", 390, 120));
        cr.setDestination(new BannerDestination(BannerDestination.Type.INTERNAL, "/promotions/x", null));
        cr.getAlt().put("ro", "Alt " + id);
        return cr;
    }

    public static Candidate candidate(BannerCampaign c, BannerCreative... creatives) {
        return new Candidate(c, List.of(creatives));
    }

    public static Candidate candidate(BannerCampaign c) {
        return new Candidate(c, List.of(creative(c.getId() + "-cr", c.getId(), 1)));
    }

    public static BannerRequest.Builder request(String slot) {
        return BannerRequest.builder().slot(slot).lang("ro").pageType("home").device("desktop").vid("v1").now(NOW);
    }

    public static class FakeRepository extends BannerRepository {
        public final Map<String, BannerCampaign> campaigns = new LinkedHashMap<>();
        public final Map<String, BannerCreative> creatives = new LinkedHashMap<>();
        public int clicks;
        public int served;
        private int seq;

        public FakeRepository() {
            super(null);
        }

        @Override
        public BannerCampaign campaign(String id) {
            return id == null ? null : campaigns.get(id);
        }

        @Override
        public List<BannerCampaign> active() {
            return campaigns.values().stream().filter(c -> c.getStatus() == BannerStatus.ACTIVE).toList();
        }

        @Override
        public BannerCampaign insert(BannerCampaign campaign) {
            campaign.setId("c" + (++seq));
            campaigns.put(campaign.getId(), campaign);
            return campaign;
        }

        @Override
        public BannerCampaign updateEditable(BannerCampaign c) {
            BannerCampaign current = campaigns.get(c.getId());
            c.setServedImpressions(current.getServedImpressions());
            c.setClicks(current.getClicks());
            c.setReviewedAt(current.getReviewedAt());
            c.setCreatedBy(current.getCreatedBy());
            c.setProposedByCompanyId(current.getProposedByCompanyId());
            campaigns.put(c.getId(), c);
            return c;
        }

        @Override
        public void setStatus(String id, BannerStatus status, String reviewer, String reason) {
            BannerCampaign c = campaigns.get(id);
            c.setStatus(status);
            if (reviewer != null) {
                c.setReviewedBy(reviewer);
                c.setReviewedAt(NOW);
            }
            c.setRejectionReason(status == BannerStatus.REJECTED ? reason : null);
        }

        @Override
        public boolean endIfActive(String id) {
            BannerCampaign c = campaigns.get(id);
            if (c != null && c.getStatus() == BannerStatus.ACTIVE) {
                c.setStatus(BannerStatus.ENDED);
                return true;
            }
            return false;
        }

        @Override
        public void incServed(String id) {
            served++;
            BannerCampaign c = campaigns.get(id);
            if (c != null) {
                c.setServedImpressions(c.getServedImpressions() + 1);
            }
        }

        @Override
        public void incClicks(String id) {
            clicks++;
        }

        @Override
        public void incServed(String campaignId, String creativeId, String slot, long n) {
            served += (int) n;
            BannerCampaign c = campaigns.get(campaignId);
            if (c != null) {
                c.setServedImpressions(c.getServedImpressions() + n);
                c.getSlotServed().merge(slot, n, Long::sum);
            }
            BannerCreative cr = creativeId == null ? null : creatives.get(creativeId);
            if (cr != null) {
                cr.setServed(cr.getServed() + n);
                cr.getSlotServed().merge(slot, n, Long::sum);
            }
        }

        @Override
        public void incClicks(String campaignId, String creativeId, String slot) {
            clicks++;
            BannerCampaign c = campaigns.get(campaignId);
            if (c != null) {
                c.setClicks(c.getClicks() + 1);
                c.getSlotClicks().merge(slot, 1L, Long::sum);
            }
            BannerCreative cr = creativeId == null ? null : creatives.get(creativeId);
            if (cr != null) {
                cr.setClicks(cr.getClicks() + 1);
                cr.getSlotClicks().merge(slot, 1L, Long::sum);
            }
        }

        @Override
        public List<BannerCampaign> all() {
            return new ArrayList<>(campaigns.values());
        }

        @Override
        public void deleteCampaign(String id) {
            campaigns.remove(id);
            creatives.values().removeIf(cr -> id.equals(cr.getCampaignId()));
        }

        @Override
        public BannerCreative creative(String id) {
            return id == null ? null : creatives.get(id);
        }

        @Override
        public List<BannerCreative> creatives(String campaignId) {
            return creatives.values().stream().filter(cr -> Objects.equals(cr.getCampaignId(), campaignId)).toList();
        }

        @Override
        public List<BannerCreative> creatives(java.util.Collection<String> campaignIds) {
            return creatives.values().stream().filter(cr -> campaignIds.contains(cr.getCampaignId())).toList();
        }

        @Override
        public BannerCreative saveCreative(BannerCreative creative) {
            if (creative.getId() == null) {
                creative.setId("cr" + (++seq));
            }
            creatives.put(creative.getId(), creative);
            return creative;
        }

        @Override
        public void deleteCreative(String id) {
            creatives.remove(id);
        }
    }
}
