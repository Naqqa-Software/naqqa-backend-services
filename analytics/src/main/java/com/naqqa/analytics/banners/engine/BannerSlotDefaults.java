package com.naqqa.analytics.banners.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BannerSlotDefaults {

    public enum ParamType {
        INT,
        BOOL
    }

    public record ParamSpec(String key, ParamType type, Object value, int min, int max) {

        public static ParamSpec integer(String key, int value, int min, int max) {
            return new ParamSpec(key, ParamType.INT, value, min, max);
        }

        public static ParamSpec bool(String key, boolean value) {
            return new ParamSpec(key, ParamType.BOOL, value, 0, 1);
        }
    }

    public record Defaults(Map<String, String> name, Map<String, String> description, List<String> pageTypeOptions,
                           List<ParamSpec> params) {
    }

    private static final Set<String> DISABLED_BY_DEFAULT = Set.of(BannerSlots.APP_PROMO_STRIP, BannerSlots.CHAT_CARD);

    private static final List<String> LISTINGS = List.of("promotions", "products", "offers", "booklets", "blogs", "recipes");

    private static final Map<String, Defaults> BY_ID = new LinkedHashMap<>();

    static {
        put(BannerSlots.HOME_HERO,
                names("Pagina principală - hero", "Главная - hero", "Home - hero"),
                names("Banner mare sub sliderul de pe pagina principală.", "Большой баннер под слайдером на главной.",
                        "Large banner under the home page slider."),
                List.of(), List.of());
        put(BannerSlots.HOME_SIDE,
                names("Pagina principală - lângă slider", "Главная - рядом со слайдером", "Home - next to slider"),
                names("Banner vertical lângă sliderul de promoții; dacă lipsește se afișează bannerul clasic „slider-banner”.",
                        "Вертикальный баннер рядом со слайдером акций; если пусто - показывается классический баннер «slider-banner».",
                        "Vertical banner next to the promotions slider; falls back to the classic \"slider-banner\" key."),
                List.of(), List.of());
        put(BannerSlots.HOME_BETWEEN_1,
                names("Pagina principală - între secțiuni 1", "Главная - между секциями 1", "Home - between sections 1"),
                names("După secțiunea de cataloage.", "После секции каталогов.", "After the catalogues section."),
                List.of(), List.of());
        put(BannerSlots.HOME_BETWEEN_2,
                names("Pagina principală - între secțiuni 2", "Главная - между секциями 2", "Home - between sections 2"),
                names("Între oferte și produse; dacă lipsește se afișează bannerul clasic „main-page-2”.",
                        "Между предложениями и товарами; если пусто - классический баннер «main-page-2».",
                        "Between offers and products; falls back to the classic \"main-page-2\" key."),
                List.of(), List.of());
        put(BannerSlots.HOME_BETWEEN_3,
                names("Pagina principală - între secțiuni 3", "Главная - между секциями 3", "Home - between sections 3"),
                names("Înainte de secțiunea „Toate produsele”.", "Перед секцией «Все товары».", "Before the \"All products\" section."),
                List.of(), List.of());
        put(BannerSlots.LISTING_TOP,
                names("Liste - sus", "Списки - сверху", "Listings - top"),
                names("Deasupra grilei pe paginile de listă (promoții, produse, oferte, cataloage, bloguri, rețete).",
                        "Над сеткой на страницах списков (акции, товары, предложения, каталоги, блоги, рецепты).",
                        "Above the grid on listing pages."),
                LISTINGS, List.of());
        put(BannerSlots.LISTING_INFEED,
                names("Liste - în grilă", "Списки - в сетке", "Listings - in feed"),
                names("În grila de promoții/produse, după un număr de rânduri; dacă lipsește se afișează bannerul clasic.",
                        "В сетке акций/товаров после заданного числа рядов; если пусто - классический баннер.",
                        "Inside the promotions/products grid after N rows; falls back to the classic key."),
                List.of("promotions", "products"), List.of(ParamSpec.integer("afterRows", 2, 1, 20)));
        put(BannerSlots.LISTING_SIDEBAR,
                names("Liste - bara laterală", "Списки - боковая панель", "Listings - sidebar"),
                names("Sub filtrele din bara laterală (doar desktop).", "Под фильтрами в боковой панели (только десктоп).",
                        "Under the sidebar filters (desktop only)."),
                LISTINGS, List.of());
        put(BannerSlots.DETAIL_SIDE,
                names("Pagina promoției - lângă imagine", "Страница акции - рядом с фото", "Promotion page - beside media"),
                names("Sub galeria de imagini a promoției.", "Под галереей фото акции.", "Under the promotion media gallery."),
                List.of(), List.of());
        put(BannerSlots.DETAIL_BOTTOM,
                names("Pagini de detaliu - jos", "Страницы деталей - снизу", "Detail pages - bottom"),
                names("La finalul paginilor de promoție, ofertă și catalog.", "Внизу страниц акции, предложения и каталога.",
                        "At the bottom of promotion, offer and catalogue pages."),
                List.of("promotion", "offer", "booklet"), List.of());
        put(BannerSlots.COMPANY_TOP,
                names("Pagina companiei - sus", "Страница компании - сверху", "Company page - top"),
                names("Rezervat companiei proprii; afișat pe pagina „Despre” a companiei.",
                        "Зарезервирован для самой компании; показывается на странице «О компании».",
                        "Reserved for the company itself; shown on the company about page."),
                List.of(), List.of());
        put(BannerSlots.BOOKLET_INTERSTITIAL,
                names("Catalog - pagină publicitară", "Каталог - рекламная страница", "Catalogue - interstitial page"),
                names("Pagină publicitară inserată între paginile catalogului.", "Рекламная страница между страницами каталога.",
                        "Ad page inserted between catalogue pages."),
                List.of(), List.of(ParamSpec.integer("afterPage", 4, 1, 100), ParamSpec.integer("every", 8, 2, 100)));
        put(BannerSlots.BLOG_INARTICLE,
                names("Articole - în text", "Статьи - в тексте", "Articles - in article"),
                names("În textul articolelor de blog, al rețetelor și al ofertelor.", "В тексте статей блога, рецептов и предложений.",
                        "Inside blog articles, recipes and offers."),
                List.of("blog", "recipe", "offer"), List.of());
        put(BannerSlots.SEARCH_TOP,
                names("Rezultate căutare - sus", "Результаты поиска - сверху", "Search results - top"),
                names("Deasupra rezultatelor căutării; poate fi țintit pe cuvinte cheie.",
                        "Над результатами поиска; можно настроить по ключевым словам.", "Above search results; keyword targeting available."),
                List.of(), List.of());
        put(BannerSlots.CHAT_CARD,
                names("Asistent chat - card sponsorizat", "Чат-ассистент - спонсорская карточка", "Chat assistant - sponsored card"),
                names("Card afișat în fereastra asistentului de chat.", "Карточка в окне чат-ассистента.", "Card shown inside the chat assistant window."),
                List.of(), List.of());
        put(BannerSlots.APP_PROMO_STRIP,
                names("Bandă promo sub meniu", "Промо-полоса под меню", "Promo strip under header"),
                names("Bandă îngustă sub meniul principal, pe tot site-ul; vizitatorul o poate închide.",
                        "Узкая полоса под главным меню на всём сайте; посетитель может её закрыть.",
                        "Thin strip under the header on every page; visitors can dismiss it."),
                List.of(), List.of(ParamSpec.integer("dismissDays", 7, 0, 365), ParamSpec.bool("showInApp", false)));
    }

    private BannerSlotDefaults() {
    }

    public static Defaults get(String id) {
        Defaults d = id == null ? null : BY_ID.get(id);
        return d != null ? d : new Defaults(Map.of(), Map.of(), List.of(), List.of());
    }

    public static boolean enabledByDefault(String id) {
        return id == null || !DISABLED_BY_DEFAULT.contains(id);
    }

    public static ParamSpec param(String slot, String key) {
        for (ParamSpec spec : get(slot).params()) {
            if (spec.key().equals(key)) {
                return spec;
            }
        }
        return null;
    }

    public static Map<String, Object> params(String slot) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (ParamSpec spec : get(slot).params()) {
            out.put(spec.key(), spec.value());
        }
        return out;
    }

    private static Map<String, String> names(String ro, String ru, String en) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("ro", ro);
        out.put("ru", ru);
        out.put("en", en);
        return out;
    }

    private static void put(String id, Map<String, String> name, Map<String, String> description, List<String> pageTypes,
                            List<ParamSpec> params) {
        BY_ID.put(id, new Defaults(name, description, pageTypes, params));
    }
}
