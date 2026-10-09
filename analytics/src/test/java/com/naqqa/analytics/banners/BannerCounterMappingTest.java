package com.naqqa.analytics.banners;

import com.naqqa.analytics.banners.model.BannerCampaign;
import com.naqqa.analytics.banners.model.BannerCreative;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.data.mapping.model.SnakeCaseFieldNamingStrategy;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;

import static org.assertj.core.api.Assertions.assertThat;

class BannerCounterMappingTest {

    private final MappingMongoConverter converter = BannerRepositoryCountersTest.converter(new SnakeCaseFieldNamingStrategy());

    @Test
    void perSlotCountersAreReadBackFromNestedDocuments() {
        Document doc = new Document("_id", new ObjectId()).append("name", "x").append("served_impressions", 12L)
                .append("slot_served", new Document("home_between_1", 7L).append("search_top", 5))
                .append("slot_clicks", new Document("home_between_1", 1));
        BannerCampaign c = converter.read(BannerCampaign.class, doc);
        assertThat(c.getSlotServed()).containsEntry("home_between_1", 7L).containsEntry("search_top", 5L);
        assertThat(c.getSlotClicks()).containsEntry("home_between_1", 1L);
        BannerCreative cr = converter.read(BannerCreative.class, new Document("_id", "cr").append("served", 3L)
                .append("slot_served", new Document("detail_bottom", 3L)));
        assertThat(cr.getSlotServed()).containsEntry("detail_bottom", 3L);
        assertThat(cr.getServed()).isEqualTo(3);
        assertThat(c.getServedImpressions()).isEqualTo(12);
    }
}
