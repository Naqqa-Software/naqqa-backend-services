package com.naqqa.analytics.banners;

import com.mongodb.client.MongoCollection;
import com.naqqa.analytics.banners.model.BannerCampaign;
import com.naqqa.analytics.banners.model.BannerCreative;
import com.naqqa.analytics.banners.store.BannerRepository;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mapping.model.FieldNamingStrategy;
import org.springframework.data.mapping.model.SnakeCaseFieldNamingStrategy;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BannerRepositoryCountersTest {

    @SuppressWarnings("unchecked")
    private final MongoCollection<Document> campaigns = mock(MongoCollection.class);
    @SuppressWarnings("unchecked")
    private final MongoCollection<Document> creatives = mock(MongoCollection.class);
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final BannerRepository repository = new BannerRepository(mongo);

    static MappingMongoConverter converter(FieldNamingStrategy naming) {
        MongoCustomConversions conversions = new MongoCustomConversions(List.of());
        MongoMappingContext context = new MongoMappingContext();
        context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        if (naming != null) {
            context.setFieldNamingStrategy(naming);
        }
        context.afterPropertiesSet();
        MappingMongoConverter c = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
        c.setCustomConversions(conversions);
        c.afterPropertiesSet();
        return c;
    }

    BannerRepositoryCountersTest() {
        when(mongo.getConverter()).thenReturn(converter(null));
        when(mongo.getCollectionName(BannerCampaign.class)).thenReturn("an_banner_campaign");
        when(mongo.getCollectionName(BannerCreative.class)).thenReturn("an_banner_creative");
        when(mongo.getCollection("an_banner_campaign")).thenReturn(campaigns);
        when(mongo.getCollection("an_banner_creative")).thenReturn(creatives);
    }

    @Test
    void servedIncrementsTotalsAndSlotKeysWithRawUnderscoreFieldNames() {
        String campaignId = new ObjectId().toHexString();
        repository.incServed(campaignId, "cr-1", "home_between_1", 7);
        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(campaigns).updateOne(filter.capture(), update.capture());
        assertThat(((Document) filter.getValue()).get("_id")).isEqualTo(new ObjectId(campaignId));
        Document inc = (Document) ((Document) update.getValue()).get("$inc");
        assertThat(inc).containsEntry("servedImpressions", 7L).containsEntry("slotServed.home_between_1", 7L);
        verify(creatives).updateOne(filter.capture(), update.capture());
        assertThat(((Document) filter.getValue()).get("_id")).isEqualTo("cr-1");
        assertThat((Document) ((Document) update.getValue()).get("$inc")).containsEntry("served", 7L).containsEntry("slotServed.home_between_1", 7L);
    }

    @Test
    void clicksIncrementPerSlotAndIgnoreUnsafeSlotNames() {
        repository.incClicks("c1", null, "home.side");
        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(campaigns).updateOne(any(Bson.class), update.capture());
        Document inc = (Document) ((Document) update.getValue()).get("$inc");
        assertThat(inc.keySet()).isEqualTo(Set.of("clicks"));
        verify(creatives, never()).updateOne(any(Bson.class), any(Bson.class));
        repository.incClicks("c1", "cr", "detail_bottom");
        verify(campaigns, times(2)).updateOne(any(Bson.class), update.capture());
        assertThat(List.copyOf(((Document) ((Document) update.getValue()).get("$inc")).keySet())).containsExactly("clicks", "slotClicks.detail_bottom");
    }

    @Test
    void fieldNamesFollowTheConfiguredNamingStrategy() {
        when(mongo.getConverter()).thenReturn(converter(new SnakeCaseFieldNamingStrategy()));
        repository.incServed("c1", "cr", "search_top", 2);
        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(campaigns).updateOne(any(Bson.class), update.capture());
        assertThat((Document) ((Document) update.getValue()).get("$inc")).containsEntry("served_impressions", 2L)
                .containsEntry("slot_served.search_top", 2L);
        verify(creatives).updateOne(any(Bson.class), update.capture());
        assertThat((Document) ((Document) update.getValue()).get("$inc")).containsEntry("served", 2L)
                .containsEntry("slot_served.search_top", 2L);
    }

    @Test
    void zeroIncrementsAreSkipped() {
        repository.incServed("c1", "cr", "home_side", 0);
        verify(campaigns, never()).updateOne(any(Bson.class), any(Bson.class));
    }
}
