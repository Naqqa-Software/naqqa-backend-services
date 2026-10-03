package com.naqqa.analytics;

import com.naqqa.analytics.collect.ChannelClassifier;
import com.naqqa.analytics.collect.ChannelClassifier.Attribution;
import com.naqqa.analytics.collect.ChannelClassifier.Input;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsChannelTest {

    private final ChannelClassifier c = new ChannelClassifier(List.of("omy.md"));

    private Attribution ref(String referrer) {
        return c.classify(new Input(referrer, null, null, null, null, null, null));
    }

    @Test
    void referrerClassification() {
        assertThat(ref("https://www.google.com/")).isEqualTo(new Attribution("Organic", "google", "organic", null));
        assertThat(ref("https://www.google.md/search?q=x").channel()).isEqualTo("Organic");
        assertThat(ref("https://yandex.ru/search/").source()).isEqualTo("yandex");
        assertThat(ref("https://l.facebook.com/l.php").source()).isEqualTo("facebook");
        assertThat(ref("https://t.me/omy").channel()).isEqualTo("Social");
        assertThat(ref("https://mail.google.com/mail/u/0").channel()).isEqualTo("Email");
        assertThat(ref("https://point.md/ro/news")).isEqualTo(new Attribution("Referral", "point.md", "referral", null));
        assertThat(ref("https://omy.md/ro/promotions").channel()).isEqualTo("Direct");
        assertThat(ref("https://www.omy.md/ro").channel()).isEqualTo("Direct");
        assertThat(ref(null).channel()).isEqualTo("Direct");
    }

    @Test
    void clickIdsAndUtm() {
        assertThat(c.classify(new Input(null, null, null, null, "gclid", null, null))).isEqualTo(new Attribution("Paid", "google", "cpc", null));
        assertThat(c.classify(new Input("https://facebook.com", null, null, null, "fbclid", null, null)).channel()).isEqualTo("Social");
        assertThat(c.classify(new Input(null, "facebook", "paid_social", "autumn", "fbclid", null, null)).channel()).isEqualTo("Paid");
        assertThat(c.classify(new Input("https://google.com", "google", "cpc", "brand", null, null, null)))
                .isEqualTo(new Attribution("Paid", "google", "cpc", "brand"));
        assertThat(c.classify(new Input(null, "newsletter", "email", "oct", null, null, null)).channel()).isEqualTo("Email");
        assertThat(c.classify(new Input(null, "instagram", null, null, null, null, null)).channel()).isEqualTo("Social");
        assertThat(c.classify(new Input(null, "partner-site", "web", null, null, null, null)).channel()).isEqualTo("Referral");
    }

    @Test
    void refParameterChannels() {
        assertThat(c.classify(new Input(null, null, null, null, null, "qr", null)).channel()).isEqualTo("QR");
        assertThat(c.classify(new Input(null, null, null, null, null, "qr_store12", null)).channel()).isEqualTo("QR");
        assertThat(c.classify(new Input(null, null, null, null, null, "print", null)).channel()).isEqualTo("QR");
        assertThat(c.classify(new Input("https://web.whatsapp.com", null, null, null, null, "share_ab12", null)).channel()).isEqualTo("Share");
        assertThat(c.classify(new Input(null, null, null, null, null, "banner", null)).channel()).isEqualTo("Banner");
        assertThat(c.classify(new Input(null, null, null, null, null, "chat", null)).channel()).isEqualTo("Chat");
        assertThat(c.classify(new Input(null, null, null, null, null, "push", null)).channel()).isEqualTo("App");
        assertThat(c.classify(new Input(null, null, null, null, null, null, "android-2.1")).channel()).isEqualTo("App");
    }

    @Test
    void attributionKeysDriveSessionRotation() {
        Attribution direct = ref(null);
        Attribution google = ref("https://google.com");
        assertThat(direct.triggersNewSession()).isFalse();
        assertThat(google.triggersNewSession()).isTrue();
        assertThat(google.key()).isEqualTo("Organic|google|organic|");
        assertThat(c.externalHost("https://omy.md/x")).isNull();
        assertThat(c.externalHost("https://www.point.md/x")).isEqualTo("point.md");
    }
}
