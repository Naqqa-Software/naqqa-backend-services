package com.naqqa.analytics;

import com.naqqa.analytics.registry.EventRegistry;
import com.naqqa.analytics.registry.PiiScrubber;
import com.naqqa.analytics.registry.RegistryValidator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.naqqa.analytics.AnalyticsTestSupport.props;
import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsRegistryTest {

    private final RegistryValidator validator = AnalyticsTestSupport.validator();

    @Test
    void registryContainsEveryContractEvent() {
        EventRegistry r = AnalyticsTestSupport.REGISTRY;
        List<String> contract = List.of("page_view", "page_leave", "scroll_depth", "click", "rage_click", "dead_click", "outbound_click",
                "lang_switch", "menu_open", "category_menu_click", "web_vitals", "js_error", "item_impression", "item_click", "item_view",
                "tab_view", "gallery_interaction", "countdown_seen", "add_to_list", "remove_from_list", "share_click", "share_landing",
                "promocode_click", "promocode_checkout_start", "promocode_purchase", "promocode_fail", "store_contact_click",
                "store_link_click", "review_submit", "review_vote", "question_submit", "faq_expand", "booklet_open", "booklet_page_view",
                "booklet_zoom", "booklet_complete", "booklet_hotspot_click", "article_read", "article_cta_click", "company_view",
                "company_section_click", "search", "search_result_click", "search_refine", "filter_apply", "pagination", "sort_change",
                "signup_start", "signup_complete", "login", "logout", "partner_login", "app_badge_click", "add_promotion_cta_click",
                "partner_page_view", "banner_impression", "banner_viewable", "banner_click", "chat_widget_impression", "chat_open",
                "chat_close", "chat_minimize", "chat_conversation_start", "chat_message_sent", "chat_bot_reply", "chat_quick_reply_click",
                "chat_voice_record", "chat_voice_transcribed", "chat_tts_play", "chat_recommendation_impression",
                "chat_recommendation_click", "chat_handoff_requested", "chat_ai_paused", "chat_operator_joined", "chat_operator_message",
                "chat_returned_to_ai", "chat_closed", "chat_rating", "chat_offtopic_refused", "chat_injection_blocked",
                "chat_recaptcha_fail", "chat_rate_limited");
        assertThat(r.events().keySet()).containsAll(contract);
        assertThat(r.entityTypes()).containsExactly("PROMOTION", "PRODUCT", "OFFER", "BOOKLET", "BLOG", "RECIPE", "RAFFLE", "COMPANY",
                "CATEGORY", "BANNER");
        assertThat(r.limit("maxEvents", 0)).isEqualTo(50);
        assertThat(r.limit("maxBodyBytes", 0)).isEqualTo(65536);
    }

    @Test
    void unknownEventIsRejected() {
        RegistryValidator.Result r = validator.validate("made_up", Map.of(), false);
        assertThat(r.ok()).isFalse();
        assertThat(r.reason()).isEqualTo(RegistryValidator.UNKNOWN_EVENT);
    }

    @Test
    void serverOnlyEventsAreRejectedFromBrowser() {
        assertThat(validator.validate("chat_bot_reply", Map.of(), false).reason()).isEqualTo(RegistryValidator.SERVER_ONLY);
        assertThat(validator.validate("chat_bot_reply", props("latencyMs", 120, "intent", "promo"), true).ok()).isTrue();
    }

    @Test
    void unknownPropsAreDroppedAndCommonPropsSplit() {
        RegistryValidator.Result r = validator.validate("item_click", props("entityType", "promotion", "entityId", "123", "companyId", 8,
                "sponsored", "true", "position", "3", "hacker", "x", "sourceBlock", "home_promotions_carousel"), false);
        assertThat(r.ok()).isTrue();
        assertThat(r.common()).containsEntry("entityType", "PROMOTION").containsEntry("entityId", "123").containsEntry("companyId", "8")
                .containsEntry("sponsored", true).containsEntry("position", 3).containsEntry("sourceBlock", "home_promotions_carousel");
        assertThat(r.props()).isEmpty();
        assertThat(r.droppedProps()).isEqualTo(1);
    }

    @Test
    void invalidValuesAreDropped() {
        RegistryValidator.Result r = validator.validate("scroll_depth", props("pct", 150), false);
        assertThat(r.props()).doesNotContainKey("pct");
        RegistryValidator.Result share = validator.validate("share_click", props("channel", "WhatsApp"), false);
        assertThat(share.props()).containsEntry("channel", "whatsapp");
        RegistryValidator.Result bad = validator.validate("share_click", props("channel", "myspace"), false);
        assertThat(bad.props()).isEmpty();
        RegistryValidator.Result orphan = validator.validate("item_view", props("entityType", "PROMOTION"), false);
        assertThat(orphan.common()).doesNotContainKey("entityType");
    }

    @Test
    void stringsAreTruncatedAndScrubbed() {
        String longTarget = "x".repeat(300);
        RegistryValidator.Result r = validator.validate("click", props("target", longTarget), false);
        assertThat((String) r.props().get("target")).hasSize(100);
        RegistryValidator.Result s = validator.validate("search", props("q", "  Lapte  ion@mail.md +373 69 123 456 ", "results", 0, "zero", true), false);
        assertThat(s.props().get("q")).isEqualTo("lapte [redacted] [redacted]");
        RegistryValidator.Result u = validator.validate("outbound_click", props("url", "https://Shop.md/p?id=1&email=a@b.c#x"), false);
        assertThat(u.props().get("url")).isEqualTo("https://shop.md/p");
        RegistryValidator.Result js = validator.validate("outbound_click", props("url", "javascript:alert(1)"), false);
        assertThat(js.props()).isEmpty();
    }

    @Test
    void piiScrubberPaths() {
        assertThat(PiiScrubber.sanitizePath("https://omy.md/ro/x?y=1", 300)).isEqualTo("/ro/x");
        assertThat(PiiScrubber.sanitizePath("ro/promotions/a#top", 300)).isEqualTo("/ro/promotions/a");
        assertThat(PiiScrubber.host("https://www.Google.com/search")).isEqualTo("google.com");
    }
}
