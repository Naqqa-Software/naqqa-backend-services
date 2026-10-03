package com.naqqa.chatbot.ai;

import com.naqqa.chatbot.ai.retrieval.Candidate;
import com.naqqa.chatbot.ai.retrieval.CategoryRef;
import com.naqqa.chatbot.ai.retrieval.CompanyRef;
import com.naqqa.chatbot.ai.retrieval.PlaceRef;
import com.naqqa.chatbot.spi.ChatEntityResolver;

import java.time.LocalDate;
import java.util.List;

final class ChatAiFixtures {

    static final String PROMOTION_CATEGORY = "promotion_category";
    static final String PRODUCT_CATEGORY = "product_category";
    static final String BOOKLET_CATEGORY = "booklet_category";
    static final String BLOG_CATEGORY = "blog_category";

    static final List<CompanyRef> COMPANIES = List.of(
            new CompanyRef(1L, "Maximum", "maximum", "max.png"),
            new CompanyRef(2L, "Nr1", "nr1", "nr1.png"),
            new CompanyRef(3L, "Kaufland Moldova SRL", "kaufland", "kaufland.png"),
            new CompanyRef(4L, "Bomba", "bomba", "bomba.png"),
            new CompanyRef(5L, "Linella", "linella", "linella.png"),
            new CompanyRef(6L, "Fidesco", "fidesco", "fidesco.png"),
            new CompanyRef(7L, "Darwin", "darwin", null),
            new CompanyRef(8L, "Enter", "enter", null),
            new CompanyRef(9L, "Green Hills Market", "green-hills", null));

    static final List<CategoryRef> CATEGORIES = List.of(
            CategoryRef.of(PROMOTION_CATEGORY, 11L, "Electronice", "Электроника"),
            CategoryRef.of(PROMOTION_CATEGORY, 12L, "Alte", "Другое"),
            CategoryRef.of(PRODUCT_CATEGORY, 21L, "Lactate", "Молочные продукты"),
            CategoryRef.of(PRODUCT_CATEGORY, 22L, "Băuturi", "Напитки"),
            CategoryRef.of(BOOKLET_CATEGORY, 31L, "Supermarketuri", "Супермаркеты"),
            CategoryRef.of(BLOG_CATEGORY, 41L, "Sfaturi", "Советы"));

    static final List<PlaceRef> PLACES = List.of(
            PlaceRef.of(PlaceRef.REGION, 101L, "mun. Bălți", "мун. Бельцы"),
            PlaceRef.of(PlaceRef.REGION, 102L, "mun. Chișinău", "мун. Кишинёв"),
            PlaceRef.of(PlaceRef.REGION, 103L, "Cahul", "Кагул"),
            PlaceRef.of(PlaceRef.SETTLEMENT, 201L, "Orhei", "Орхей"));

    static final ChatEntityResolver DIRECTORY = new ChatEntityResolver() {
        @Override
        public List<CompanyRef> companies() {
            return COMPANIES;
        }

        @Override
        public List<CategoryRef> categories() {
            return CATEGORIES;
        }

        @Override
        public List<PlaceRef> places() {
            return PLACES;
        }
    };

    private ChatAiFixtures() {
    }

    static Candidate candidate(String type, long id, String title, double score, Double discount, boolean sponsored,
                               LocalDate validTo) {
        return new Candidate(type, id, "slug-" + id, Candidate.titles(title, title), "img-" + id, 10.0, 15.0, discount, 1L,
                validTo, null, score, sponsored ? Boolean.TRUE : null, sponsored ? 1.0 : null, null, null,
                path(type, id));
    }

    static String path(String type, long id) {
        return switch (type) {
            case "PROMOTION" -> "/promotions/slug-" + id;
            case "OFFER" -> "/offers/slug-" + id;
            case "BOOKLET" -> "/booklets/slug-" + id;
            case "PRODUCT" -> "/booklets/b-" + id + "/products";
            case "BLOG", "RECIPE" -> "/blogs/slug-" + id;
            case "RAFFLE" -> "/raffles/" + id;
            case "COMPANY" -> "/company/slug-" + id;
            default -> "/promotions/" + id;
        };
    }
}
