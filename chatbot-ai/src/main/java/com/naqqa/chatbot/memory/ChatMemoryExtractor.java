package com.naqqa.chatbot.memory;

import com.naqqa.chatbot.ai.AiReply;
import com.naqqa.chatbot.ai.ConversationContext;
import com.naqqa.chatbot.ai.IntentResult;
import com.naqqa.chatbot.ai.IntentRouter;
import com.naqqa.chatbot.ai.PiiMasker;
import com.naqqa.chatbot.ai.TextNormalizer;
import com.naqqa.chatbot.ai.retrieval.CategoryRef;
import com.naqqa.chatbot.ai.retrieval.CompanyRef;
import com.naqqa.chatbot.ai.retrieval.PlaceRef;
import com.naqqa.chatbot.entities.ChatCard;
import com.naqqa.chatbot.i18n.ChatLanguages;
import com.naqqa.chatbot.spi.ChatEntityResolver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ChatMemoryExtractor {

    public static final int MAX_PRODUCT_TOKENS = 4;
    public static final int MAX_PRODUCT_LENGTH = 40;
    public static final int MAX_TEXT = 160;
    public static final int MAX_QUESTION = 120;
    public static final int MAX_ITEMS = 5;
    private static final List<String> MASKS = List.of(PiiMasker.EMAIL, PiiMasker.PHONE, PiiMasker.CARD, PiiMasker.IBAN,
            PiiMasker.IDNP, PiiMasker.SECRET);

    public record Observation(List<CompanyRef> stores, List<CategoryRef> categories, List<PlaceRef> places, List<String> brands,
                              String product, String productKey, boolean results, boolean cheapest, Double budgetMax,
                              List<String> hints, String unresolved, String unresolvedKey, String intent, String question,
                              String context, List<ConversationContext.Item> items) {

        public static final Observation EMPTY = new Observation(List.of(), List.of(), List.of(), List.of(), null, null, false, false,
                null, List.of(), null, null, null, null, null, List.of());

        public Observation {
            stores = stores == null ? List.of() : List.copyOf(stores);
            categories = categories == null ? List.of() : List.copyOf(categories);
            places = places == null ? List.of() : List.copyOf(places);
            brands = brands == null ? List.of() : List.copyOf(brands);
            hints = hints == null ? List.of() : List.copyOf(hints);
            items = items == null ? List.of() : List.copyOf(items);
        }

        public boolean empty() {
            return stores.isEmpty() && categories.isEmpty() && places.isEmpty() && brands.isEmpty() && product == null && !cheapest
                    && budgetMax == null && hints.isEmpty() && unresolved == null && question == null;
        }
    }

    public record Product(String label, String key) {
    }

    private final ChatLanguages languages;
    private final IntentRouter router;
    private final ChatEntityResolver directory;
    private final ChatMemoryCommands commands;
    private volatile List<String> brandSource;
    private volatile Map<String, String> brandIndex = Map.of();

    public ChatMemoryExtractor(ChatLanguages languages, IntentRouter router, ChatEntityResolver directory, ChatMemoryCommands commands) {
        this.languages = languages;
        this.router = router;
        this.directory = directory == null ? ChatEntityResolver.NONE : directory;
        this.commands = commands;
    }

    public static boolean hasPii(String text) {
        if (text == null) {
            return false;
        }
        if (PiiMasker.containsPii(text)) {
            return true;
        }
        for (String m : MASKS) {
            if (text.contains(m)) {
                return true;
            }
        }
        return false;
    }

    public Observation extract(String text, AiReply reply) {
        if (reply == null || text == null || text.isBlank()) {
            return Observation.EMPTY;
        }
        if (AiReply.ROUTE_GUARD.equals(reply.route()) || reply.flagged()) {
            return Observation.EMPTY;
        }
        String clean = TextNormalizer.clean(PiiMasker.mask(text));
        boolean pii = hasPii(clean);
        IntentResult r = route(clean);
        IntentRouter.Signals signals;
        try {
            signals = router.signals(clean);
        } catch (RuntimeException e) {
            signals = IntentRouter.Signals.NONE;
        }
        List<CardInfo> results = results(reply.cards());
        boolean hasResults = !results.isEmpty();
        boolean catalog = r != null && r.intent() != null && r.intent().isCatalog();
        List<CompanyRef> stores = signals.storeCompare() ? List.of() : stores(clean, r);
        List<CategoryRef> categories = new ArrayList<>();
        if (r != null && r.category() != null && knownCategory(r.category())) {
            categories.add(r.category());
        }
        List<PlaceRef> places = new ArrayList<>();
        if (r != null && r.place() != null && knownPlace(r.place())) {
            places.add(r.place());
        }
        Product product = null;
        if (hasResults && catalog && !pii && r.query() != null && !r.query().isBlank()) {
            product = product(r.query(), clean, stores, results);
        }
        List<String> brands = hasResults && !pii ? brands(clean, results) : List.of();
        Double budget = r != null && r.priceMin() == null && r.priceMax() != null && r.priceMax() > 0 ? r.priceMax() : null;
        List<String> hints = commands == null ? List.of() : commands.hints(clean);
        String unresolved = null;
        String unresolvedKey = null;
        if (!hasResults && !pii && catalog && reply.qualityFlags().contains(AiReply.FLAG_NO_RESULTS) && clean.length() <= MAX_TEXT) {
            unresolved = clean;
            Product p = r.query() == null ? null : product(r.query(), clean, stores, List.of());
            unresolvedKey = p != null ? p.key() : TextNormalizer.compact(clean);
        }
        boolean meaningful = hasResults || unresolved != null || AiReply.ROUTE_KNOWLEDGE.equals(reply.route());
        String question = pii || !meaningful ? null : cap(clean, MAX_QUESTION);
        ConversationContext ctx = ConversationContext.parse(reply.context());
        List<ConversationContext.Item> items = new ArrayList<>();
        if (ctx != null) {
            for (ConversationContext.Item i : ctx.items()) {
                if (items.size() >= MAX_ITEMS) {
                    break;
                }
                items.add(i);
            }
        }
        String context = ctx == null || pii ? null : reply.context();
        return new Observation(stores, categories, places, brands, product == null ? null : product.label(),
                product == null ? null : product.key(), hasResults, signals.cheapest(), budget, hints, unresolved, unresolvedKey,
                meaningful ? reply.intent() : null, question, context, items);
    }

    IntentResult route(String text) {
        try {
            return router.route(text, null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public List<CompanyRef> stores(String text, IntentResult routed) {
        Map<Long, CompanyRef> out = new LinkedHashMap<>();
        if (routed != null && routed.company() != null) {
            out.put(routed.company().id(), routed.company());
        }
        try {
            IntentRouter.StoreMatch match = router.stores(text);
            if (match != null) {
                for (CompanyRef c : match.companies()) {
                    out.putIfAbsent(c.id(), c);
                }
            }
        } catch (RuntimeException ignored) {
        }
        List<String> excluded;
        try {
            excluded = router.excluded(TextNormalizer.normalizedPhrase(text));
        } catch (RuntimeException e) {
            excluded = List.of();
        }
        List<CompanyRef> kept = new ArrayList<>();
        for (CompanyRef c : out.values()) {
            if (c == null || c.id() == null) {
                continue;
            }
            CompanyRef real = directory.company(c.id());
            if (real == null || negated(real, excluded)) {
                continue;
            }
            kept.add(real);
        }
        return kept;
    }

    private static boolean negated(CompanyRef company, List<String> excluded) {
        String name = TextNormalizer.compact(company.name());
        for (String w : excluded) {
            String x = TextNormalizer.compact(w);
            if (x.length() >= 3 && (name.contains(x) || x.contains(name))) {
                return true;
            }
        }
        return false;
    }

    private boolean knownCategory(CategoryRef ref) {
        for (CategoryRef c : directory.categories()) {
            if (c.id() != null && c.id().equals(ref.id()) && java.util.Objects.equals(c.taxonomy(), ref.taxonomy())) {
                return true;
            }
        }
        return false;
    }

    private boolean knownPlace(PlaceRef ref) {
        for (PlaceRef p : directory.places()) {
            if (p.id() != null && p.id().equals(ref.id()) && java.util.Objects.equals(p.kind(), ref.kind())) {
                return true;
            }
        }
        return false;
    }

    public Product product(String query, String original, List<CompanyRef> stores, List<CardInfo> results) {
        Set<String> storeTokens = new LinkedHashSet<>();
        for (CompanyRef c : stores) {
            storeTokens.addAll(TextNormalizer.tokens(c.name()));
        }
        Map<String, String> originals = new HashMap<>();
        for (String w : (original == null ? "" : original).split("[^\\p{L}\\p{N}]+")) {
            if (!w.isBlank()) {
                originals.putIfAbsent(TextNormalizer.fold(w), w.toLowerCase(java.util.Locale.ROOT));
            }
        }
        List<String> kept = new ArrayList<>();
        for (String t : TextNormalizer.tokens(query)) {
            if (t.length() < 2 || languages.isStopword(t) || languages.priceWords().contains(t) || storeTokens.contains(t)
                    || t.chars().anyMatch(Character::isDigit)) {
                continue;
            }
            kept.add(t);
        }
        if (kept.isEmpty() || kept.size() > MAX_PRODUCT_TOKENS) {
            return null;
        }
        if (!results.isEmpty()) {
            String head = languages.stem(kept.get(0));
            boolean seen = false;
            for (CardInfo c : results) {
                for (String w : TextNormalizer.tokens(c.title())) {
                    if (w.startsWith(head.length() >= 3 ? head : kept.get(0))) {
                        seen = true;
                        break;
                    }
                }
                if (seen) {
                    break;
                }
            }
            if (!seen) {
                return null;
            }
        }
        List<String> label = new ArrayList<>();
        List<String> key = new ArrayList<>();
        for (String t : kept) {
            label.add(originals.getOrDefault(t, t));
            key.add(languages.stem(t));
        }
        String text = String.join(" ", label);
        if (text.length() > MAX_PRODUCT_LENGTH) {
            return null;
        }
        return new Product(text, String.join(" ", key));
    }

    public static String productKey(ChatLanguages languages, String label) {
        List<String> key = new ArrayList<>();
        for (String t : TextNormalizer.tokens(label)) {
            key.add(languages.stem(t));
        }
        return String.join(" ", key);
    }

    private List<String> brands(String text, List<CardInfo> results) {
        Map<String, String> index = brandIndex();
        if (index.isEmpty()) {
            return List.of();
        }
        List<String> tokens = TextNormalizer.tokens(text);
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; i < tokens.size(); i++) {
            for (int n = 2; n >= 1; n--) {
                if (i + n > tokens.size()) {
                    continue;
                }
                String window = String.join("", tokens.subList(i, i + n));
                String brand = window.length() >= 3 ? index.get(window) : null;
                if (brand != null && languages.isStopword(window)) {
                    brand = null;
                }
                if (brand != null && inTitles(window, results)) {
                    out.add(brand);
                    break;
                }
            }
        }
        return List.copyOf(out);
    }

    private static boolean inTitles(String compact, List<CardInfo> results) {
        for (CardInfo c : results) {
            if (TextNormalizer.compact(c.title()).contains(compact)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, String> brandIndex() {
        List<String> source;
        try {
            source = directory.brands();
        } catch (RuntimeException e) {
            return Map.of();
        }
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        if (source != brandSource) {
            Map<String, String> index = new HashMap<>();
            for (String b : source) {
                if (b == null || b.isBlank()) {
                    continue;
                }
                String compact = TextNormalizer.compact(b);
                if (compact.length() >= 3 && compact.chars().anyMatch(Character::isLetter)) {
                    index.putIfAbsent(compact, b.trim());
                }
            }
            brandIndex = index;
            brandSource = source;
        }
        return brandIndex;
    }

    public record CardInfo(String title, Long companyId) {
    }

    public static List<CardInfo> results(List<ChatCard> cards) {
        List<CardInfo> out = new ArrayList<>();
        if (cards == null) {
            return out;
        }
        for (ChatCard c : cards) {
            if (c == null || "COMPANY".equals(c.getType()) || ChatCard.GROUP_RELATED.equals(c.getGroup()) || c.getTitle() == null) {
                continue;
            }
            out.add(new CardInfo(c.getTitle(), c.getCompanyId()));
        }
        return out;
    }

    static String cap(String value, int max) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        return v.length() > max ? v.substring(0, max).trim() : v;
    }
}
