package com.naqqa.chatbot.ai;

import com.naqqa.chatbot.ai.retrieval.Candidate;
import com.naqqa.chatbot.ai.retrieval.ChatItemType;
import com.naqqa.chatbot.ai.retrieval.CompanyRef;
import com.naqqa.chatbot.ai.retrieval.RetrievalPlan;
import com.naqqa.chatbot.spi.ChatContentProvider;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class CatalogContentProvider implements ChatContentProvider {

    record Item(String type, long id, String ro, String ru, String en, Double price, Double discount, Long companyId,
                Long categoryId, LocalDate validTo, long freshness) {
    }

    static final Map<String, String> SYNONYMS = Map.of(
            "branza", "cascaval", "lactate", "lapte", "dulciuri", "ciocolata", "racoritoare", "suc",
            "сладости", "шоколад", "газировка", "сок", "gaseste", "cafea", "cola", "suc");

    final List<Item> items = new ArrayList<>();
    final List<RetrievalPlan> plans = new ArrayList<>();

    CatalogContentProvider() {
        LocalDate today = LocalDate.now();
        Object[][] rows = {
                {"PROMOTION", "Ouă de găină 10 buc", "Яйца куриные 10 шт", "Chicken eggs 10 pcs", 32.9, 20.0, 5L, null},
                {"PROMOTION", "Pâine albă feliată", "Хлеб белый нарезной", "White sliced bread", 9.5, 10.0, 5L, null},
                {"PRODUCT", "Lapte 2.5% 1L", "Молоко 2.5% 1л", "Milk 2.5% 1L", 18.9, 15.0, 3L, 21L},
                {"PRODUCT", "Lapte bio 1.5% 1L", "Молоко био 1.5% 1л", "Organic milk 1L", 24.5, null, 2L, 21L},
                {"PROMOTION", "Unt 82% 200g", "Масло сливочное 82% 200г", "Butter 82% 200g", 39.9, 25.0, 1L, 21L},
                {"PROMOTION", "Cașcaval Olanda 500g", "Сыр Голландский 500г", "Dutch cheese 500g", 89.0, 30.0, 3L, 21L},
                {"PROMOTION", "Cafea Jacobs Monarch 250g", "Кофе Якобс Монарх 250г", "Jacobs Monarch coffee 250g", 79.0, 35.0, 1L, 22L},
                {"PROMOTION", "Cafea Lavazza Crema 1kg", "Кофе Лавацца Крема 1кг", "Lavazza Crema coffee 1kg", 289.0, 20.0, 5L, 22L},
                {"PROMOTION", "Ceai verde Greenfield", "Чай зеленый Гринфилд", "Greenfield green tea", 34.0, 15.0, 2L, 22L},
                {"PROMOTION", "Cereale Nesquik 375g", "Хлопья Несквик 375г", "Nesquik cereal 375g", 45.0, 20.0, 1L, null},
                {"PROMOTION", "Carne de pui file", "Куриное филе", "Chicken fillet", 69.0, 15.0, 3L, null},
                {"PROMOTION", "Carne de porc ceafă", "Свиная шея", "Pork neck", 99.0, 20.0, 1L, null},
                {"PROMOTION", "Pește somon file", "Филе лосося", "Salmon fillet fish", 189.0, 10.0, 2L, null},
                {"PROMOTION", "Legume mix congelate", "Овощная смесь замороженная", "Frozen mixed vegetables", 29.0, 25.0, 5L, null},
                {"PROMOTION", "Cartofi noi 2kg", "Картофель молодой 2кг", "New potatoes 2kg", 25.0, 10.0, 4L, null},
                {"PROMOTION", "Orez bob lung 1kg", "Рис длиннозерный 1кг", "Long grain rice 1kg", 27.0, 12.0, 3L, null},
                {"PROMOTION", "Paste Barilla spaghete 500g", "Макароны Барилла спагетти 500г", "Barilla spaghetti pasta 500g", 32.0, 25.0, 5L, null},
                {"PROMOTION", "Supă instant de pui", "Суп куриный быстрого приготовления", "Instant chicken soup", 12.0, 10.0, 6L, null},
                {"PROMOTION", "Salată verde", "Салат зеленый", "Green salad lettuce", 15.0, 5.0, 6L, null},
                {"PROMOTION", "Vin roșu Purcari", "Вино красное Пуркарь", "Purcari red wine", 149.0, 20.0, 1L, 22L},
                {"PROMOTION", "Bere Chișinău 0.5L", "Пиво Кишинэу 0.5л", "Chisinau beer 0.5L", 16.0, 15.0, 2L, 22L},
                {"PROMOTION", "Suc de portocale Natur Bravo 1L", "Сок апельсиновый Натур Браво 1л", "Orange juice 1L", 28.0, 20.0, 5L, 22L},
                {"PROMOTION", "Apă minerală Borjomi", "Минеральная вода Боржоми", "Borjomi mineral water", 19.0, 10.0, 3L, 22L},
                {"PROMOTION", "Chipsuri Lays 140g", "Чипсы Лейс 140г", "Lays chips 140g", 29.0, 30.0, 4L, null},
                {"PROMOTION", "Snacksuri sărate mix", "Снеки соленые микс", "Salty snacks mix", 22.0, 15.0, 4L, null},
                {"PROMOTION", "Pizza Margherita congelată", "Пицца Маргарита замороженная", "Frozen Margherita pizza", 59.0, 20.0, 3L, null},
                {"PROMOTION", "Tort de ciocolată", "Шоколадный торт", "Chocolate cake", 159.0, 15.0, 6L, null},
                {"PROMOTION", "Pahare de unică folosință 50 buc", "Стаканы одноразовые 50 шт", "Disposable cups 50 pcs", 25.0, 10.0, 4L, null},
                {"PROMOTION", "Mici pentru grătar 1kg", "Мититеи для гриля 1кг", "Grill sausages mici 1kg", 119.0, 20.0, 1L, null},
                {"PROMOTION", "Cărbune pentru grătar 3kg", "Уголь древесный 3кг", "Charcoal 3kg", 65.0, 15.0, 4L, null},
                {"PROMOTION", "Sos barbecue Heinz", "Соус барбекю Хайнц", "Heinz barbecue sauce", 35.0, 10.0, 3L, null},
                {"PROMOTION", "Ketchup Heinz 500g", "Кетчуп Хайнц 500г", "Heinz ketchup 500g", 32.0, 20.0, 5L, null},
                {"PROMOTION", "Muștar Develey", "Горчица Девелей", "Develey mustard", 18.0, 10.0, 2L, null},
                {"PROMOTION", "Sandviș cu șuncă", "Бутерброд с ветчиной", "Ham sandwich", 25.0, 5.0, 6L, null},
                {"PROMOTION", "Fructe de sezon mix", "Фрукты сезонные микс", "Seasonal fruit mix", 39.0, 10.0, 5L, null},
                {"PROMOTION", "Brânză de vaci 400g", "Творог 400г", "Cottage cheese 400g", 36.0, 15.0, 3L, 21L},
                {"PROMOTION", "Salam Milano", "Колбаса Милано", "Milano salami", 79.0, 20.0, 1L, null},
                {"PROMOTION", "Șervețele de masă 100 buc", "Салфетки столовые 100 шт", "Table napkins 100 pcs", 15.0, 10.0, 4L, null},
                {"PROMOTION", "Șampanie Cricova", "Шампанское Криково", "Cricova champagne", 129.0, 25.0, 1L, 22L},
                {"PROMOTION", "Bomboane Bucuria", "Конфеты Букурия", "Bucuria candy", 59.0, 20.0, 5L, null},
                {"PROMOTION", "Baloane colorate 20 buc", "Шары цветные 20 шт", "Colorful balloons 20 pcs", 30.0, 10.0, 4L, null},
                {"PROMOTION", "Lumânări pentru tort", "Свечи для торта", "Cake candles", 12.0, 10.0, 4L, null},
                {"PROMOTION", "Flori buchet trandafiri", "Букет роз цветы", "Rose bouquet flowers", 199.0, 15.0, 9L, null},
                {"PROMOTION", "Parfum Chanel 50ml", "Духи Шанель 50мл", "Chanel perfume 50ml", 1599.0, 20.0, 9L, null},
                {"PROMOTION", "Ciocolată Milka 100g", "Шоколад Милка 100г", "Milka chocolate 100g", 25.0, 30.0, 2L, null},
                {"PROMOTION", "Cosmetice set Nivea", "Косметика набор Нивея", "Nivea cosmetics set", 249.0, 25.0, 9L, null},
                {"PROMOTION", "Jucării Lego Classic", "Игрушки Лего Классик", "Lego Classic toys", 499.0, 20.0, 8L, null},
                {"PROMOTION", "Ceas Casio", "Часы Касио", "Casio watch", 899.0, 15.0, 7L, 11L},
                {"PROMOTION", "Set cadou Nivea Men", "Подарочный набор Нивея Мен", "Nivea Men gift set", 199.0, 30.0, 9L, null},
                {"PROMOTION", "Miel proaspăt", "Ягненок свежий", "Fresh lamb", 159.0, 10.0, 1L, null},
                {"PROMOTION", "Cozonac cu nucă", "Кекс с орехами козонак", "Walnut cozonac", 69.0, 15.0, 6L, null},
                {"PROMOTION", "Pască tradițională", "Пасха творожная", "Traditional easter bread", 79.0, 10.0, 6L, null},
                {"PROMOTION", "Vopsea ouă 5 culori", "Краска для яиц 5 цветов", "Egg dye 5 colors", 15.0, 20.0, 4L, null},
                {"PROMOTION", "Brad artificial 180cm", "Елка искусственная 180см", "Artificial christmas tree", 899.0, 25.0, 8L, null},
                {"PROMOTION", "Decorațiuni brad set", "Украшения для елки набор", "Tree decorations set", 149.0, 30.0, 8L, null},
                {"PROMOTION", "Mandarine 1kg", "Мандарины 1кг", "Tangerines 1kg", 35.0, 15.0, 5L, null},
                {"PROMOTION", "Icre roșii 100g", "Икра красная 100г", "Red caviar 100g", 189.0, 10.0, 1L, null},
                {"PROMOTION", "Artificii baterie 25 focuri", "Фейерверк батарея 25 залпов", "Fireworks 25 shots", 399.0, 15.0, 8L, null},
                {"PROMOTION", "Mărțișor argint", "Мэрцишор серебро", "Silver martisor", 99.0, 10.0, 9L, null},
                {"PROMOTION", "Caiete 48 file set", "Тетради 48 листов набор", "Notebooks 48 sheets set", 45.0, 20.0, 7L, null},
                {"PROMOTION", "Pixuri albastre 10 buc", "Ручки синие 10 шт", "Blue pens 10 pcs", 25.0, 15.0, 7L, null},
                {"PROMOTION", "Ghiozdan școlar", "Рюкзак школьный", "School backpack", 399.0, 25.0, 7L, null},
                {"PROMOTION", "Creioane colorate 24", "Карандаши цветные 24", "Colored pencils 24", 49.0, 20.0, 7L, null},
                {"PROMOTION", "Penar cu fermoar", "Пенал на молнии", "Zipper pencil case", 59.0, 10.0, 7L, null},
                {"PROMOTION", "Carioci 12 culori", "Фломастеры 12 цветов", "Markers 12 colors", 39.0, 15.0, 7L, null},
                {"PROMOTION", "Uniformă școlară", "Школьная форма", "School uniform", 499.0, 10.0, 7L, null},
                {"PROMOTION", "Pantofi pentru copii", "Туфли детские", "Kids shoes", 399.0, 20.0, 7L, null},
                {"PROMOTION", "Telefon Samsung Galaxy A55", "Телефон Самсунг Галакси A55", "Samsung Galaxy A55 phone", 6999.0, 15.0, 8L, 11L},
                {"PROMOTION", "Laptop Lenovo IdeaPad", "Ноутбук Леново ИдеаПад", "Lenovo IdeaPad laptop", 9999.0, 10.0, 7L, 11L},
                {"PROMOTION", "Televizor LG 55 inch", "Телевизор LG 55 дюймов", "LG 55 inch TV", 8999.0, 20.0, 8L, 11L},
                {"PROMOTION", "Detergent Ariel 3L", "Стиральный порошок Ариэль 3л", "Ariel detergent 3L", 189.0, 35.0, 3L, null},
                {"PROMOTION", "Scutece Pampers 4", "Подгузники Памперс 4", "Pampers diapers 4", 299.0, 25.0, 1L, null},
                {"PROMOTION", "Iaurt Danone 400g", "Йогурт Данон 400г", "Danone yogurt 400g", 21.0, 20.0, 5L, 21L},
                {"PROMOTION", "Smântână 20% 400g", "Сметана 20% 400г", "Sour cream 20% 400g", 23.0, 15.0, 5L, 21L},
                {"PROMOTION", "Coca-Cola 2L", "Кока-Кола 2л", "Coca-Cola 2L", 29.0, 20.0, 2L, 22L},
                {"RECIPE", "Rețetă de cozonac pufos", "Рецепт пышного кулича", "Fluffy cozonac recipe", null, null, null, 41L},
                {"RECIPE", "Supă de pui cu tăiței", "Куриный суп с лапшой", "Chicken noodle soup", null, null, null, 41L},
                {"RECIPE", "Salată de boeuf", "Салат оливье", "Olivier salad", null, null, null, 41L},
                {"BLOG", "Sfaturi pentru cumpărături inteligente", "Советы для умных покупок", "Smart shopping tips", null, null, null, 41L},
                {"BLOG", "Cum economisești la cumpărături", "Как экономить на покупках", "How to save on shopping", null, null, null, 41L},
                {"BOOKLET", "Catalog Linella săptămâna aceasta", "Каталог Линелла на неделю", "Linella weekly catalog", null, null, 5L, 31L},
                {"BOOKLET", "Catalog Kaufland", "Каталог Кауфланд", "Kaufland catalog", null, null, 3L, 31L},
                {"PROMOTION", "Hrană uscată pentru câini Pedigree 2kg", "Сухой корм для собак Педигри 2кг", "Pedigree dry dog food 2kg", 129.0, 15.0, 4L, null},
                {"PROMOTION", "Șampon Head & Shoulders 400ml", "Шампунь Хед энд Шолдерс 400мл", "Head & Shoulders shampoo 400ml", 79.0, 20.0, 5L, null}
        };
        long id = 100;
        for (Object[] r : rows) {
            id++;
            LocalDate validTo = today.plusDays(id % 3 == 0 ? 1 : 10);
            items.add(new Item((String) r[0], id, (String) r[1], (String) r[2], (String) r[3], (Double) r[4],
                    (Double) r[5], (Long) r[6], (Long) r[7], validTo, id * 1000L));
        }
    }

    @Override
    public List<ChatItemType> itemTypes() {
        return ChatTestSupport.TYPES;
    }

    @Override
    public boolean supportsRelaxation() {
        return true;
    }

    @Override
    public boolean isAvailable(String type) {
        return !"RAFFLE".equals(type);
    }

    @Override
    public Candidate companyCandidate(CompanyRef company) {
        return new Candidate("COMPANY", company.id(), company.slug(), Candidate.titles(company.name(), company.name()),
                null, null, null, null, company.id(), null, null, null, null, null, null, null, "/company/" + company.slug());
    }

    @Override
    public List<Candidate> retrieve(RetrievalPlan plan) {
        plans.add(plan);
        if (plan.related()) {
            return List.of();
        }
        List<String> q = queryTokens(plan.query());
        List<Item> out = new ArrayList<>();
        for (Item item : items) {
            if (!plan.types().contains(item.type())) {
                continue;
            }
            if (plan.companyId() != null && !plan.companyId().equals(item.companyId())) {
                continue;
            }
            if (plan.category() != null && !plan.category().id().equals(item.categoryId())) {
                continue;
            }
            if (plan.priceMax() != null && (item.price() == null || item.price() > plan.priceMax())) {
                continue;
            }
            if (plan.priceMin() != null && (item.price() == null || item.price() < plan.priceMin())) {
                continue;
            }
            if (plan.minDiscount() != null && (item.discount() == null || item.discount() < plan.minDiscount())) {
                continue;
            }
            if (q.isEmpty() || matches(item, q, plan.relax())) {
                out.add(item);
            }
        }
        if (plan.sortDiscount()) {
            out.sort(Comparator.comparingDouble((Item i) -> i.discount() == null ? -1 : i.discount()).reversed());
        }
        List<Candidate> candidates = new ArrayList<>();
        for (Item i : out.size() > plan.perType() * 2 ? out.subList(0, plan.perType() * 2) : out) {
            Map<String, String> titles = new LinkedHashMap<>();
            titles.put("ro", i.ro());
            titles.put("ru", i.ru());
            titles.put("en", i.en());
            candidates.add(new Candidate(i.type(), i.id(), "slug-" + i.id(), titles, null, i.price(), null, i.discount(),
                    i.companyId(), i.validTo(), i.freshness(), 5.0, null, null, null, null, ChatAiFixtures.path(i.type(), i.id())));
        }
        return candidates;
    }

    static List<String> queryTokens(String query) {
        List<String> out = new ArrayList<>();
        for (String t : TextNormalizer.tokens(query)) {
            if (t.length() >= 2 && !ChatTestSupport.ALL_LANGUAGES.isStopword(t)) {
                out.add(t);
            }
        }
        return out;
    }

    private static List<String> titleTokens(Item item) {
        List<String> out = new ArrayList<>();
        for (String t : TextNormalizer.tokens(item.ro() + " " + item.ru() + " " + item.en())) {
            out.add(t);
            if (TextNormalizer.isCyrillic(t.charAt(0))) {
                out.add(TextNormalizer.transliterate(t));
            }
        }
        return out;
    }

    private static boolean matches(Item item, List<String> query, int relax) {
        List<String> title = titleTokens(item);
        int hits = 0;
        for (String q : query) {
            if (tokenMatches(q, title, relax)) {
                hits++;
            }
        }
        return relax == RetrievalPlan.RELAX_NONE ? hits == query.size() : hits > 0;
    }

    private static boolean tokenMatches(String q, List<String> title, int relax) {
        if (relax == RetrievalPlan.RELAX_SYNONYMS) {
            String synonym = SYNONYMS.get(q);
            return synonym != null && title.stream().anyMatch(t -> t.startsWith(synonym));
        }
        List<String> forms = new ArrayList<>(List.of(q));
        if (relax >= RetrievalPlan.RELAX_VARIANTS && TextNormalizer.isCyrillic(q.charAt(0))) {
            forms.add(TextNormalizer.transliterate(q));
        }
        if (relax >= RetrievalPlan.RELAX_SYNONYMS && SYNONYMS.containsKey(q)) {
            forms.add(SYNONYMS.get(q));
        }
        for (String f : forms) {
            for (String t : title) {
                if (t.startsWith(f) || (f.length() >= 4 && f.startsWith(t) && t.length() >= 4)) {
                    return true;
                }
                int stem = Math.min(5, Math.min(f.length(), t.length()));
                if (stem >= 4 && f.regionMatches(0, t, 0, stem)) {
                    return true;
                }
                if (relax >= RetrievalPlan.RELAX_FUZZY && f.length() >= 4 && TextNormalizer.levenshtein(f, t, 2) <= 2) {
                    return true;
                }
                if (relax >= RetrievalPlan.RELAX_STEM && f.length() >= 4 && t.length() >= 4 && f.regionMatches(0, t, 0, 4)) {
                    return true;
                }
            }
        }
        return false;
    }
}
