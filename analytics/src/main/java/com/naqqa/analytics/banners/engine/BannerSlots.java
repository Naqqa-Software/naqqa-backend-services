package com.naqqa.analytics.banners.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BannerSlots {

    public record Size(int w, int h) {
        public double ratio() {
            return h == 0 ? 0 : (double) w / h;
        }
    }

    public record Slot(String id, String page, Size desktop, Size mobile, boolean reservedForCompany, boolean mounted) {
        public boolean hiddenOnMobile() {
            return mobile == null;
        }
    }

    public static final String HOME_HERO = "home_hero";
    public static final String HOME_SIDE = "home_side";
    public static final String HOME_BETWEEN_1 = "home_between_1";
    public static final String HOME_BETWEEN_2 = "home_between_2";
    public static final String HOME_BETWEEN_3 = "home_between_3";
    public static final String LISTING_TOP = "listing_top";
    public static final String LISTING_INFEED = "listing_infeed";
    public static final String LISTING_SIDEBAR = "listing_sidebar";
    public static final String DETAIL_SIDE = "detail_side";
    public static final String DETAIL_BOTTOM = "detail_bottom";
    public static final String COMPANY_TOP = "company_top";
    public static final String BOOKLET_INTERSTITIAL = "booklet_interstitial";
    public static final String BLOG_INARTICLE = "blog_inarticle";
    public static final String SEARCH_TOP = "search_top";
    public static final String CHAT_CARD = "chat_card";
    public static final String APP_PROMO_STRIP = "app_promo_strip";

    public static final List<Slot> ALL = List.of(
            new Slot(HOME_HERO, "home", new Size(1200, 400), new Size(390, 300), false, true),
            new Slot(HOME_SIDE, "home", new Size(300, 400), new Size(390, 300), false, true),
            new Slot(HOME_BETWEEN_1, "home", new Size(1200, 150), new Size(390, 120), false, true),
            new Slot(HOME_BETWEEN_2, "home", new Size(1200, 150), new Size(390, 120), false, true),
            new Slot(HOME_BETWEEN_3, "home", new Size(1200, 150), new Size(390, 120), false, true),
            new Slot(LISTING_TOP, "listing", new Size(1200, 120), new Size(390, 100), false, true),
            new Slot(LISTING_INFEED, "listing", new Size(1200, 150), new Size(390, 150), false, true),
            new Slot(LISTING_SIDEBAR, "listing", new Size(300, 600), null, false, true),
            new Slot(DETAIL_SIDE, "detail", new Size(300, 250), new Size(390, 325), false, false),
            new Slot(DETAIL_BOTTOM, "detail", new Size(1200, 150), new Size(390, 120), false, true),
            new Slot(COMPANY_TOP, "company", new Size(1200, 200), new Size(390, 150), true, true),
            new Slot(BOOKLET_INTERSTITIAL, "booklet", new Size(600, 850), new Size(390, 552), false, true),
            new Slot(BLOG_INARTICLE, "blog", new Size(728, 90), new Size(390, 200), false, true),
            new Slot(SEARCH_TOP, "search", new Size(1200, 120), new Size(390, 100), false, true),
            new Slot(CHAT_CARD, "chat", new Size(320, 180), new Size(320, 180), false, true),
            new Slot(APP_PROMO_STRIP, "global", new Size(1200, 48), new Size(390, 48), false, true)
    );

    private static final Map<String, Slot> BY_ID = new LinkedHashMap<>();

    static {
        for (Slot slot : ALL) {
            BY_ID.put(slot.id(), slot);
        }
    }

    private BannerSlots() {
    }

    public static Slot get(String id) {
        return id == null ? null : BY_ID.get(id.trim().toLowerCase());
    }

    public static boolean exists(String id) {
        return get(id) != null;
    }

    public static boolean reservedForCompany(String id) {
        Slot slot = get(id);
        return slot != null && slot.reservedForCompany();
    }
}
