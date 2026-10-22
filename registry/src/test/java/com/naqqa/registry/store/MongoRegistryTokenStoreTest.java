package com.naqqa.registry.store;

import com.naqqa.registry.domain.RegistryToken;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MongoRegistryTokenStoreTest {

    private static final RegistryToken TOKEN = new RegistryToken("abcdefghijklmnop", "hash", "acme", "Acme SRL", "CI",
            List.of("components"), List.of("@naqqa/chat"), Instant.parse("2026-10-19T10:00:00.123Z"), "admin",
            Instant.parse("2027-10-19T10:00:00Z"), null, null, null, null);

    @Test
    void documentRoundTripKeepsEveryField() {
        Document document = MongoRegistryTokenStore.toDocument(TOKEN);
        assertThat(document.get("_id")).isEqualTo("abcdefghijklmnop");
        assertThat(MongoRegistryTokenStore.fromDocument(document)).isEqualTo(TOKEN);
        RegistryToken revoked = TOKEN.revoke(Instant.parse("2026-11-01T00:00:00Z"), "root", "unpaid")
                .withLastUsedAt(Instant.parse("2026-10-20T00:00:00Z"));
        assertThat(MongoRegistryTokenStore.fromDocument(MongoRegistryTokenStore.toDocument(revoked))).isEqualTo(revoked);
    }

    @Test
    void usesThePrefixedCollection() {
        assertThat(new MongoRegistryTokenStore(null, "naqqa_registry_").collection()).isEqualTo("naqqa_registry_tokens");
        assertThat(new MongoRegistryTokenStore(null, null).collection()).isEqualTo("tokens");
        assertThat(new MongoRegistryTokenStore(null, "x_").findById(null)).isEmpty();
    }
}
