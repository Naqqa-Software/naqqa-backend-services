package com.naqqa.chatbot.memory;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.naqqa.chatbot.ai.AiReply;
import com.naqqa.chatbot.ai.ConversationContext;
import com.naqqa.chatbot.ai.IntentResult;
import com.naqqa.chatbot.ai.IntentRouter;
import com.naqqa.chatbot.ai.MemoryContext;
import com.naqqa.chatbot.ai.TextNormalizer;
import com.naqqa.chatbot.ai.retrieval.CategoryRef;
import com.naqqa.chatbot.ai.retrieval.CompanyRef;
import com.naqqa.chatbot.ai.retrieval.PlaceRef;
import com.naqqa.chatbot.config.NaqqaChatbotProperties;
import com.naqqa.chatbot.dto.ChatDtos.MemoryItemDto;
import com.naqqa.chatbot.dto.ChatDtos.MemoryViewDto;
import com.naqqa.chatbot.entities.ChatMemoryEntity;
import com.naqqa.chatbot.entities.ChatMemoryEntity.Note;
import com.naqqa.chatbot.entities.ChatMemoryEntity.Signal;
import com.naqqa.chatbot.i18n.ChatLanguages;
import com.naqqa.chatbot.repository.ChatMemoryRepository;
import com.naqqa.chatbot.service.ChatException;
import com.naqqa.chatbot.spi.ChatEntityResolver;
import com.naqqa.chatbot.spi.ChatMemoryCipher;
import com.naqqa.chatbot.spi.ChatUserContextProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

@Slf4j
public class ChatMemoryService {

    public static final String INTENT = "memory";
    public static final String ID_PRICE = "price";
    public static final String ID_LAST = "last";
    public static final double INFERRED = 1.0;
    public static final double EXPLICIT = 3.0;
    public static final double PRUNE_BELOW = 0.15;
    public static final int MAX_QUESTIONS = 3;
    public static final int MAX_TOPICS = 5;
    private static final Duration BUDGET_TTL = Duration.ofDays(60);
    private static final Duration DOMINANT_TTL = Duration.ofDays(60);
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final Set<String> TARGET_FILLERS = Set.of("de", "ca", "despre", "la", "magazinul", "magazin", "produsul", "produs",
            "marca", "brandul", "categoria", "orasul", "regiunea", "preferinta", "prefer", "fac", "cumparaturi", "cumpar", "merg",
            "imi", "place", "ce", "eu", "про", "о", "об", "что", "я", "в", "магазин", "магазине", "товар", "бренд", "категорию",
            "город", "покупаю", "люблю", "the", "that", "i", "store", "shop", "at", "about", "my", "brand", "product", "city");

    private final ChatMemoryRepository repository;
    private final ChatLanguages languages;
    private final IntentRouter router;
    private final ChatEntityResolver directory;
    private final NaqqaChatbotProperties.Memory config;
    private final ChatMemoryCommands commands;
    private final ChatMemoryExtractor extractor;
    private ChatMemoryCipher cipher = ChatMemoryCipher.NONE;
    private ChatUserContextProvider userContext = ChatUserContextProvider.NONE;
    private Supplier<Instant> clock = Instant::now;
    private java.util.function.BiFunction<String, String, List<String>> catalogProbe;

    public ChatMemoryService(ChatMemoryRepository repository, ChatLanguages languages, IntentRouter router, ChatEntityResolver directory,
                             NaqqaChatbotProperties.Memory config) {
        this.repository = repository;
        this.languages = languages;
        this.router = router;
        this.directory = directory == null ? ChatEntityResolver.NONE : directory;
        this.config = config == null ? new NaqqaChatbotProperties.Memory() : config;
        this.commands = new ChatMemoryCommands(languages);
        this.extractor = new ChatMemoryExtractor(languages, router, this.directory, commands);
    }

    public void setCipher(ChatMemoryCipher cipher) {
        this.cipher = cipher == null ? ChatMemoryCipher.NONE : cipher;
    }

    public void setUserContext(ChatUserContextProvider userContext) {
        this.userContext = userContext == null ? ChatUserContextProvider.NONE : userContext;
    }

    public void setClock(Supplier<Instant> clock) {
        this.clock = clock == null ? Instant::now : clock;
    }

    public void setCatalogProbe(java.util.function.BiFunction<String, String, List<String>> catalogProbe) {
        this.catalogProbe = catalogProbe;
    }

    public boolean enabled() {
        return config.isEnabled();
    }

    public ChatMemoryCommands commands() {
        return commands;
    }

    public ChatMemoryExtractor extractor() {
        return extractor;
    }

    public NaqqaChatbotProperties.Memory config() {
        return config;
    }

    public static String ownerId(Long userId) {
        return "u:" + userId;
    }

    public ChatMemoryEntity load(Long userId) {
        if (userId == null) {
            return null;
        }
        ChatMemoryEntity stored = repository.findById(ownerId(userId)).orElse(null);
        if (stored == null) {
            return null;
        }
        if (!Objects.equals(stored.getUserId(), userId)) {
            return null;
        }
        Instant now = clock.get();
        if (stored.getExpiresAt() != null && stored.getExpiresAt().isBefore(now)) {
            repository.deleteById(stored.getId());
            return null;
        }
        return decode(stored);
    }

    private ChatMemoryEntity loadOrNew(Long userId) {
        ChatMemoryEntity m = load(userId);
        if (m != null) {
            return m;
        }
        m = new ChatMemoryEntity();
        m.setId(ownerId(userId));
        m.setUserId(userId);
        m.setCreatedAt(clock.get());
        return m;
    }

    private void save(ChatMemoryEntity m) {
        Instant now = clock.get();
        prune(m, now);
        m.setUpdatedAt(now);
        m.setExpiresAt(now.plus(Duration.ofDays(Math.max(1, config.getRetentionDays()))));
        repository.save(encode(m));
    }

    public void observe(Long userId, String conversationId, String lang, String text, AiReply reply) {
        if (!enabled() || userId == null || reply == null) {
            return;
        }
        try {
            ChatMemoryEntity m = loadOrNew(userId);
            if (m.isPaused()) {
                return;
            }
            ChatMemoryExtractor.Observation o = extractor.extract(text, reply);
            if (o.empty() && o.items().isEmpty()) {
                return;
            }
            apply(m, o, clock.get(), lang, conversationId);
            save(m);
        } catch (RuntimeException e) {
            log.warn("[chatbot] memory observe failed: {}", e.getMessage());
        }
    }

    void apply(ChatMemoryEntity m, ChatMemoryExtractor.Observation o, Instant now, String lang, String conversationId) {
        for (CompanyRef c : o.stores()) {
            bump(m.getStores(), "store:" + c.id(), c.name(), c.id(), "company", INFERRED, false, now);
        }
        for (CategoryRef c : o.categories()) {
            bump(m.getCategories(), "category:" + c.taxonomy() + ":" + c.id(), c.label("ro"), c.id(), c.taxonomy(), INFERRED, false, now);
        }
        for (PlaceRef p : o.places()) {
            bump(m.getPlaces(), "place:" + p.kind() + ":" + p.id(), p.label("ro"), p.id(), p.kind(), INFERRED, false, now);
        }
        for (String b : o.brands()) {
            bump(m.getBrands(), "brand:" + TextNormalizer.compact(b), b, null, null, INFERRED, false, now);
        }
        if (o.product() != null && o.productKey() != null) {
            bump(m.getProducts(), "product:" + o.productKey(), o.product(), null, null, INFERRED, false, now);
            m.getUnresolved().removeIf(n -> o.productKey().equals(n.getKey()));
        }
        if (o.cheapest()) {
            m.setCheapestWeight(decay(m.getCheapestWeight(), m.getCheapestAt(), now, false) + INFERRED);
            m.setCheapestCount(m.getCheapestCount() + 1);
            m.setCheapestAt(now);
        }
        if (o.budgetMax() != null && (!m.isBudgetExplicit() || m.getBudgetAt() == null
                || m.getBudgetAt().plus(BUDGET_TTL).isBefore(now))) {
            m.setBudgetMax(o.budgetMax());
            m.setBudgetAt(now);
            m.setBudgetExplicit(false);
        }
        for (String h : o.hints()) {
            bump(m.getHints(), "hint:" + h, h, null, null, EXPLICIT, true, now);
        }
        if (o.unresolved() != null) {
            String key = o.unresolvedKey();
            m.getUnresolved().removeIf(n -> Objects.equals(key, n.getKey()));
            m.getUnresolved().add(note(o.unresolved(), key, now));
        }
        if (o.intent() != null && !o.intent().isBlank()) {
            List<String> intents = m.getIntents();
            if (intents.isEmpty() || !o.intent().equals(intents.get(intents.size() - 1))) {
                intents.add(o.intent());
            }
            while (intents.size() > Math.max(1, config.getMaxIntents())) {
                intents.remove(0);
            }
        }
        if (lang != null) {
            m.setLang(lang);
        }
        summarize(m, o, now, conversationId);
    }

    private void summarize(ChatMemoryEntity m, ChatMemoryExtractor.Observation o, Instant now, String conversationId) {
        if (conversationId == null) {
            return;
        }
        MemoryContext.Summary current = summary(m.getSummary());
        if (current != null && !conversationId.equals(current.conversationId())) {
            m.setPreviousSummary(m.getSummary());
            current = null;
        }
        List<String> questions = new ArrayList<>(current == null ? List.of() : current.questions());
        if (o.question() != null && !o.question().isBlank()) {
            questions.remove(o.question());
            questions.add(o.question());
        }
        while (questions.size() > MAX_QUESTIONS) {
            questions.remove(0);
        }
        List<String> topics = new ArrayList<>(current == null ? List.of() : current.topics());
        if (o.product() != null) {
            topics.remove(o.product());
            topics.add(o.product());
        }
        while (topics.size() > MAX_TOPICS) {
            topics.remove(0);
        }
        String context = current == null ? null : current.context();
        List<ConversationContext.Item> items = current == null ? List.of() : current.items();
        ConversationContext parsed = ConversationContext.parse(o.context());
        if (parsed != null && (parsed.hasItems() || parsed.query() != null && !parsed.query().isBlank())) {
            context = o.context();
            if (!o.items().isEmpty()) {
                items = o.items();
            }
        }
        m.setSummary(json(new MemoryContext.Summary(conversationId, now, questions, topics, context, items)));
    }

    public MemoryContext snapshot(Long userId, String conversationId) {
        if (!enabled() || userId == null) {
            return null;
        }
        ChatMemoryEntity m;
        try {
            m = load(userId);
        } catch (RuntimeException e) {
            log.warn("[chatbot] memory load failed: {}", e.getMessage());
            return null;
        }
        if (m == null || m.isPaused()) {
            return null;
        }
        Instant now = clock.get();
        List<MemoryContext.Pref> stores = new ArrayList<>();
        for (Signal s : ordered(m.getStores(), now)) {
            CompanyRef c = s.getRefId() == null ? null : directory.company(s.getRefId());
            if (c != null) {
                stores.add(pref(s, c.name(), now));
            }
        }
        List<MemoryContext.Pref> categories = new ArrayList<>();
        for (Signal s : ordered(m.getCategories(), now)) {
            CategoryRef c = category(s.getRefKind(), s.getRefId());
            if (c != null) {
                categories.add(pref(s, c.label(m.getLang()), now));
            }
        }
        List<MemoryContext.Pref> brands = new ArrayList<>();
        for (Signal s : ordered(m.getBrands(), now)) {
            brands.add(pref(s, s.getLabel(), now));
        }
        List<MemoryContext.Pref> products = new ArrayList<>();
        for (Signal s : ordered(m.getProducts(), now)) {
            products.add(pref(s, s.getLabel(), now));
        }
        MemoryContext.Pref place = null;
        for (Signal s : ordered(m.getPlaces(), now)) {
            PlaceRef p = place(s.getRefKind(), s.getRefId());
            if (p != null && (s.isExplicit() || effective(s, now) >= INFERRED)) {
                place = pref(s, p.label(m.getLang()), now);
                break;
            }
        }
        if (place == null) {
            PlaceRef profile = profilePlace(userId);
            if (profile != null) {
                place = new MemoryContext.Pref("place:" + profile.kind() + ":" + profile.id(), profile.id(), profile.label(m.getLang()),
                        INFERRED, 0, false, null);
            }
        }
        List<String> hints = new ArrayList<>();
        for (Signal s : ordered(m.getHints(), now)) {
            hints.add(s.getKey().startsWith("hint:") ? s.getKey().substring(5) : s.getKey());
        }
        boolean cheapest = decay(m.getCheapestWeight(), m.getCheapestAt(), now, false) >= 2 * INFERRED - 0.01;
        Double budget = m.getBudgetMax() != null && m.getBudgetAt() != null
                && (m.isBudgetExplicit() || m.getBudgetAt().plus(BUDGET_TTL).isAfter(now)) ? m.getBudgetMax() : null;
        MemoryContext.Summary last = last(m, conversationId);
        return new MemoryContext(userId, stores, categories, brands, products, place, defaultStore(m, now), cheapest, budget, hints,
                last, config.getBoostCap());
    }

    private MemoryContext.Summary last(ChatMemoryEntity m, String conversationId) {
        MemoryContext.Summary current = summary(m.getSummary());
        MemoryContext.Summary previous = summary(m.getPreviousSummary());
        if (current == null) {
            return previous;
        }
        if (conversationId == null || !conversationId.equals(current.conversationId())) {
            return current;
        }
        return previous != null ? previous : current;
    }

    Long defaultStore(ChatMemoryEntity m, Instant now) {
        Signal explicit = null;
        double total = 0;
        Signal top = null;
        for (Signal s : m.getStores()) {
            if (s.getRefId() == null || directory.company(s.getRefId()) == null) {
                continue;
            }
            if (s.isExplicit() && (explicit == null || after(s.getLastSeen(), explicit.getLastSeen()))) {
                explicit = s;
            }
            double w = effective(s, now);
            total += w;
            if (top == null || w > effective(top, now)) {
                top = s;
            }
        }
        if (explicit != null) {
            return explicit.getRefId();
        }
        if (top == null || total <= 0) {
            return null;
        }
        boolean recent = top.getLastSeen() != null && top.getLastSeen().plus(DOMINANT_TTL).isAfter(now);
        boolean frequent = top.getCount() >= Math.max(1, config.getDefaultStoreMinCount());
        boolean dominant = effective(top, now) / total >= config.getDefaultStoreShare();
        return recent && frequent && dominant ? top.getRefId() : null;
    }

    private static boolean after(Instant a, Instant b) {
        return a != null && (b == null || a.isAfter(b));
    }

    private PlaceRef profilePlace(Long userId) {
        try {
            PlaceRef p = userContext.place(userId);
            return p == null ? null : place(p.kind(), p.id()) != null ? place(p.kind(), p.id()) : p;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public AiReply command(Long userId, String conversationId, String text, String lang) {
        if (!enabled()) {
            return null;
        }
        ChatMemoryCommands.Detected d = commands.detect(text);
        if (d == null) {
            return null;
        }
        if (userId == null) {
            return d.strong() ? reply(languages.template("memory_login", lang), lang, List.of()) : null;
        }
        return switch (d.command()) {
            case VIEW -> reply(viewText(userId, lang), lang, viewQuickReplies(userId));
            case FORGET_ALL -> {
                forgetAll(userId);
                yield reply(languages.template("memory_forgot_all", lang), lang, List.of());
            }
            case PAUSE -> {
                ChatMemoryEntity m = loadOrNew(userId);
                if (m.isPaused()) {
                    yield reply(languages.template("memory_already_paused", lang), lang, List.of("mem_resume"));
                }
                m.setPaused(true);
                save(m);
                yield reply(languages.template("memory_paused", lang), lang, List.of("mem_resume"));
            }
            case RESUME -> {
                ChatMemoryEntity m = loadOrNew(userId);
                m.setPaused(false);
                save(m);
                yield reply(languages.template("memory_resumed", lang), lang, List.of("mem_view"));
            }
            case FORGET -> forgetCommand(userId, d, lang);
            case REMEMBER -> remember(userId, d.payload(), lang);
        };
    }

    private AiReply forgetCommand(Long userId, ChatMemoryCommands.Detected d, String lang) {
        String target = d.payload() == null ? "" : d.payload().trim();
        ChatMemoryEntity m = load(userId);
        if (target.isEmpty()) {
            return d.strong() ? reply(viewText(userId, lang), lang, viewQuickReplies(userId)) : null;
        }
        String id = m == null ? null : match(m, target, lang);
        if (id == null) {
            return d.strong() ? reply(languages.format("memory_forget_unknown", lang, ChatMemoryExtractor.cap(target, 60)), lang,
                    List.of("mem_view")) : null;
        }
        String label = remove(m, id, lang);
        save(m);
        return reply(languages.format("memory_forgot_item", lang, label == null ? target : label), lang, List.of("mem_view"));
    }

    String match(ChatMemoryEntity m, String target, String lang) {
        List<String> wanted = new ArrayList<>();
        for (String t : TextNormalizer.tokens(target)) {
            if (!TARGET_FILLERS.contains(t) && !languages.isStopword(t)) {
                wanted.add(t);
            }
        }
        if (wanted.isEmpty()) {
            return null;
        }
        for (MemoryItemDto item : items(m, lang, clock.get())) {
            if (ID_LAST.equals(item.id()) || ID_PRICE.equals(item.id())) {
                continue;
            }
            List<String> label = TextNormalizer.tokens(item.label());
            if (label.isEmpty()) {
                continue;
            }
            boolean all = true;
            for (String w : wanted) {
                boolean hit = false;
                for (String l : label) {
                    if (l.equals(w) || languages.stem(l).equals(languages.stem(w))) {
                        hit = true;
                        break;
                    }
                }
                if (!hit) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return item.id();
            }
        }
        return null;
    }

    private AiReply remember(Long userId, String payload, String lang) {
        ChatMemoryEntity m = loadOrNew(userId);
        if (m.isPaused()) {
            return reply(languages.template("memory_remember_paused", lang), lang, List.of("mem_resume"));
        }
        String text = payload == null ? "" : TextNormalizer.clean(payload);
        if (text.isBlank() || TextNormalizer.tokens(text).isEmpty()) {
            return reply(languages.template("memory_remember_empty", lang), lang, List.of());
        }
        if (ChatMemoryExtractor.hasPii(text)) {
            return reply(languages.template("memory_remember_pii", lang), lang, List.of());
        }
        for (Pattern p : languages.injectionPatterns()) {
            if (p.matcher(text).find()) {
                return reply(languages.template("memory_remember_rejected", lang), lang, List.of());
            }
        }
        text = ChatMemoryExtractor.cap(text, ChatMemoryExtractor.MAX_TEXT);
        IntentResult r = extractor.route(text);
        List<CompanyRef> stores = extractor.stores(text, r);
        Instant now = clock.get();
        boolean resolved = false;
        if (!stores.isEmpty()) {
            Set<Long> named = new LinkedHashSet<>();
            for (CompanyRef c : stores) {
                named.add(c.id());
            }
            List<String> demoted = new ArrayList<>();
            for (Signal s : m.getStores()) {
                if (s.isExplicit() && !named.contains(s.getRefId())) {
                    s.setExplicit(false);
                    s.setSource(ChatMemoryEntity.SOURCE_CHAT);
                    demoted.add(s.getLabel());
                }
            }
            for (CompanyRef c : stores) {
                bump(m.getStores(), "store:" + c.id(), c.name(), c.id(), "company", EXPLICIT, true, now);
            }
            if (!demoted.isEmpty()) {
                m.getFacts().removeIf(f -> mentionsAny(f.getText(), demoted));
            }
            resolved = true;
        }
        if (r != null && r.place() != null) {
            for (Signal s : m.getPlaces()) {
                s.setExplicit(false);
            }
            bump(m.getPlaces(), "place:" + r.place().kind() + ":" + r.place().id(), r.place().label("ro"), r.place().id(),
                    r.place().kind(), EXPLICIT, true, now);
            resolved = true;
        }
        if (r != null && r.category() != null) {
            bump(m.getCategories(), "category:" + r.category().taxonomy() + ":" + r.category().id(), r.category().label("ro"),
                    r.category().id(), r.category().taxonomy(), EXPLICIT, true, now);
            resolved = true;
        }
        if (r != null && r.priceMax() != null && r.priceMin() == null && r.priceMax() > 0) {
            m.setBudgetMax(r.priceMax());
            m.setBudgetAt(now);
            m.setBudgetExplicit(true);
            resolved = true;
        }
        for (String h : commands.hints(text)) {
            bump(m.getHints(), "hint:" + h, h, null, null, EXPLICIT, true, now);
            resolved = true;
        }
        if (r != null && r.intent() != null && r.intent().isCatalog() && r.query() != null && !r.query().isBlank() && stores.isEmpty()) {
            List<ChatMemoryExtractor.CardInfo> found = probe(r.query(), lang);
            ChatMemoryExtractor.Product p = found.isEmpty() ? null : extractor.product(r.query(), text, stores, found);
            if (p != null) {
                bump(m.getProducts(), "product:" + p.key(), p.label(), null, null, EXPLICIT, true, now);
                resolved = true;
            }
        }
        if (!resolved) {
            return reply(languages.template("memory_remember_rejected", lang), lang, List.of());
        }
        String key = TextNormalizer.compact(text);
        m.getFacts().removeIf(f -> key.equals(f.getKey()));
        m.getFacts().add(note(text, key, now));
        save(m);
        if (!stores.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (CompanyRef c : stores) {
                names.add(c.name());
            }
            return reply(languages.format("memory_remember_store", lang, String.join(", ", names)), lang, List.of("mem_view"));
        }
        return reply(languages.format("memory_remembered", lang, languages.format("memory_quoted", lang, text)), lang, List.of("mem_view"));
    }

    private List<ChatMemoryExtractor.CardInfo> probe(String query, String lang) {
        if (catalogProbe == null) {
            return List.of();
        }
        try {
            List<ChatMemoryExtractor.CardInfo> out = new ArrayList<>();
            for (String title : catalogProbe.apply(query, lang)) {
                if (title != null && !title.isBlank()) {
                    out.add(new ChatMemoryExtractor.CardInfo(title, null));
                }
            }
            return out;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static boolean mentionsAny(String text, List<String> labels) {
        String compact = TextNormalizer.compact(text);
        for (String l : labels) {
            String c = TextNormalizer.compact(l);
            if (!c.isEmpty() && compact.contains(c)) {
                return true;
            }
        }
        return false;
    }

    public MemoryViewDto view(Long userId, String lang) {
        requireUser(userId);
        ChatMemoryEntity m = enabled() ? load(userId) : null;
        Instant now = clock.get();
        List<String> lists = lists(userId, lang);
        if (m == null) {
            return new MemoryViewDto(enabled(), false, List.of(), lists, null, null, config.getRetentionDays(), userId);
        }
        return new MemoryViewDto(enabled(), m.isPaused(), items(m, lang, now), lists, m.getUpdatedAt(), m.getExpiresAt(),
                config.getRetentionDays(), userId);
    }

    private List<String> lists(Long userId, String lang) {
        List<String> out = new ArrayList<>();
        try {
            for (ChatUserContextProvider.ShoppingList l : userContext.lists(userId, lang)) {
                if (l != null && l.name() != null && !l.name().isBlank()) {
                    out.add(languages.format("memory_list_item", lang, l.name(), l.total()));
                }
            }
        } catch (RuntimeException e) {
            log.debug("[chatbot] memory lists failed: {}", e.getMessage());
        }
        return out;
    }

    public MemoryViewDto forget(Long userId, String itemId, String lang) {
        requireUser(userId);
        ChatMemoryEntity m = load(userId);
        if (m == null || itemId == null || remove(m, itemId, lang) == null) {
            throw new ChatException(HttpStatus.NOT_FOUND, ChatException.NOT_FOUND, "Memory item not found.");
        }
        save(m);
        return view(userId, lang);
    }

    public void forgetAll(Long userId) {
        requireUser(userId);
        repository.deleteById(ownerId(userId));
        repository.deleteByUserId(userId);
    }

    public void deleteUser(Long userId) {
        if (userId == null) {
            return;
        }
        repository.deleteById(ownerId(userId));
        repository.deleteByUserId(userId);
    }

    public MemoryViewDto pause(Long userId, boolean paused, String lang) {
        requireUser(userId);
        ChatMemoryEntity m = loadOrNew(userId);
        m.setPaused(paused);
        save(m);
        return view(userId, lang);
    }

    public long purgeExpired() {
        try {
            return repository.deleteExpired(clock.get());
        } catch (RuntimeException e) {
            log.warn("[chatbot] memory purge failed: {}", e.getMessage());
            return 0;
        }
    }

    private static void requireUser(Long userId) {
        if (userId == null) {
            throw new ChatException(HttpStatus.UNAUTHORIZED, ChatException.FORBIDDEN, "Sign in to manage what the assistant remembers.");
        }
    }

    String remove(ChatMemoryEntity m, String id, String lang) {
        if (ID_PRICE.equals(id)) {
            if (m.getCheapestCount() == 0 && m.getBudgetMax() == null) {
                return null;
            }
            String label = priceLabel(m, lang, clock.get());
            m.setCheapestCount(0);
            m.setCheapestWeight(0);
            m.setCheapestAt(null);
            m.setBudgetMax(null);
            m.setBudgetAt(null);
            m.setBudgetExplicit(false);
            return label == null ? id : label;
        }
        if (ID_LAST.equals(id)) {
            if (m.getSummary() == null && m.getPreviousSummary() == null) {
                return null;
            }
            String label = lastLabel(last(m, null));
            m.setSummary(null);
            m.setPreviousSummary(null);
            return label == null ? id : label;
        }
        for (List<Signal> list : List.of(m.getStores(), m.getCategories(), m.getBrands(), m.getProducts(), m.getPlaces(), m.getHints())) {
            for (Signal s : list) {
                if (id.equals(s.getId())) {
                    String label = signalLabel(s, lang);
                    list.remove(s);
                    if (s.getKey() != null && s.getKey().startsWith("store:")) {
                        m.getFacts().removeIf(f -> mentionsAny(f.getText(), List.of(label)));
                    }
                    if (s.getKey() != null && s.getKey().startsWith("product:")) {
                        String product = s.getLabel();
                        stripTopic(m, product);
                    }
                    return label;
                }
            }
        }
        for (List<Note> list : List.of(m.getFacts(), m.getUnresolved())) {
            for (Note n : list) {
                if (id.equals(n.getId())) {
                    list.remove(n);
                    return n.getText();
                }
            }
        }
        return null;
    }

    private void stripTopic(ChatMemoryEntity m, String product) {
        for (boolean previous : new boolean[]{false, true}) {
            MemoryContext.Summary s = summary(previous ? m.getPreviousSummary() : m.getSummary());
            if (s == null || !s.topics().contains(product)) {
                continue;
            }
            List<String> topics = new ArrayList<>(s.topics());
            topics.remove(product);
            ConversationContext ctx = s.parsed();
            boolean same = ctx != null && ctx.query() != null && TextNormalizer.compact(ctx.query()).contains(TextNormalizer.compact(product));
            String updated = json(new MemoryContext.Summary(s.conversationId(), s.at(), s.questions(), topics, same ? null : s.context(),
                    same ? List.of() : s.items()));
            if (previous) {
                m.setPreviousSummary(updated);
            } else {
                m.setSummary(updated);
            }
        }
    }

    List<MemoryItemDto> items(ChatMemoryEntity m, String lang, Instant now) {
        List<MemoryItemDto> out = new ArrayList<>();
        for (Signal s : ordered(m.getStores(), now)) {
            out.add(item(s, "store", signalLabel(s, lang), lang));
        }
        for (Signal s : ordered(m.getCategories(), now)) {
            out.add(item(s, "category", signalLabel(s, lang), lang));
        }
        for (Signal s : ordered(m.getBrands(), now)) {
            out.add(item(s, "brand", signalLabel(s, lang), lang));
        }
        for (Signal s : ordered(m.getProducts(), now)) {
            out.add(item(s, "product", signalLabel(s, lang), lang));
        }
        for (Signal s : ordered(m.getPlaces(), now)) {
            out.add(item(s, "place", signalLabel(s, lang), lang));
        }
        String price = priceLabel(m, lang, now);
        if (price != null) {
            out.add(new MemoryItemDto(ID_PRICE, "price", price, null, m.isBudgetExplicit(), latest(m.getCheapestAt(), m.getBudgetAt())));
        }
        for (Signal s : ordered(m.getHints(), now)) {
            out.add(item(s, "hint", signalLabel(s, lang), lang));
        }
        for (Note n : m.getFacts()) {
            out.add(new MemoryItemDto(n.getId(), "fact", n.getText(), null, true, n.getAt()));
        }
        for (Note n : m.getUnresolved()) {
            out.add(new MemoryItemDto(n.getId(), "unresolved", n.getText(), null, false, n.getAt()));
        }
        MemoryContext.Summary last = last(m, null);
        String lastLabel = lastLabel(last);
        if (lastLabel != null) {
            out.add(new MemoryItemDto(ID_LAST, "last", lastLabel, when(last.at(), lang), false, last.at()));
        }
        return out;
    }

    private MemoryItemDto item(Signal s, String kind, String label, String lang) {
        String detail = s.isExplicit() ? languages.template("memory_explicit", lang)
                : ChatMemoryEntity.SOURCE_PROFILE.equals(s.getSource()) ? languages.template("memory_profile", lang)
                : s.getCount() > 1 ? languages.format("memory_times", lang, s.getCount()) : null;
        return new MemoryItemDto(s.getId(), kind, label, detail, s.isExplicit(), s.getLastSeen());
    }

    private String signalLabel(Signal s, String lang) {
        String key = s.getKey() == null ? "" : s.getKey();
        if (key.startsWith("store:") && s.getRefId() != null) {
            CompanyRef c = directory.company(s.getRefId());
            return c != null ? c.name() : s.getLabel();
        }
        if (key.startsWith("category:")) {
            CategoryRef c = category(s.getRefKind(), s.getRefId());
            return c != null ? c.label(lang) : s.getLabel();
        }
        if (key.startsWith("place:")) {
            PlaceRef p = place(s.getRefKind(), s.getRefId());
            return p != null ? p.label(lang) : s.getLabel();
        }
        if (key.startsWith("hint:")) {
            String t = languages.template("memory_hint." + key.substring(5), lang);
            return t.isBlank() ? key.substring(5) : t;
        }
        return s.getLabel();
    }

    private String priceLabel(ChatMemoryEntity m, String lang, Instant now) {
        List<String> parts = new ArrayList<>();
        if (m.getCheapestCount() > 0 && decay(m.getCheapestWeight(), m.getCheapestAt(), now, false) >= INFERRED * 0.5) {
            parts.add(languages.template("memory_price_cheapest", lang));
        }
        if (m.getBudgetMax() != null) {
            parts.add(languages.format("memory_price_budget", lang, money(m.getBudgetMax())));
        }
        return parts.isEmpty() ? null : String.join("; ", parts);
    }

    private String lastLabel(MemoryContext.Summary last) {
        if (last == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        ConversationContext ctx = last.parsed();
        String store = null;
        if (ctx != null && ctx.companyId() != null) {
            CompanyRef c = directory.company(ctx.companyId());
            store = c == null ? null : c.name();
        }
        if (!last.topics().isEmpty()) {
            parts.add(String.join(", ", last.topics()) + (store == null ? "" : " · " + store));
        } else if (ctx != null && ctx.query() != null && !ctx.query().isBlank()) {
            parts.add(ctx.query() + (store == null ? "" : " · " + store));
        } else if (!last.questions().isEmpty()) {
            parts.add(last.questions().get(last.questions().size() - 1));
        }
        return parts.isEmpty() ? null : String.join("; ", parts);
    }

    public String when(Instant at, String lang) {
        if (at == null) {
            return "";
        }
        ZoneId zone = ZoneId.systemDefault();
        LocalDate then = at.atZone(zone).toLocalDate();
        LocalDate today = clock.get().atZone(zone).toLocalDate();
        long days = java.time.temporal.ChronoUnit.DAYS.between(then, today);
        if (days <= 0) {
            return languages.template("memory_when_today", lang);
        }
        if (days == 1) {
            return languages.template("memory_when_yesterday", lang);
        }
        return languages.format("memory_when_days", lang, days);
    }

    public String viewText(Long userId, String lang) {
        ChatMemoryEntity m = load(userId);
        Instant now = clock.get();
        List<String> lists = lists(userId, lang);
        List<MemoryItemDto> items = m == null ? List.of() : items(m, lang, now);
        StringBuilder sb = new StringBuilder();
        if (items.isEmpty() && lists.isEmpty()) {
            sb.append(languages.template("memory_view_empty", lang));
        } else {
            sb.append(languages.template("memory_view_header", lang));
            line(sb, "memory_line_stores", join(items, "store"), lang);
            line(sb, "memory_line_categories", join(items, "category"), lang);
            line(sb, "memory_line_brands", join(items, "brand"), lang);
            line(sb, "memory_line_products", join(items, "product"), lang);
            line(sb, "memory_line_place", join(items, "place"), lang);
            line(sb, "memory_line_price", join(items, "price"), lang);
            line(sb, "memory_line_hints", join(items, "hint"), lang);
            line(sb, "memory_line_facts", quoted(items, "fact", lang), lang);
            line(sb, "memory_line_unresolved", quoted(items, "unresolved", lang), lang);
            for (MemoryItemDto i : items) {
                if ("last".equals(i.kind())) {
                    sb.append('\n').append(languages.format("memory_line_last", lang, i.detail(), i.label()));
                }
            }
            if (!lists.isEmpty()) {
                sb.append('\n').append(languages.format("memory_line_lists", lang, String.join(", ", lists)));
            }
        }
        if (m != null && m.isPaused()) {
            sb.append("\n\n").append(languages.template("memory_line_paused", lang));
        }
        String example = null;
        for (MemoryItemDto i : items) {
            if ("store".equals(i.kind()) || "product".equals(i.kind())) {
                example = i.label();
                break;
            }
        }
        sb.append("\n\n").append(example == null ? languages.template("memory_view_footer", lang)
                : languages.format("memory_view_footer_item", lang, example));
        return sb.toString();
    }

    private List<String> viewQuickReplies(Long userId) {
        ChatMemoryEntity m = load(userId);
        if (m == null) {
            return List.of();
        }
        return m.isPaused() ? List.of("mem_resume", "mem_forget_all") : List.of("mem_forget_all");
    }

    private void line(StringBuilder sb, String key, String value, String lang) {
        if (value != null && !value.isBlank()) {
            sb.append('\n').append(languages.format(key, lang, value));
        }
    }

    private static String join(List<MemoryItemDto> items, String kind) {
        List<String> out = new ArrayList<>();
        for (MemoryItemDto i : items) {
            if (kind.equals(i.kind())) {
                out.add(i.detail() == null ? i.label() : i.label() + " (" + i.detail() + ")");
            }
        }
        return String.join(", ", out);
    }

    private String quoted(List<MemoryItemDto> items, String kind, String lang) {
        List<String> out = new ArrayList<>();
        for (MemoryItemDto i : items) {
            if (kind.equals(i.kind())) {
                out.add(languages.format("memory_quoted", lang, i.label()));
            }
        }
        return String.join(", ", out);
    }

    private AiReply reply(String text, String lang, List<String> quickReplies) {
        return new AiReply(text, List.of(), quickReplies == null ? List.of() : quickReplies, INTENT, 1.0, false, false, 0, 0, 0, false,
                AiReply.ROUTE_TEMPLATE, List.of(), lang);
    }

    void bump(List<Signal> list, String key, String label, Long refId, String refKind, double inc, boolean explicit, Instant now) {
        Signal found = null;
        for (Signal s : list) {
            if (key.equals(s.getKey())) {
                found = s;
                break;
            }
        }
        if (found == null) {
            found = new Signal();
            found.setId(shortId());
            found.setKey(key);
            found.setFirstSeen(now);
            found.setSource(explicit ? ChatMemoryEntity.SOURCE_EXPLICIT : ChatMemoryEntity.SOURCE_CHAT);
            list.add(found);
        }
        found.setWeight(effective(found, now) + inc);
        found.setCount(found.getCount() + 1);
        found.setLastSeen(now);
        if (label != null && !label.isBlank()) {
            found.setLabel(label);
        }
        if (refId != null) {
            found.setRefId(refId);
        }
        if (refKind != null) {
            found.setRefKind(refKind);
        }
        if (explicit) {
            found.setExplicit(true);
            found.setSource(ChatMemoryEntity.SOURCE_EXPLICIT);
        }
    }

    double effective(Signal s, Instant now) {
        return decay(s.getWeight(), s.getLastSeen(), now, s.isExplicit());
    }

    double decay(double weight, Instant at, Instant now, boolean explicit) {
        if (at == null || weight <= 0) {
            return Math.max(0, weight);
        }
        double days = Math.max(0, Duration.between(at, now).toMillis() / 86_400_000.0);
        double half = explicit ? config.getExplicitHalfLifeDays() : config.getHalfLifeDays();
        return weight * Math.pow(0.5, days / Math.max(1, half));
    }

    List<Signal> ordered(List<Signal> list, Instant now) {
        List<Signal> out = new ArrayList<>(list);
        out.sort(Comparator.comparing((Signal s) -> !s.isExplicit()).thenComparing(Comparator.comparingDouble((Signal s) -> effective(s, now))
                .reversed()));
        return out;
    }

    private MemoryContext.Pref pref(Signal s, String label, Instant now) {
        return new MemoryContext.Pref(s.getKey(), s.getRefId(), label, effective(s, now), s.getCount(), s.isExplicit(), s.getLastSeen());
    }

    void prune(ChatMemoryEntity m, Instant now) {
        prune(m.getStores(), now, config.getMaxStores());
        prune(m.getCategories(), now, config.getMaxCategories());
        prune(m.getBrands(), now, config.getMaxBrands());
        prune(m.getProducts(), now, config.getMaxProducts());
        prune(m.getPlaces(), now, config.getMaxPlaces());
        prune(m.getHints(), now, config.getMaxHints());
        capNotes(m.getFacts(), config.getMaxFacts());
        capNotes(m.getUnresolved(), config.getMaxUnresolved());
        if (m.getCheapestAt() != null && decay(m.getCheapestWeight(), m.getCheapestAt(), now, false) < PRUNE_BELOW) {
            m.setCheapestCount(0);
            m.setCheapestWeight(0);
            m.setCheapestAt(null);
        }
    }

    private void prune(List<Signal> list, Instant now, int max) {
        list.removeIf(s -> !s.isExplicit() && effective(s, now) < PRUNE_BELOW);
        List<Signal> sorted = ordered(list, now);
        if (sorted.size() > Math.max(1, max)) {
            Set<Signal> keep = new java.util.HashSet<>(sorted.subList(0, Math.max(1, max)));
            list.removeIf(s -> !keep.contains(s));
        }
    }

    private static void capNotes(List<Note> list, int max) {
        list.sort(Comparator.comparing(Note::getAt, Comparator.nullsFirst(Comparator.naturalOrder())));
        while (list.size() > Math.max(1, max)) {
            list.remove(0);
        }
    }

    private Note note(String text, String key, Instant now) {
        Note n = new Note();
        n.setId(shortId());
        n.setText(text);
        n.setKey(key);
        n.setAt(now);
        return n;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private CategoryRef category(String taxonomy, Long id) {
        if (id == null) {
            return null;
        }
        for (CategoryRef c : directory.categories()) {
            if (id.equals(c.id()) && (taxonomy == null || taxonomy.equals(c.taxonomy()))) {
                return c;
            }
        }
        return null;
    }

    private PlaceRef place(String kind, Long id) {
        if (id == null) {
            return null;
        }
        for (PlaceRef p : directory.places()) {
            if (id.equals(p.id()) && (kind == null || kind.equals(p.kind()))) {
                return p;
            }
        }
        return null;
    }

    private static Instant latest(Instant a, Instant b) {
        return a == null ? b : b == null ? a : a.isAfter(b) ? a : b;
    }

    static String money(Double value) {
        if (value == null) {
            return "";
        }
        return value % 1 == 0 ? String.valueOf(value.longValue()) : String.valueOf(value);
    }

    static String json(MemoryContext.Summary summary) {
        try {
            return summary == null ? null : MAPPER.writeValueAsString(summary);
        } catch (Exception e) {
            return null;
        }
    }

    static MemoryContext.Summary summary(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, MemoryContext.Summary.class);
        } catch (Exception e) {
            return null;
        }
    }

    ChatMemoryEntity encode(ChatMemoryEntity m) {
        return transform(m, true);
    }

    ChatMemoryEntity decode(ChatMemoryEntity m) {
        return transform(m, false);
    }

    private ChatMemoryEntity transform(ChatMemoryEntity m, boolean encrypt) {
        ChatMemoryEntity out = new ChatMemoryEntity();
        out.setId(m.getId());
        out.setUserId(m.getUserId());
        out.setPaused(m.isPaused());
        out.setLang(m.getLang());
        out.setStores(copy(m.getStores(), false, encrypt));
        out.setCategories(copy(m.getCategories(), false, encrypt));
        out.setBrands(copy(m.getBrands(), false, encrypt));
        out.setPlaces(copy(m.getPlaces(), false, encrypt));
        out.setProducts(copy(m.getProducts(), true, encrypt));
        out.setHints(copy(m.getHints(), true, encrypt));
        out.setFacts(notes(m.getFacts(), encrypt));
        out.setUnresolved(notes(m.getUnresolved(), encrypt));
        out.setIntents(new ArrayList<>(m.getIntents() == null ? List.of() : m.getIntents()));
        out.setCheapestCount(m.getCheapestCount());
        out.setCheapestWeight(m.getCheapestWeight());
        out.setCheapestAt(m.getCheapestAt());
        out.setBudgetMax(m.getBudgetMax());
        out.setBudgetAt(m.getBudgetAt());
        out.setBudgetExplicit(m.isBudgetExplicit());
        out.setSummary(crypt(m.getSummary(), encrypt));
        out.setPreviousSummary(crypt(m.getPreviousSummary(), encrypt));
        out.setCreatedAt(m.getCreatedAt());
        out.setUpdatedAt(m.getUpdatedAt());
        out.setExpiresAt(m.getExpiresAt());
        return out;
    }

    private List<Signal> copy(List<Signal> list, boolean sensitive, boolean encrypt) {
        List<Signal> out = new ArrayList<>();
        for (Signal s : list == null ? List.<Signal>of() : list) {
            Signal c = s.copy();
            if (sensitive) {
                c.setKey(crypt(c.getKey(), encrypt));
                c.setLabel(crypt(c.getLabel(), encrypt));
            }
            out.add(c);
        }
        return out;
    }

    private List<Note> notes(List<Note> list, boolean encrypt) {
        List<Note> out = new ArrayList<>();
        for (Note n : list == null ? List.<Note>of() : list) {
            Note c = n.copy();
            c.setText(crypt(c.getText(), encrypt));
            c.setKey(crypt(c.getKey(), encrypt));
            out.add(c);
        }
        return out;
    }

    private String crypt(String value, boolean encrypt) {
        if (value == null) {
            return null;
        }
        return encrypt ? cipher.encrypt(value) : cipher.decrypt(value);
    }
}
