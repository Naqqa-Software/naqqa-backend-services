package com.naqqa.chatbot.ai;

import com.naqqa.chatbot.ai.IntentDef.Role;
import com.naqqa.chatbot.ai.knowledge.KnowledgeHit;
import com.naqqa.chatbot.ai.knowledge.KnowledgeService;
import com.naqqa.chatbot.ai.llm.LlmGate;
import com.naqqa.chatbot.ai.llm.LlmProvider;
import com.naqqa.chatbot.ai.llm.LlmRequest;
import com.naqqa.chatbot.ai.llm.LlmResult;
import com.naqqa.chatbot.ai.llm.PromptBuilder;
import com.naqqa.chatbot.ai.retrieval.Candidate;
import com.naqqa.chatbot.ai.retrieval.CardFactory;
import com.naqqa.chatbot.ai.retrieval.CategoryRef;
import com.naqqa.chatbot.ai.retrieval.ChatRetrievalService;
import com.naqqa.chatbot.ai.retrieval.CompanyRef;
import com.naqqa.chatbot.ai.retrieval.PlaceRef;
import com.naqqa.chatbot.ai.retrieval.RankedItem;
import com.naqqa.chatbot.ai.retrieval.Ranker;
import com.naqqa.chatbot.ai.retrieval.RetrievalPlan;
import com.naqqa.chatbot.ai.safety.ChatSafety;
import com.naqqa.chatbot.ai.safety.TopicGuard;
import com.naqqa.chatbot.entities.ChatCard;
import com.naqqa.chatbot.entities.ChatSettingsEntity;
import com.naqqa.chatbot.i18n.ChatLanguages;
import com.naqqa.chatbot.memory.ChatMemoryCommands;
import com.naqqa.chatbot.spi.ChatEntityResolver;
import com.naqqa.chatbot.spi.ChatSearchLinkBuilder;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
public class DefaultChatAiEngine implements ChatAiEngine {

    private static final int LLM_ITEMS = PromptBuilder.MAX_ITEMS;

    private final InputGuard inputGuard;
    private final IntentRouter router;
    private final IntentCatalog catalog;
    private final ChatLanguages languages;
    private final ChatRetrievalService retriever;
    private final Ranker ranker;
    private final CardFactory cardFactory;
    private final KnowledgeService knowledge;
    private final LlmProvider llm;
    private final LlmGate gate;
    private final PromptBuilder prompts;
    private final OutputGuard outputGuard;
    private final ChatEntityResolver directory;
    private final ChatSearchLinkBuilder links;
    private ChatSafety safety;
    private ResponseRouter responseRouter;
    private TopicGuard topicGuard;
    private String operatorQuickReply;
    private final FollowUpResolver followUps;
    private final ChatMemoryCommands memoryPhrases;
    private java.util.function.Supplier<Instant> clock = Instant::now;

    public DefaultChatAiEngine(InputGuard inputGuard, IntentRouter router, ChatRetrievalService retriever, Ranker ranker,
                               CardFactory cardFactory, KnowledgeService knowledge, LlmProvider llm, LlmGate gate,
                               PromptBuilder prompts, OutputGuard outputGuard, ChatEntityResolver directory,
                               ChatSearchLinkBuilder links) {
        this.inputGuard = inputGuard;
        this.router = router;
        this.catalog = router.catalog();
        this.languages = inputGuard.languages();
        this.retriever = retriever;
        this.ranker = ranker;
        this.cardFactory = cardFactory;
        this.knowledge = knowledge;
        this.llm = llm;
        this.gate = gate;
        this.prompts = prompts;
        this.outputGuard = outputGuard;
        this.directory = directory == null ? ChatEntityResolver.NONE : directory;
        this.links = links == null ? ChatSearchLinkBuilder.NONE : links;
        this.responseRouter = new ResponseRouter(languages, null);
        this.followUps = new FollowUpResolver(languages, router);
        this.memoryPhrases = new ChatMemoryCommands(languages);
    }

    public void setTopicGuard(TopicGuard topicGuard) {
        this.topicGuard = topicGuard;
    }

    public void setClock(java.util.function.Supplier<Instant> clock) {
        this.clock = clock == null ? Instant::now : clock;
    }

    public void setResponseRouter(ResponseRouter responseRouter) {
        this.responseRouter = responseRouter == null ? new ResponseRouter(languages, null) : responseRouter;
    }

    public void setSafety(ChatSafety safety, String operatorQuickReply) {
        this.safety = safety;
        this.operatorQuickReply = operatorQuickReply;
    }

    @Override
    public AiReply reply(AiRequest request) {
        return run(request, false);
    }

    @Override
    public AiReply suggest(AiRequest request) {
        return run(request, true);
    }

    @Override
    public int reindexKnowledge() {
        return knowledge.reindex();
    }

    private static final class Outcome {
        private final String text;
        private final List<ChatCard> cards;
        private final double confidence;
        private final boolean escalate;
        private final LlmResult llm;
        private String route = AiReply.ROUTE_TEMPLATE;
        private final List<String> flags = new ArrayList<>();
        private String kind;
        private boolean keepItems;
        private IntentDef intentOverride;

        private Outcome(String text, List<ChatCard> cards, double confidence, boolean escalate, LlmResult llm) {
            this.text = text;
            this.cards = cards;
            this.confidence = confidence;
            this.escalate = escalate;
            this.llm = llm;
        }

        String text() {
            return text;
        }

        List<ChatCard> cards() {
            return cards;
        }

        double confidence() {
            return confidence;
        }

        boolean escalate() {
            return escalate;
        }

        LlmResult llm() {
            return llm;
        }

        Outcome route(String value) {
            this.route = value;
            return this;
        }

        Outcome flag(String value) {
            if (value != null && !flags.contains(value)) {
                flags.add(value);
            }
            return this;
        }

        Outcome flags(List<String> values) {
            for (String v : values) {
                flag(v);
            }
            return this;
        }

        Outcome kind(String value) {
            this.kind = value;
            return this;
        }

        Outcome keepItems() {
            this.keepItems = true;
            return this;
        }

        Outcome as(IntentDef def) {
            this.intentOverride = def;
            return this;
        }

        Outcome prefixed(String prefix) {
            if (prefix == null || prefix.isBlank()) {
                return this;
            }
            Outcome out = new Outcome(prefix + "\n\n" + text, cards, confidence, escalate, llm).route(route).flags(flags);
            out.kind = kind;
            out.keepItems = keepItems;
            out.intentOverride = intentOverride;
            return out;
        }
    }

    private record FollowUp(Outcome outcome, IntentResult intent, IntentRouter.Signals signals, Integer focus) {
    }

    private record Ctx(boolean comparative, boolean followUp, boolean carried, double threshold, boolean operatorDraft,
                       boolean recommendation, IntentRouter.Signals signals, boolean multiPart, Set<String> skip) {

        Ctx(boolean comparative, boolean followUp, boolean carried, double threshold, boolean operatorDraft,
            boolean recommendation, IntentRouter.Signals signals, boolean multiPart) {
            this(comparative, followUp, carried, threshold, operatorDraft, recommendation, signals, multiPart, Set.of());
        }

        boolean special() {
            return comparative || followUp || recommendation || multiPart || signals.hasScenario()
                    || signals.hasComparePair();
        }
    }

    static final int STAGE_CATEGORY = 6;
    static final int STAGE_CLOSEST = 7;
    static final int STAGE_TOKENS = 8;
    private static final int SCENARIO_MAX_TERMS = 8;
    private static final int EXPIRING_DAYS = 3;

    private record LadderResult(List<RankedItem> items, int stage, CategoryRef category) {

        static final LadderResult EMPTY = new LadderResult(List.of(), -1, null);
    }

    private record LlmCall(LlmResult result, boolean wanted, boolean failed) {

        static final LlmCall NONE = new LlmCall(null, false, false);
    }

    private AiReply run(AiRequest request, boolean operatorDraft) {
        if (request.quickReply() != null && catalog.quickReply(request.quickReply()) == null && request.text() != null
                && !request.text().isBlank()) {
            request = new AiRequest(request.conversationId(), request.lang(), request.text(), null, request.pagePath(),
                    request.history(), request.settings(), request.memory());
        }
        ConversationContext previous = operatorDraft ? null : ConversationContext.latest(request.history());
        String carry = previous == null ? null : previous.toJson();
        AiReply out = runTurn(request, operatorDraft, previous);
        if (out.context() == null && out.route() != null && AiReply.ROUTE_GUARD.equals(out.route())) {
            return out.withContext(carry);
        }
        return out;
    }

    private AiReply runTurn(AiRequest request, boolean operatorDraft, ConversationContext previous) {
        long start = System.nanoTime();
        ChatSettingsEntity settings = request.settings() != null ? request.settings() : new ChatSettingsEntity();
        String requestLang = languages.normalize(request.lang());
        String text = request.text();
        if (operatorDraft && (text == null || text.isBlank())) {
            text = lastVisitorText(request.history());
        }
        String masked = PiiMasker.mask(text == null ? "" : text);
        String quickReply = request.quickReply();
        if (safety != null) {
            ChatSafety.Verdict verdict = safety.inspect(masked);
            if (!verdict.none()) {
                String safetyLang = masked.isBlank() ? requestLang : languages.detect(TextNormalizer.clean(masked), requestLang);
                long latency = (System.nanoTime() - start) / 1_000_000L;
                return new AiReply(safety.reply(verdict, safetyLang), List.of(), safety.quickReplies(verdict, operatorQuickReply),
                        verdict.intent(), 1.0, verdict.escalate(), false, 0, 0, latency, true, AiReply.ROUTE_GUARD, List.of(), safetyLang);
            }
        }
        InputGuard.Result guard = inputGuard.inspect(masked, requestLang);
        if (!guard.flagged()) {
            String repaired = router.repair(guard.text());
            if (repaired != null && !repaired.equals(guard.text())) {
                guard = new InputGuard.Result(repaired, languages.detect(repaired, guard.lang()), false, guard.reason());
            }
        }
        String lang = masked.isBlank() ? requestLang : guard.lang();
        IntentDef injection = catalog.first(Role.INJECTION);
        if (guard.flagged()) {
            Outcome o = new Outcome(languages.template("injection", lang), List.of(), 1.0, false, null).route(AiReply.ROUTE_GUARD);
            return reply(o, injection, "injection", start, true, lang, catalog.quickRepliesFor(injection, false), settings);
        }
        IntentDef offTopic = catalog.first(Role.OFF_TOPIC);
        String restricted = quickReply == null && topicGuard != null ? topicGuard.restricted(guard.text()) : null;
        if (restricted != null) {
            Outcome o = new Outcome(languages.template("restricted", lang), List.of(), 1.0, false, null).route(AiReply.ROUTE_GUARD);
            return reply(o, null, "restricted", start, true, lang, catalog.quickRepliesFor(offTopic, false), settings);
        }
        IntentResult intent;
        try {
            intent = router.route(guard.text(), quickReply);
        } catch (RuntimeException e) {
            log.warn("[chatbot] intent routing failed: {}", e.getMessage());
            intent = new IntentResult(catalog.first(Role.SEARCH), 0.5, null, null, guard.text(), false, false, null);
        }
        TopicGuard.Link link = quickReply == null && topicGuard != null ? topicGuard.link(guard.text()) : TopicGuard.Link.NONE;
        if (link != TopicGuard.Link.NONE) {
            if (intent.company() != null) {
                Outcome o = companyLink(intent, lang);
                if (o != null) {
                    return reply(o, intent.intent(), null, start, false, lang, catalog.quickRepliesFor(intent.intent(), true), settings);
                }
            }
            IntentDef def = intent.intent();
            boolean searchLike = def == null || def.role() == Role.SEARCH || def.role() == Role.CATALOG || def.role() == Role.OFF_TOPIC;
            if (link == TopicGuard.Link.STRONG || (searchLike && intent.query() != null && !intent.query().isBlank())) {
                Outcome o = new Outcome(languages.template("external_link", lang), List.of(), 1.0, false, null).route(AiReply.ROUTE_GUARD);
                return reply(o, null, "external_link", start, true, lang, catalog.quickRepliesFor(offTopic, false), settings);
            }
        }
        MemoryContext memory = operatorDraft ? null : request.memory();
        boolean memoryOff = previous != null && previous.memoryDisabled();
        if (quickReply == null && !operatorDraft) {
            ChatMemoryCommands.Found reference = memoryPhrases.reference(guard.text());
            MemoryTurn turn = null;
            if (reference != null) {
                try {
                    turn = memoryReference(reference, previous, memory, request, lang, settings);
                } catch (RuntimeException e) {
                    log.warn("[chatbot] memory reference failed: {}", e.getMessage());
                }
            }
            if (turn != null) {
                ConversationContext next = turn.intent() == null || turn.intent().intent() == null ? previous
                        : context(turn.intent(), IntentRouter.Signals.NONE, turn.outcome(), lang, previous, null);
                if (next != null && (turn.off() || memoryOff)) {
                    next = next.withMemory(false, true);
                }
                IntentDef def = turn.intent() == null ? null : turn.intent().intent();
                List<String> replies = def == null ? List.of() : quickReplies(turn.outcome(), def);
                return reply(turn.outcome(), def, MEMORY_INTENT, start, false, lang, replies, settings)
                        .withContext(next == null ? null : next.toJson());
            }
        }
        if (quickReply == null && previous != null && !operatorDraft) {
            FollowUpResolver.Resolution resolution;
            try {
                resolution = followUps.resolve(guard.text(), previous);
            } catch (RuntimeException e) {
                resolution = FollowUpResolver.Resolution.NONE;
            }
            if (!resolution.none()) {
                FollowUp fu;
                try {
                    fu = followUpOutcome(resolution, previous, guard.text(), lang, request, settings);
                } catch (RuntimeException e) {
                    log.warn("[chatbot] follow-up failed: {}", e.getMessage());
                    fu = null;
                }
                if (fu != null) {
                    ConversationContext next = context(fu.intent(), fu.signals(), fu.outcome(), lang, previous, fu.focus());
                    return reply(fu.outcome(), fu.intent().intent(), null, start, false, lang,
                            quickReplies(fu.outcome(), fu.intent().intent()), settings).withContext(next == null ? null : next.toJson());
                }
            }
        }
        boolean followUp = quickReply == null && responseRouter.followUp(guard.text());
        boolean carried = false;
        String signalText = guard.text();
        if (followUp) {
            String previousText = lastVisitorText(request.history());
            if (previousText != null && !previousText.isBlank() && !previousText.trim().equals(guard.text().trim())) {
                try {
                    String joined = TextNormalizer.clean(PiiMasker.mask(previousText)) + " " + guard.text();
                    IntentResult combined = router.route(joined, null);
                    if (combined.intent() != null && combined.intent().isCatalog()) {
                        intent = combined;
                        carried = true;
                        signalText = joined;
                    }
                } catch (RuntimeException ignored) {
                }
            }
        }
        IntentRouter.Signals signals = IntentRouter.Signals.NONE;
        if (quickReply == null) {
            try {
                signals = router.signals(signalText);
            } catch (RuntimeException e) {
                signals = IntentRouter.Signals.NONE;
            }
        }
        IntentResult withoutPreference = intent;
        boolean preferredPlace = false;
        if (previous != null && quickReply == null && !operatorDraft) {
            if (signals.excluded().isEmpty() && !previous.prefExcluded().isEmpty() && (signals.basket() != null || signals.hasScenario())) {
                signals = signals.withExcluded(previous.prefExcluded());
            }
            IntentDef d = intent.intent();
            if (d != null && intent.place() == null && intent.company() == null && previous.prefPlaceId() != null
                    && (d.role() == Role.SEARCH && intent.query() != null && !intent.query().isBlank() || d.role() == Role.CATALOG
                    && d.types() != null && d.types().defaults() != null && d.types().defaults().contains("PROMOTION"))) {
                PlaceRef place = place(previous.prefPlaceKind(), previous.prefPlaceId());
                if (place != null) {
                    intent = intent.with(place, intent.priceMin(), intent.priceMax(), intent.sortDiscount(), intent.page());
                    preferredPlace = true;
                }
            }
        }
        CompanyRef memoryStore = null;
        IntentResult withoutMemoryStore = intent;
        if (memory != null && quickReply == null && !memoryOff && !preferredPlace && memory.defaultStoreId() != null
                && intent.company() == null && intent.place() == null && defaultStoreApplies(intent, signals, guard.text())) {
            CompanyRef store = directory.company(memory.defaultStoreId());
            if (MemoryPersonalizer.known(store)) {
                intent = withCompany(intent, store);
                memoryStore = store;
            }
        }
        Ctx ctx = new Ctx(quickReply == null && responseRouter.comparative(guard.text()), followUp, carried,
                ResponseRouter.threshold(settings.getLlmConfidenceThreshold()), operatorDraft,
                quickReply == null && responseRouter.recommendation(guard.text()), signals,
                quickReply == null && responseRouter.multiPart(guard.text()));
        Outcome outcome;
        boolean defaulted = false;
        try {
            outcome = handle(intent, guard.text(), lang, request, settings, operatorDraft, ctx);
            if (preferredPlace && (outcome.cards().isEmpty() || outcome.flags.contains(AiReply.FLAG_NO_RESULTS))) {
                intent = withoutPreference;
                outcome = handle(intent, guard.text(), lang, request, settings, operatorDraft, ctx);
            }
            if (memoryStore != null) {
                if (!hasStoreResults(outcome, memoryStore.id())) {
                    intent = withoutMemoryStore;
                    Outcome wide = handle(intent, guard.text(), lang, request, settings, operatorDraft, ctx);
                    outcome = hasResults(wide) ? wide.prefixed(languages.format("memory_default_store_empty", lang,
                            plain(memoryStore.name()), intent.query().trim())) : wide;
                } else {
                    Outcome wide = null;
                    try {
                        wide = handle(withoutMemoryStore, guard.text(), lang, request, settings, operatorDraft, ctx);
                    } catch (RuntimeException ignored) {
                    }
                    outcome = cheaperElsewhere(outcome, wide, memoryStore, lang)
                            .prefixed(languages.format("memory_default_store", lang, plain(memoryStore.name())));
                    defaulted = true;
                }
            }
        } catch (RuntimeException e) {
            log.warn("[chatbot] reply pipeline failed: {}", e.getMessage());
            outcome = new Outcome(languages.template("unknown", lang), List.of(), 0.3, false, null);
        }
        IntentDef replied = outcome.intentOverride != null ? outcome.intentOverride : intent.intent();
        ConversationContext next = operatorDraft ? null : context(intent, ctx.signals(), outcome, lang, previous, null);
        if (next != null && (defaulted || memoryOff)) {
            next = next.withMemory(defaulted, memoryOff);
        }
        List<String> replies = quickReplies(outcome, replied);
        if (defaulted) {
            List<String> withAll = new ArrayList<>();
            withAll.add("mem_all_stores");
            for (String r : replies) {
                if (withAll.size() < 4 && !withAll.contains(r)) {
                    withAll.add(r);
                }
            }
            replies = withAll;
        }
        return reply(outcome, replied, null, start, false, lang, replies, settings)
                .withContext(next == null ? null : next.toJson());
    }

    static final String MEMORY_INTENT = "memory";

    private record MemoryTurn(Outcome outcome, IntentResult intent, boolean off) {
    }

    private static IntentResult withCompany(IntentResult r, CompanyRef company) {
        return new IntentResult(r.intent(), r.confidence(), company, r.category(), r.query(), r.escalate(), r.browse(), r.quickReply())
                .with(r.place(), r.priceMin(), r.priceMax(), r.sortDiscount(), r.page());
    }

    private static boolean hasResults(Outcome outcome) {
        if (outcome == null || outcome.flags.contains(AiReply.FLAG_NO_RESULTS)) {
            return false;
        }
        for (ChatCard c : outcome.cards()) {
            if (!"COMPANY".equals(c.getType()) && !ChatCard.GROUP_RELATED.equals(c.getGroup())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasStoreResults(Outcome outcome, Long storeId) {
        if (outcome == null || outcome.flags.contains(AiReply.FLAG_NO_RESULTS)) {
            return false;
        }
        for (ChatCard c : outcome.cards()) {
            if (!"COMPANY".equals(c.getType()) && !ChatCard.GROUP_RELATED.equals(c.getGroup()) && storeId.equals(c.getCompanyId())) {
                return true;
            }
        }
        return false;
    }

    private boolean defaultStoreApplies(IntentResult intent, IntentRouter.Signals signals, String text) {
        IntentDef d = intent.intent();
        if (d == null || d.role() != Role.SEARCH || intent.query() == null || intent.query().isBlank()) {
            return false;
        }
        if (signals.storeCompare() || signals.basket() != null || signals.hasScenario() || signals.nutrition() != null
                || signals.hasComparePair()) {
            return false;
        }
        try {
            IntentRouter.StoreMatch stores = router.stores(text);
            if (stores != null && !stores.companies().isEmpty()) {
                return false;
            }
        } catch (RuntimeException ignored) {
        }
        return true;
    }

    private Outcome cheaperElsewhere(Outcome outcome, Outcome wide, CompanyRef store, String lang) {
        if (wide == null) {
            return outcome;
        }
        Double storeMin = null;
        int results = 0;
        for (ChatCard c : outcome.cards()) {
            if ("COMPANY".equals(c.getType()) || ChatCard.GROUP_RELATED.equals(c.getGroup())) {
                continue;
            }
            results++;
            if (c.getPrice() != null && c.getPrice() > 0 && (storeMin == null || c.getPrice() < storeMin)) {
                storeMin = c.getPrice();
            }
        }
        ChatCard best = null;
        for (ChatCard c : wide.cards()) {
            if ("COMPANY".equals(c.getType()) || ChatCard.GROUP_RELATED.equals(c.getGroup()) || c.getPrice() == null || c.getPrice() <= 0
                    || c.getCompanyId() == null || store.id().equals(c.getCompanyId())) {
                continue;
            }
            if (best == null || c.getPrice() < best.getPrice()) {
                best = c;
            }
        }
        if (best == null || storeMin == null || best.getPrice() >= storeMin - 0.009 || best.getPath() == null
                || !outputGuard.isInternalPath(best.getPath())) {
            return outcome;
        }
        CompanyRef other = directory.company(best.getCompanyId());
        String company = other != null ? plain(other.name()) : best.getCompany() == null ? null : plain(best.getCompany());
        if (company == null || company.isBlank()) {
            return outcome;
        }
        String line = languages.format("memory_cheaper_elsewhere", lang, plain(best.getTitle()), best.getPath(),
                priceText(best.getPrice(), lang), company);
        List<ChatCard> cards = new ArrayList<>(outcome.cards());
        cards.add(Math.min(results, cards.size()), best.toBuilder().group(ChatCard.GROUP_RESULTS).build());
        Outcome out = new Outcome(outcome.text() + "\n\n" + line, cards, outcome.confidence(), outcome.escalate(), outcome.llm())
                .route(outcome.route).flags(outcome.flags);
        out.kind = outcome.kind;
        out.intentOverride = outcome.intentOverride;
        return out;
    }

    private IntentResult baseIntent(ConversationContext ctx, boolean withCompany) {
        if (ctx == null) {
            return null;
        }
        IntentDef def = previousDef(ctx);
        if (def == null) {
            return null;
        }
        CompanyRef company = withCompany && ctx.companyId() != null ? directory.company(ctx.companyId()) : null;
        PlaceRef place = place(ctx.placeKind(), ctx.placeId());
        CategoryRef category = category(ctx.categoryTaxonomy(), ctx.categoryId());
        String query = ctx.query() == null ? "" : ctx.query();
        if (query.isBlank() && category == null && company == null) {
            return null;
        }
        return new IntentResult(def, 0.9, company, category, query, false, query.isBlank(), null)
                .with(place, ctx.priceMin(), ctx.priceMax(), ctx.minDiscount() != null, null);
    }

    private Ctx plainCtx() {
        return new Ctx(false, false, false, ResponseRouter.DEFAULT_THRESHOLD, false, false, IntentRouter.Signals.NONE, false);
    }

    private String whenLabel(Instant at, String lang) {
        long days = MemoryPersonalizer.daysAgo(at, clock.get());
        if (days <= 0) {
            return languages.template("memory_when_today", lang);
        }
        if (days == 1) {
            return languages.template("memory_when_yesterday", lang);
        }
        return languages.format("memory_when_days", lang, days);
    }

    private MemoryTurn memoryReference(ChatMemoryCommands.Found reference, ConversationContext previous, MemoryContext memory,
                                       AiRequest request, String lang, ChatSettingsEntity settings) {
        MemoryContext.Summary last = memory == null ? null : memory.last();
        ConversationContext lastCtx = last == null ? null : last.parsed();
        switch (reference.reference()) {
            case ALL_STORES -> {
                if (previous == null || !previous.memoryDefaulted()) {
                    return null;
                }
                IntentResult base = baseIntent(previous, false);
                if (base == null) {
                    return null;
                }
                Outcome o = handle(base, base.query(), lang, request, settings, false, plainCtx());
                String label = base.query() == null ? "" : base.query().trim();
                return new MemoryTurn(o.prefixed(label.isEmpty() ? null : languages.format("memory_all_stores", lang, label)), base, true);
            }
            case LAST_ASKED -> {
                List<String> questions = new ArrayList<>(last == null ? List.of() : last.questions());
                Instant at = last == null ? null : last.at();
                if (questions.isEmpty()) {
                    for (AiTurn t : request.history() == null ? List.<AiTurn>of() : request.history()) {
                        if (t != null && "user".equals(t.role()) && t.text() != null && !t.text().isBlank()) {
                            questions.add(t.text().trim());
                        }
                    }
                    while (questions.size() > 3) {
                        questions.remove(0);
                    }
                    at = clock.get();
                }
                if (questions.isEmpty()) {
                    return new MemoryTurn(new Outcome(languages.template("memory_nothing_last", lang), List.of(), 0.9, false, null),
                            null, false);
                }
                StringBuilder sb = new StringBuilder(languages.format("memory_last_asked", lang, whenLabel(at, lang)));
                for (String q : questions) {
                    sb.append("\n- ").append(languages.format("memory_quoted", lang, plain(OutputGuard.cap(q, 120))));
                }
                return new MemoryTurn(new Outcome(sb.toString(), List.of(), 0.9, false, null), null, false);
            }
            case LAST_SHOWN -> {
                boolean fromMemory = last != null && !last.items().isEmpty();
                List<ConversationContext.Item> items = fromMemory ? last.items() : previous == null ? List.of() : previous.items();
                ConversationContext source = fromMemory ? lastCtx : previous;
                Instant at = fromMemory ? last.at() : clock.get();
                List<ChatCard> cards = items.isEmpty() ? List.of() : verified(items);
                IntentResult base = baseIntent(source, true);
                if (!cards.isEmpty()) {
                    Outcome o = new Outcome(languages.format("memory_last_shown", lang, whenLabel(at, lang)), cards, 0.9, false, null)
                            .route(AiReply.ROUTE_SEARCH).kind(ConversationContext.KIND_SEARCH);
                    return new MemoryTurn(o, base, false);
                }
                if (base != null && base.query() != null && !base.query().isBlank()) {
                    Outcome o = handle(base, base.query(), lang, request, settings, false, plainCtx());
                    return new MemoryTurn(o.prefixed(languages.format("memory_last_shown_expired", lang, whenLabel(at, lang),
                            base.query().trim())), base, false);
                }
                return new MemoryTurn(new Outcome(languages.template("memory_nothing_last", lang), List.of(), 0.9, false, null),
                        null, false);
            }
            case LIKE_LAST_TIME, SAME_STORE -> {
                Long storeId = MemoryPersonalizer.storeFor(memory, previous);
                CompanyRef store = storeId == null ? null : directory.company(storeId);
                String residual = reference.residual();
                boolean words = false;
                for (String t : TextNormalizer.tokens(residual)) {
                    words |= !languages.isStopword(t) && t.length() >= 2;
                }
                IntentResult r;
                String text;
                if (words) {
                    r = router.route(residual, null);
                    if (r == null || r.intent() == null || !r.intent().isCatalog()
                            || (r.query() == null || r.query().isBlank()) && r.category() == null) {
                        return null;
                    }
                    if (store != null && r.company() == null) {
                        r = withCompany(r, store);
                    }
                    text = residual;
                } else {
                    ConversationContext base = reference.reference() == ChatMemoryCommands.Reference.LIKE_LAST_TIME
                            ? lastCtx != null ? lastCtx : previous : previous != null ? previous : lastCtx;
                    r = baseIntent(base, true);
                    if (r == null) {
                        if (memory == null && previous == null) {
                            return null;
                        }
                        return new MemoryTurn(new Outcome(languages.template("memory_nothing_last", lang), List.of(), 0.9, false, null),
                                null, false);
                    }
                    if (store != null && (reference.reference() == ChatMemoryCommands.Reference.SAME_STORE || r.company() == null)) {
                        r = withCompany(r, store);
                    }
                    text = r.query();
                }
                Outcome o = handle(r, text, lang, request, settings, false, plainCtx());
                boolean same = reference.reference() == ChatMemoryCommands.Reference.SAME_STORE;
                String prefix = r.company() != null
                        ? languages.format(same ? "memory_same_store" : "memory_like_last_time", lang, plain(r.company().name()))
                        : languages.template("memory_like_last_time_plain", lang);
                return new MemoryTurn(o.prefixed(prefix), r, false);
            }
            default -> {
                return null;
            }
        }
    }

    private List<String> quickReplies(Outcome outcome, IntentDef def) {
        int results = 0;
        for (ChatCard c : outcome.cards()) {
            if (!ChatCard.GROUP_RELATED.equals(c.getGroup()) && !"COMPANY".equals(c.getType())) {
                results++;
            }
        }
        if (outcome.keepItems) {
            return List.of("fu_where", "fu_recipes", "fu_list", "fu_cheaper");
        }
        if (ConversationContext.KIND_BASKET.equals(outcome.kind) || ConversationContext.KIND_SCENARIO.equals(outcome.kind)) {
            return List.of("fu_alternative", "fu_no_meat", "fu_more_people");
        }
        if (ConversationContext.KIND_SEARCH.equals(outcome.kind) && results >= 2) {
            return List.of("fu_cheaper", "fu_compare", "fu_more", "fu_discount_only");
        }
        if (ConversationContext.KIND_COMPARE.equals(outcome.kind) && results >= 1) {
            return List.of("fu_where", "fu_cheaper", "fu_recipes");
        }
        return catalog.quickRepliesFor(def, !outcome.cards().isEmpty());
    }

    private PlaceRef place(String kind, Long id) {
        if (id == null) {
            return null;
        }
        try {
            for (PlaceRef p : directory.places()) {
                if (id.equals(p.id()) && (kind == null || kind.equals(p.kind()))) {
                    return p;
                }
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private CategoryRef category(String taxonomy, Long id) {
        if (id == null) {
            return null;
        }
        try {
            for (CategoryRef c : directory.categories()) {
                if (id.equals(c.id()) && (taxonomy == null || taxonomy.equals(c.taxonomy()))) {
                    return c;
                }
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private ConversationContext context(IntentResult intent, IntentRouter.Signals signals, Outcome outcome, String lang,
                                        ConversationContext previous, Integer focus) {
        if (intent == null) {
            return previous;
        }
        IntentRouter.Signals s = signals == null ? IntentRouter.Signals.NONE : signals;
        IntentDef def = intent.intent();
        String kind = outcome.kind;
        if (kind == null) {
            kind = def != null && def.isCatalog() && !outcome.cards().isEmpty() ? ConversationContext.KIND_SEARCH
                    : def != null && (def.isKnowledge() || def.role() == Role.CONTACT || def.role() == Role.PAGE)
                    ? ConversationContext.KIND_KNOWLEDGE : ConversationContext.KIND_OTHER;
        }
        List<ConversationContext.Item> items = outcome.keepItems && previous != null ? previous.items()
                : ConversationContext.items(outcome.cards());
        PlaceRef place = intent.place();
        String prefPlaceKind = place != null ? place.kind() : previous == null ? null : previous.prefPlaceKind();
        Long prefPlaceId = place != null ? place.id() : previous == null ? null : previous.prefPlaceId();
        boolean planned = ConversationContext.KIND_BASKET.equals(kind) || ConversationContext.KIND_SCENARIO.equals(kind);
        List<String> prefExcluded = planned && !s.excluded().isEmpty() ? s.excluded()
                : previous == null ? List.of() : previous.prefExcluded();
        Integer people = s.basket() != null ? Integer.valueOf(s.basket().people()) : s.people();
        Double budget = intent.priceMin() == null ? intent.priceMax() : null;
        return new ConversationContext(lang, def == null ? null : def.id(), kind, intent.query(),
                intent.company() == null ? null : intent.company().id(), place == null ? null : place.kind(),
                place == null ? null : place.id(), intent.category() == null ? null : intent.category().taxonomy(),
                intent.category() == null ? null : intent.category().id(), intent.priceMin(), planned ? budget : intent.priceMax(),
                s.minDiscount(), s.sort(), s.cheapest() ? Boolean.TRUE : null, people, s.excluded(),
                s.scenario() == null ? null : s.scenario().id(), s.basket() == null ? null : s.basket().period(),
                s.nutrition() == null ? null : s.nutrition().kcal(), items, focus, prefPlaceKind, prefPlaceId, prefExcluded);
    }

    private IntentDef previousDef(ConversationContext previous) {
        IntentDef def = catalog.get(previous.intent());
        if (def != null && def.isCatalog()) {
            return def;
        }
        IntentDef search = catalog.first(Role.SEARCH);
        return search != null ? search : def;
    }

    private static Candidate candidate(ConversationContext.Item item) {
        LocalDate valid = null;
        try {
            valid = item.validTo() == null ? null : LocalDate.parse(item.validTo().length() > 10 ? item.validTo().substring(0, 10) : item.validTo());
        } catch (RuntimeException ignored) {
        }
        return new Candidate(item.type(), item.id(), null, Candidate.titles(item.title(), item.title()), null, item.price(), null,
                item.discount(), item.companyId(), valid, null, null, null, null, null, null, item.path());
    }

    private List<ChatCard> itemCards(List<ConversationContext.Item> items) {
        List<ChatCard> out = new ArrayList<>();
        for (ConversationContext.Item i : items) {
            out.add(ChatCard.builder().type(i.type()).id(i.id()).title(i.title()).price(i.price()).discount(i.discount())
                    .companyId(i.companyId()).company(i.company()).path(i.path()).validTo(i.validTo()).image(i.image())
                    .originalPrice(i.originalPrice()).group(ChatCard.GROUP_RESULTS).build());
        }
        return out;
    }

    private String ordinalLabel(Integer ordinal, String lang) {
        String key = ordinal == null ? "ordinal_1" : ordinal < 0 ? "ordinal_last" : "ordinal_" + ordinal;
        String v = languages.template(key, lang);
        return v.isBlank() ? languages.template("ordinal_1", lang) : v;
    }

    private FollowUp followUpOutcome(FollowUpResolver.Resolution res, ConversationContext prev, String text, String lang,
                                     AiRequest request, ChatSettingsEntity settings) {
        IntentDef def = previousDef(prev);
        if (def == null) {
            return null;
        }
        CompanyRef company = prev.companyId() == null ? null : directory.company(prev.companyId());
        PlaceRef place = place(prev.placeKind(), prev.placeId());
        CategoryRef category = category(prev.categoryTaxonomy(), prev.categoryId());
        String query = prev.query() == null ? "" : prev.query();
        IntentResult base = new IntentResult(def, 0.9, company, category, query, false, query.isBlank(), null)
                .with(place, prev.priceMin(), prev.priceMax(), prev.minDiscount() != null, null);
        switch (res.kind()) {
            case DETAIL, WHERE, LIST -> {
                Integer ordinal = res.ordinal() != null ? res.ordinal() : prev.focus() != null ? prev.focus() : 1;
                ConversationContext.Item item = prev.item(ordinal);
                if (item == null) {
                    return null;
                }
                Outcome o = res.kind() == FollowUpResolver.Kind.DETAIL ? detailOutcome(item, ordinal, lang)
                        : res.kind() == FollowUpResolver.Kind.WHERE ? whereOutcome(item, lang) : listOutcome(item, lang);
                o.kind(prev.kind()).keepItems();
                return new FollowUp(o, base, signalsOf(prev, prev.excluded(), prev.people(), false, Boolean.TRUE.equals(prev.cheapest())), ordinal);
            }
            case COMPARE, BEST -> {
                Outcome o = compareShownOutcome(prev, res.kind() == FollowUpResolver.Kind.BEST, lang);
                o.kind(prev.kind()).keepItems();
                return new FollowUp(o, base, signalsOf(prev, prev.excluded(), prev.people(), false, false), prev.focus());
            }
            case RECIPES -> {
                IntentDef recipes = catalog.get("recipes");
                if (recipes == null) {
                    return null;
                }
                String q = query;
                ConversationContext.Item focused = prev.focused();
                if (q.isBlank() && focused != null && focused.title() != null) {
                    List<String> words = TextNormalizer.tokens(focused.title());
                    q = words.isEmpty() ? "" : words.get(0);
                }
                IntentResult r = new IntentResult(recipes, 0.9, null, null, q, false, q.isBlank(), null);
                Ctx c = new Ctx(false, true, true, ResponseRouter.DEFAULT_THRESHOLD, false, false, IntentRouter.Signals.NONE, false);
                Outcome o = catalogOutcome(r, text, lang, request, settings, false, c);
                return new FollowUp(o.prefixed(q.isBlank() ? null : languages.format("followup_recipes", lang, q)), r,
                        IntentRouter.Signals.NONE, null);
            }
            default -> {
                return modifiedOutcome(res, prev, def, company, place, category, query, text, lang, request, settings);
            }
        }
    }

    private IntentRouter.Signals signalsOf(ConversationContext prev, List<String> excluded, Integer people, boolean alternative,
                                           boolean cheapest) {
        ChatLanguages.Scenario scenario = prev.scenario() == null ? null : languages.scenarios().get(prev.scenario());
        IntentRouter.BasketRequest basket = ConversationContext.KIND_BASKET.equals(prev.kind())
                ? new IntentRouter.BasketRequest(prev.period() == null ? "week" : prev.period(), people == null ? 1 : people) : null;
        return new IntentRouter.Signals(prev.minDiscount(), prev.sort(), scenario, people, List.of(), excluded, alternative, basket, null,
                cheapest, false, ConversationContext.KIND_COMPARE.equals(prev.kind()), false, false, null);
    }

    private FollowUp modifiedOutcome(FollowUpResolver.Resolution res, ConversationContext prev, IntentDef def, CompanyRef company,
                                     PlaceRef place, CategoryRef category, String query, String text, String lang, AiRequest request,
                                     ChatSettingsEntity settings) {
        Double priceMin = prev.priceMin();
        Double priceMax = prev.priceMax();
        Double minDiscount = prev.minDiscount();
        boolean cheapest = Boolean.TRUE.equals(prev.cheapest());
        List<String> excluded = new ArrayList<>(prev.excluded());
        Integer people = prev.people();
        boolean alternative = false;
        Set<String> skip = new HashSet<>();
        String prefix = null;
        String label = query.isBlank() ? category != null ? category.label(lang) : company != null ? plain(company.name()) : "" : query;
        switch (res.kind()) {
            case CHEAPER -> {
                cheapest = true;
                Double first = prev.items().isEmpty() ? null : prev.items().get(0).price();
                if (first != null && first > 0.02) {
                    priceMax = round2(first - 0.01);
                }
                prefix = label.isBlank() ? languages.template("followup_cheaper_generic", lang)
                        : languages.format("followup_cheaper", lang, label);
            }
            case MORE -> {
                for (ConversationContext.Item i : prev.items()) {
                    skip.add(i.key());
                }
                if (cheapest && prev.priceMax() != null) {
                    priceMax = null;
                }
                cheapest = false;
                prefix = label.isBlank() ? languages.template("followup_more_generic", lang)
                        : languages.format("followup_more", lang, label);
            }
            case ALTERNATIVE -> {
                alternative = true;
                prefix = languages.template("followup_alternative", lang);
            }
            case DISCOUNT -> {
                minDiscount = minDiscount == null ? 1.0 : Math.max(1.0, minDiscount);
                prefix = label.isBlank() ? languages.template("followup_discount_generic", lang)
                        : languages.format("followup_discount", lang, label);
            }
            case EXCLUDE -> {
                for (String w : res.excluded()) {
                    if (!excluded.contains(w)) {
                        excluded.add(w);
                    }
                }
                prefix = languages.format("followup_excluded", lang, res.excluded().get(0));
            }
            case PEOPLE -> {
                people = res.people();
                prefix = languages.format("followup_people", lang, people);
            }
            case PRICE -> {
                priceMin = res.current().priceMin();
                priceMax = res.current().priceMax();
                prefix = languages.format("followup_price", lang, priceMax != null ? priceText(priceMax, lang) : priceText(priceMin, lang));
            }
            case STORE -> {
                company = res.current().company();
                place = null;
                if (cheapest && prev.priceMax() != null) {
                    priceMax = null;
                }
                prefix = query.isBlank() ? null : languages.format("followup_store", lang, plain(company.name()), query);
            }
            case PLACE -> {
                place = res.current().place();
                company = null;
                prefix = languages.format("followup_place", lang, place.label(lang), label);
            }
            case SUBJECT -> {
                query = res.residual();
                category = null;
            }
            default -> {
                return null;
            }
        }
        IntentRouter.Signals s = signalsOf(prev, excluded, people, alternative, cheapest);
        s = s.withSort(minDiscount, prev.sort(), cheapest);
        IntentResult r = new IntentResult(def, 0.9, company, category, query, false, query.isBlank(), null)
                .with(place, priceMin, priceMax, minDiscount != null, null);
        Ctx c = new Ctx(false, true, true, ResponseRouter.DEFAULT_THRESHOLD, false, false, s, false, skip);
        Outcome o = null;
        String kind = prev.kind();
        if (ConversationContext.KIND_BASKET.equals(kind)) {
            o = basketOutcome(r, lang, settings, c);
        } else if (ConversationContext.KIND_SCENARIO.equals(kind) && s.scenario() != null) {
            o = scenarioOutcome(r, lang, settings, c);
        } else if (ConversationContext.KIND_COMPARE.equals(kind) && res.kind() != FollowUpResolver.Kind.SUBJECT) {
            o = storesCompareOutcome(r, lang, settings, c);
        }
        if (o == null) {
            o = catalogOutcome(r, text, lang, request, settings, false, c);
            if (res.kind() == FollowUpResolver.Kind.CHEAPER && (o.cards().isEmpty() || lostHead(o.cards(), prev.items(), query, lang))) {
                Double ceiling = prev.priceMax() != null ? prev.priceMax()
                        : prev.items().isEmpty() ? null : prev.items().get(0).price();
                IntentResult open = r.with(place, priceMin, ceiling, minDiscount != null, null);
                o = catalogOutcome(open, text, lang, request, settings, false, c);
                prefix = languages.format("followup_already_cheapest", lang, label);
                r = open;
            }
        }
        if (o.kind == null && !o.cards().isEmpty()) {
            o.kind(ConversationContext.KIND_SEARCH);
        }
        return new FollowUp(o.prefixed(prefix), r, s, null);
    }

    private boolean lostHead(List<ChatCard> cards, List<ConversationContext.Item> previous, String query, String lang) {
        List<String> q = headStems(query == null ? "" : query);
        if (q.isEmpty()) {
            return false;
        }
        boolean before = false;
        for (ConversationContext.Item i : previous) {
            before |= i.price() != null && headRank(i.title(), q) >= 0;
        }
        if (!before) {
            return false;
        }
        for (ChatCard c : cards) {
            if (c.getPrice() != null && headRank(c.getTitle(), q) >= 0) {
                return false;
            }
        }
        return true;
    }

    private Outcome detailOutcome(ConversationContext.Item item, Integer ordinal, String lang) {
        Candidate c = candidate(item);
        String discount = item.discount() != null && item.discount() >= 1 && item.discount() <= 99
                ? languages.format("scenario_discount", lang, Math.round(item.discount())) : "";
        StringBuilder sb = new StringBuilder(languages.format("detail_header", lang, ordinalLabel(ordinal, lang), itemLink(c, lang),
                item.price() == null ? "" : priceText(item.price(), lang), discount));
        sb.append(where(c, lang));
        if (c.validTo() != null) {
            sb.append(languages.format("detail_valid", lang, c.validTo().toString()));
        }
        return new Outcome(sb.toString(), verified(List.of(item)), 0.95, false, null).route(AiReply.ROUTE_SEARCH);
    }

    private List<ChatCard> verified(List<ConversationContext.Item> items) {
        Set<String> allowed = new HashSet<>();
        for (ConversationContext.Item i : items) {
            allowed.add(i.key());
        }
        return outputGuard.verifyCards(itemCards(items), allowed, LocalDate.now());
    }

    private Outcome whereOutcome(ConversationContext.Item item, String lang) {
        Candidate c = candidate(item);
        CompanyRef company = item.companyId() == null ? null : directory.company(item.companyId());
        List<ChatCard> cards = new ArrayList<>(verified(List.of(item)));
        String text;
        if (company == null) {
            text = languages.format("where_unknown", lang, itemLink(c, lang));
        } else {
            text = languages.format("where_header", lang, itemLink(c, lang), plain(company.name()), companyPath(company));
            RankedItem companyItem = companyItem(company);
            if (companyItem != null) {
                ChatCard card = cardFactory.card(companyItem, lang);
                if (card != null) {
                    cards.add(card);
                }
            }
        }
        return new Outcome(text, cards, 0.95, false, null).route(AiReply.ROUTE_SEARCH);
    }

    private Outcome listOutcome(ConversationContext.Item item, String lang) {
        String path = item.path() != null && outputGuard.isInternalPath(item.path()) ? item.path() : "/promotions";
        String text = languages.format("list_add", lang, plain(item.title()), path);
        return new Outcome(text, verified(List.of(item)), 0.95, false, null).route(AiReply.ROUTE_TEMPLATE);
    }

    private Outcome compareShownOutcome(ConversationContext prev, boolean best, String lang) {
        List<ConversationContext.Item> items = prev.items().subList(0, Math.min(3, prev.items().size()));
        StringBuilder sb = new StringBuilder(languages.template("compare_shown_header", lang));
        ConversationContext.Item cheapest = null;
        ConversationContext.Item deepest = null;
        for (ConversationContext.Item i : items) {
            Candidate c = candidate(i);
            String discount = i.discount() != null && i.discount() >= 1 && i.discount() <= 99
                    ? languages.format("scenario_discount", lang, Math.round(i.discount())) : "";
            String store = i.company() != null ? plain(i.company()) : storeName(c);
            sb.append("\n").append(languages.format("compare_shown_line", lang, itemLink(c, lang),
                    i.price() == null ? "—" : priceText(i.price(), lang), discount, store));
            if (i.price() != null && (cheapest == null || i.price() < cheapest.price())) {
                cheapest = i;
            }
            if (i.discount() != null && i.discount() <= 99 && (deepest == null || i.discount() > deepest.discount())) {
                deepest = i;
            }
        }
        sb.append("\n\n");
        if (cheapest != null) {
            sb.append(languages.format("compare_shown_cheapest", lang, plain(cheapest.title()), priceText(cheapest.price(), lang)));
        }
        if (deepest != null && deepest != cheapest && deepest.discount() >= 1) {
            sb.append(languages.format("compare_shown_discount", lang, plain(deepest.title()), Math.round(deepest.discount())));
        }
        if (best) {
            ConversationContext.Item pick = cheapest != null ? cheapest : items.get(0);
            sb.append("\n").append(languages.format("compare_shown_best", lang, plain(pick.title())));
        }
        return new Outcome(sb.toString(), verified(items), 0.9, false, null).route(AiReply.ROUTE_SEARCH);
    }

    private Outcome handle(IntentResult intent, String text, String lang, AiRequest request,
                           ChatSettingsEntity settings, boolean operatorDraft, Ctx ctx) {
        IntentDef def = intent.intent();
        if (def == null) {
            return new Outcome(languages.template("unknown", lang), List.of(), intent.confidence(), false, null);
        }
        if (!operatorDraft && request.quickReply() == null && ctx.signals().nutrition() != null && scenarioApplies(def)) {
            Outcome nutrition = nutritionOutcome(ctx.signals().nutrition(), intent, lang, settings, ctx);
            if (nutrition != null) {
                return nutrition;
            }
        }
        boolean basketForced = ctx.signals().basket() != null && (intent.hasPrice() || ctx.signals().people() != null)
                && def.role() != Role.INJECTION && def.role() != Role.CONTACT;
        if (!operatorDraft && request.quickReply() == null && ctx.signals().basket() != null && (scenarioApplies(def) || basketForced)) {
            Outcome basket = basketOutcome(intent, lang, settings, ctx);
            if (basket != null) {
                return basket;
            }
        }
        if (!operatorDraft && request.quickReply() == null && intent.company() != null && ctx.signals().storeAspect() != null
                && router.aspectOnly(TextNormalizer.normalizedPhrase(text), ctx.signals().storeAspect(), intent.query())) {
            Outcome store = storeAspectOutcome(intent, text, lang, request, settings, ctx);
            if (store != null) {
                IntentDef companyDef = catalog.first(Role.COMPANY);
                return companyDef == null ? store : store.as(companyDef);
            }
        }
        boolean occasion = ctx.signals().hasScenario() && !languages.scenarioCategoryLike().contains(ctx.signals().scenario().id());
        if (!operatorDraft && request.quickReply() == null && ctx.signals().storeCompare() && scenarioApplies(def)
                && !occasion && ctx.signals().basket() == null && !hasSeveralStores(text)) {
            Outcome compared = storesCompareOutcome(intent, lang, settings, ctx);
            if (compared != null) {
                return compared;
            }
        }
        boolean deferred = ctx.signals().hasScenario() && !ctx.recommendation()
                && !scenarioResidual(intent.query(), ctx.signals().scenario()).isBlank();
        if (deferred && !operatorDraft && request.quickReply() == null && scenarioApplies(def)) {
            IntentRouter.Signals plain = ctx.signals().withScenario(null, ctx.signals().basket(), ctx.signals().alternative());
            Ctx withoutScenario = new Ctx(ctx.comparative(), ctx.followUp(), ctx.carried(), ctx.threshold(), ctx.operatorDraft(),
                    ctx.recommendation(), plain, ctx.multiPart(), ctx.skip());
            Outcome normal = handle(intent, text, lang, request, settings, operatorDraft, withoutScenario);
            boolean hasResults = normal.cards().stream().anyMatch(c -> !"COMPANY".equals(c.getType())
                    && !ChatCard.GROUP_RELATED.equals(c.getGroup()));
            if (hasResults) {
                return normal;
            }
            Outcome scenario = scenarioOutcome(intent, lang, settings, ctx);
            return scenario != null ? scenario : normal;
        }
        boolean categoryScenario = ctx.signals().hasScenario() && intent.category() != null
                && languages.scenarioCategoryLike().contains(ctx.signals().scenario().id());
        if (!operatorDraft && request.quickReply() == null && ctx.signals().hasScenario() && scenarioApplies(def) && !categoryScenario
                && !(ctx.recommendation() && responseRouter.allows(ResponseRouter.LlmReason.RECOMMENDATION))) {
            Outcome scenario = scenarioOutcome(intent, lang, settings, ctx);
            if (scenario != null) {
                return scenario;
            }
        }
        if (!operatorDraft && request.quickReply() == null && ctx.comparative() && ctx.signals().hasComparePair()
                && !ctx.signals().storeCompare() && scenarioApplies(def) && !hasSeveralStores(text)
                && !responseRouter.allows(ResponseRouter.LlmReason.COMPARATIVE)) {
            Outcome compared = productCompareOutcome(intent, ctx.signals().comparePair(), lang, settings);
            if (compared != null) {
                return compared;
            }
        }
        boolean signalOnly = ctx.signals().minDiscount() != null || ctx.signals().sort() != null;
        boolean blankQuery = intent.query() == null || intent.query().isBlank();
        IntentDef browseIntent = catalog.priceBrowseIntent();
        if (!operatorDraft && request.quickReply() == null && signalOnly && browseIntent != null
                && (def.role() == Role.OFF_TOPIC || def.role() == Role.GREETING || (def.role() == Role.SEARCH && blankQuery))) {
            IntentResult browse = new IntentResult(browseIntent, Math.max(0.75, intent.confidence()), intent.company(),
                    intent.category(), intent.query() == null ? "" : intent.query(), false, blankQuery, null)
                    .with(intent.place(), intent.priceMin(), intent.priceMax(), true, null);
            return catalogOutcome(browse, text, lang, request, settings, operatorDraft, ctx).as(browseIntent);
        }
        if (!operatorDraft && ctx.recommendation() && !responseRouter.allows(ResponseRouter.LlmReason.RECOMMENDATION)
                && (def.role() == Role.SEARCH || def.role() == Role.OFF_TOPIC)
                && (intent.query() == null || intent.query().isBlank())) {
            Outcome deals = bestDealsOutcome(intent, lang, settings, operatorDraft ? null : request.memory());
            if (deals != null) {
                return deals;
            }
        }
        switch (def.role()) {
            case GREETING -> {
                boolean thanks = TextNormalizer.tokens(text).stream().anyMatch(languages.thanksWords()::contains);
                return new Outcome(languages.template(thanks ? "thanks" : "greeting", lang), List.of(), intent.confidence(), false, null);
            }
            case OFF_TOPIC, INJECTION -> {
                String key = def.role() == Role.INJECTION ? "injection" : intent.confidence() < 0.5 ? "unknown" : "off_topic";
                return new Outcome(languages.template(key, lang), List.of(), intent.confidence(), false, null);
            }
            case CONTACT -> {
                if (intent.escalate()) {
                    return new Outcome(languages.template(def.escalateTemplate() == null ? "operator" : def.escalateTemplate(), lang),
                            List.of(), 1.0, true, null);
                }
                return knowledgeOutcome(intent, text, lang, request, settings, operatorDraft, ctx);
            }
            case KNOWLEDGE, PARTNER -> {
                return knowledgeOutcome(intent, text, lang, request, settings, operatorDraft, ctx);
            }
            case PAGE -> {
                return pageOutcome(intent, text, lang);
            }
            case CATEGORY_LIST -> {
                return new Outcome(categoriesText(def, lang), List.of(), intent.confidence(), false, null);
            }
            case LOCATION -> {
                if (intent.place() == null && (intent.query() == null || intent.query().isBlank()) && def.emptyTemplate() != null) {
                    return new Outcome(languages.template(def.emptyTemplate(), lang), List.of(), intent.confidence(), false, null);
                }
                return catalogOutcome(intent, text, lang, request, settings, operatorDraft, ctx);
            }
            case SEARCH -> {
                if ((intent.query() == null || intent.query().isBlank()) && def.emptyTemplate() != null) {
                    return new Outcome(languages.template(def.emptyTemplate(), lang), List.of(), intent.confidence(), false, null);
                }
                return catalogOutcome(intent, text, lang, request, settings, operatorDraft, ctx);
            }
            default -> {
                if (def.disabledTemplate() != null && !anyAvailable(def)) {
                    return new Outcome(languages.template(def.disabledTemplate(), lang), List.of(), intent.confidence(), false, null);
                }
                return catalogOutcome(intent, text, lang, request, settings, operatorDraft, ctx);
            }
        }
    }

    private boolean anyAvailable(IntentDef def) {
        List<String> types = def.types() == null || def.types().defaults() == null ? List.of() : def.types().defaults();
        for (String t : types) {
            if (retriever.isAvailable(t)) {
                return true;
            }
        }
        return false;
    }

    private ResponseRouter.LlmReason catalogLlmReason(IntentResult intent, Ctx ctx) {
        if (ctx.operatorDraft()) {
            return responseRouter.allowsOperatorDraft() ? ResponseRouter.LlmReason.KNOWLEDGE_SYNTHESIS : null;
        }
        if (!responseRouter.normal() || intent.browse() || intent.quickReply() != null) {
            return null;
        }
        if (ctx.comparative() && responseRouter.allows(ResponseRouter.LlmReason.COMPARATIVE)) {
            return ResponseRouter.LlmReason.COMPARATIVE;
        }
        if (ctx.recommendation() && responseRouter.allows(ResponseRouter.LlmReason.RECOMMENDATION)) {
            return ResponseRouter.LlmReason.RECOMMENDATION;
        }
        if (ctx.followUp() && !ctx.carried() && responseRouter.allows(ResponseRouter.LlmReason.FOLLOW_UP)) {
            return ResponseRouter.LlmReason.FOLLOW_UP;
        }
        if (intent.confidence() < ctx.threshold() && responseRouter.allows(ResponseRouter.LlmReason.LOW_CONFIDENCE)) {
            return ResponseRouter.LlmReason.LOW_CONFIDENCE;
        }
        return null;
    }

    private Outcome catalogOutcome(IntentResult intent, String text, String lang, AiRequest request,
                                   ChatSettingsEntity settings, boolean operatorDraft, Ctx ctx) {
        IntentDef def = intent.intent();
        if (def != null && intent.category() != null && !"booklet_category".equals(intent.category().taxonomy())
                && def.types() != null && List.of("BOOKLET").equals(def.types().defaults())) {
            Outcome booklets = bookletsForCategory(intent, lang, settings);
            if (booklets != null) {
                return booklets;
            }
        }
        int maxCards = settings.getMaxCards() > 0 ? Math.min(settings.getMaxCards(), 10) : 5;
        LocalDate today = LocalDate.now();
        if (intent.company() != null && intent.quickReply() == null && text != null && !text.isBlank()) {
            IntentRouter.StoreMatch stores;
            try {
                stores = router.stores(text);
            } catch (RuntimeException e) {
                stores = null;
            }
            if (stores != null && stores.companies().size() >= 2) {
                return multiStoreOutcome(intent, stores, text, lang, request, settings, operatorDraft, maxCards, today);
            }
        }
        boolean unitMode = intent.quickReply() == null && unitRequested(text) && intent.query() != null
                && !intent.query().isBlank();
        if (unitMode) {
            String cleaned = withoutUnitWords(intent.query(), languages.unitPricePhrases());
            if (!cleaned.isBlank() && !cleaned.equals(intent.query())) {
                intent = new IntentResult(def, intent.confidence(), intent.company(), intent.category(), cleaned, intent.escalate(),
                        intent.browse(), intent.quickReply()).with(intent.place(), intent.priceMin(), intent.priceMax(),
                        intent.sortDiscount(), intent.page());
            }
        }
        RetrievalPlan plan = plan(intent, lang, ctx.signals());
        List<Candidate> candidates = retriever.retrieve(plan);
        Ranker.Options options = Ranker.Options.from(settings, Math.max(maxCards, LLM_ITEMS));
        List<RankedItem> ranked = signalFilter(relevant(ranker.rank(candidates, options, today), intent), ctx.signals(), today);
        if (unitMode) {
            List<RankedItem> wide = new ArrayList<>(ranked);
            Set<String> seenKeys = new HashSet<>();
            for (RankedItem r : ranked) {
                seenKeys.add(r.candidate().key());
            }
            RetrievalPlan more = new RetrievalPlan(plan.intent(), retriever.usable(pricedTypes(), true), intent.query(), lang,
                    plan.companyId(), plan.category(), false, 20, plan.priceMin(), plan.priceMax(), false, plan.place());
            for (RankedItem r : relevantTo(rankSafely(more, Ranker.Options.from(settings, 40), today), intent.query())) {
                if (seenKeys.add(r.candidate().key())) {
                    wide.add(r);
                }
            }
            Outcome unit = unitOutcome(intent, wide, lang, maxCards, today, preferredUnit(text));
            if (unit != null) {
                return unit;
            }
        }
        if (!ctx.skip().isEmpty()) {
            ranked = new ArrayList<>(ranked);
            ranked.removeIf(r -> ctx.skip().contains(r.candidate().key()));
        }
        MemoryContext memory = request == null ? null : request.memory();
        if (memory != null && !operatorDraft && intent.company() == null && intent.quickReply() == null && !ctx.signals().storeCompare()) {
            ranked = MemoryPersonalizer.boost(ranked, memory, maxCards);
        }
        CompanyRef company = intent.company();
        RankedItem companyItem = company == null ? null : companyItem(company);
        LadderResult ladder = LadderResult.EMPTY;
        boolean hasQueryText = intent.query() != null && !intent.query().isBlank();
        if (ranked.isEmpty() && intent.quickReply() == null && hasQueryText && !ctx.carried()
                && (intent.hasPrice() || ctx.signals().minDiscount() != null)) {
            Outcome relaxed = relaxedOutcome(intent, plan, options, lang, today, ctx, maxCards);
            if (relaxed != null) {
                return relaxed;
            }
        }
        if (ranked.isEmpty() && intent.quickReply() == null && company != null && (hasQueryText || intent.category() != null)) {
            Outcome alternatives = alternativesOutcome(intent, plan, options, lang, today, ctx, maxCards);
            if (alternatives != null) {
                return alternatives;
            }
        }
        if (ranked.isEmpty() && intent.quickReply() == null) {
            ladder = ladder(plan, options, today, ctx.signals());
            ranked = ladder.items();
            if (!ctx.skip().isEmpty() && !ranked.isEmpty()) {
                List<RankedItem> fresh = new ArrayList<>(ranked);
                fresh.removeIf(r -> ctx.skip().contains(r.candidate().key()));
                ranked = fresh;
            }
        }
        if (ranked.isEmpty() && hasQueryText && intent.quickReply() == null && def.types() != null && def.types().defaults() != null
                && def.types().defaults().equals(List.of("RECIPE"))) {
            IntentResult browse = new IntentResult(def, intent.confidence(), null, null, "", false, true, null);
            List<RankedItem> recipes = rankSafely(plan(browse, lang, IntentRouter.Signals.NONE), options, today);
            if (!recipes.isEmpty()) {
                List<RankedItem> top = recipes.size() > maxCards ? recipes.subList(0, maxCards) : recipes;
                Set<String> keys = new HashSet<>();
                for (RankedItem r : top) {
                    keys.add(r.candidate().key());
                }
                List<ChatCard> cards = outputGuard.verifyCards(cardFactory.cards(top, lang), keys, today);
                return new Outcome(languages.format("recipes_fallback", lang, intent.query().trim()), cards, 0.6, false, null)
                        .route(AiReply.ROUTE_SEARCH).kind(ConversationContext.KIND_SEARCH);
            }
        }
        if (ranked.isEmpty() && intent.quickReply() == null && company == null && intent.place() != null && hasQueryText
                && (def.role() == Role.SEARCH || def.role() == Role.LOCATION || def.role() == Role.CATALOG)) {
            IntentResult anywhere = new IntentResult(def, intent.confidence(), null, intent.category(), intent.query(), false,
                    intent.browse(), null).with(null, intent.priceMin(), intent.priceMax(), intent.sortDiscount(), null);
            Outcome wider = catalogOutcome(anywhere, text, lang, request, settings, operatorDraft, ctx);
            if (!wider.cards().isEmpty() && !wider.flags.contains(AiReply.FLAG_NO_RESULTS)) {
                return wider.prefixed(languages.format("place_relaxed", lang, intent.query().trim(), intent.place().label(lang)));
            }
        }
        if (ranked.isEmpty() && intent.quickReply() == null && company != null && intent.place() != null) {
            IntentResult anywhere = new IntentResult(def, intent.confidence(), company, intent.category(), intent.query(), false,
                    intent.browse(), null).with(null, intent.priceMin(), intent.priceMax(), intent.sortDiscount(), null);
            Outcome wider = catalogOutcome(anywhere, text, lang, request, settings, operatorDraft, ctx);
            if (!wider.cards().isEmpty() && !wider.flags.contains(AiReply.FLAG_NO_RESULTS)) {
                return wider.prefixed(languages.format("store_not_in_place", lang, plain(company.name()), intent.place().label(lang)));
            }
        }
        if (ranked.isEmpty() && hasQueryText && intent.quickReply() == null && (def.role() == Role.CATEGORY && intent.category() != null
                || def.role() == Role.COMPANY && company != null)) {
            IntentResult browse = new IntentResult(def, intent.confidence(), company, intent.category(), "", false, true, null)
                    .with(intent.place(), intent.priceMin(), intent.priceMax(), intent.sortDiscount(), null);
            Outcome wider = catalogOutcome(browse, text, lang, request, settings, operatorDraft, ctx);
            if (!wider.cards().isEmpty() && !wider.flags.contains(AiReply.FLAG_NO_RESULTS)) {
                return wider.prefixed(languages.format("query_dropped", lang, intent.query().trim()));
            }
        }
        if (ranked.isEmpty() && hasQueryText && intent.quickReply() == null && def.role() == Role.CATALOG && def.browsable()
                && def.emptyResultsTemplate() == null) {
            IntentResult browse = new IntentResult(def, intent.confidence(), intent.company(), intent.category(), "", false, true, null)
                    .with(intent.place(), intent.priceMin(), intent.priceMax(), intent.sortDiscount(), null);
            List<RankedItem> found = signalFilter(rankSafely(plan(browse, lang, ctx.signals()), options, today), ctx.signals(), today);
            if (!found.isEmpty()) {
                List<RankedItem> top = found.size() > maxCards ? found.subList(0, maxCards) : found;
                Set<String> keys = new HashSet<>();
                for (RankedItem r : top) {
                    keys.add(r.candidate().key());
                }
                List<ChatCard> cards = outputGuard.verifyCards(cardFactory.cards(top, lang), keys, today);
                String see = seeAllPath(browse);
                String message = languages.format("catalog_fallback", lang, intent.query().trim())
                        + (see != null && outputGuard.isInternalPath(see) ? "\n\n" + languages.format("see_all", lang, see) : "");
                return new Outcome(message, cards, 0.6, false, null).route(AiReply.ROUTE_SEARCH).kind(ConversationContext.KIND_SEARCH);
            }
        }
        if (ranked.isEmpty()) {
            boolean hasQuery = intent.query() != null && !intent.query().isBlank();
            if (ctx.recommendation() && !operatorDraft && !responseRouter.allows(ResponseRouter.LlmReason.RECOMMENDATION)) {
                Outcome deals = bestDealsOutcome(intent, lang, settings, operatorDraft ? null : request.memory());
                if (deals != null) {
                    return deals;
                }
            }
            LlmCall call = LlmCall.NONE;
            ResponseRouter.LlmReason reason = intent.quickReply() == null ? noResultsLlmReason(intent, ctx, hasQuery, text) : null;
            if (reason != null) {
                List<RankedItem> context = reason == ResponseRouter.LlmReason.RECOMMENDATION
                        ? recommendationItems(lang, settings, today) : List.of();
                call = callLlm(settings, lang, text, intent, request, context, List.of(), operatorDraft);
                Outcome viaLlm = llmCatalogOutcome(call, context, lang, maxCards, today, Math.min(intent.confidence(), 0.35));
                if (viaLlm != null) {
                    return viaLlm.flag(AiReply.FLAG_NO_RESULTS);
                }
            }
            Outcome fallback = noResultsFallback(intent, def, company, companyItem, text, lang, ctx, hasQuery);
            return call.wanted() ? fallback.flag(AiReply.FLAG_LLM_FALLBACK) : fallback;
        }
        boolean priceFocus = (ctx.signals().cheapest() || ctx.signals().priceAsk())
                && (intent.query() != null && !intent.query().isBlank() || intent.category() != null)
                && ranked.stream().anyMatch(r -> r.candidate().price() != null && r.candidate().price() > 0);
        if (priceFocus) {
            ranked = hasQueryText ? headFirst(byPrice(ranked), intent.query(), lang) : byPrice(ranked);
        } else if (hasQueryText && (def.role() == Role.SEARCH || def.role() == Role.CATALOG || def.role() == Role.COMPANY
                || def.role() == Role.LOCATION)) {
            ranked = headFirst(ranked, intent.query(), lang);
        }
        List<RankedItem> top = ranked.size() > maxCards ? ranked.subList(0, maxCards) : ranked;
        Set<String> allowed = new HashSet<>();
        for (RankedItem r : ranked) {
            allowed.add(r.candidate().key());
        }
        if (companyItem != null) {
            allowed.add(companyItem.candidate().key());
        }
        ResponseRouter.LlmReason reason = catalogLlmReason(intent, ctx);
        LlmCall call = reason == null ? LlmCall.NONE : callLlm(settings, lang, text, intent, request, ranked, List.of(), operatorDraft);
        LlmResult llmResult = call.result();
        List<RankedItem> chosen = top;
        String message = null;
        double confidence = intent.confidence();
        boolean escalate = false;
        boolean guardModified = false;
        if (llmResult != null) {
            String clean = outputGuard.sanitize(llmResult.text());
            if (!clean.isBlank()) {
                guardModified = !clean.equals(llmResult.text().trim());
                message = clean;
                confidence = llmResult.confidence();
                escalate = llmResult.escalate();
                if (llmResult.ids() != null && !llmResult.ids().isEmpty()) {
                    Set<String> ids = new HashSet<>(llmResult.ids());
                    List<RankedItem> selected = new ArrayList<>();
                    for (RankedItem r : ranked) {
                        if (ids.contains(r.candidate().key()) && selected.size() < maxCards) {
                            selected.add(r);
                        }
                    }
                    if (!selected.isEmpty()) {
                        chosen = selected;
                    }
                }
            } else {
                llmResult = new LlmResult("", List.of(), 0, false, llmResult.tokensIn(), llmResult.tokensOut());
            }
        }
        chosen = ctx.signals().sort() == null && !priceFocus ? Ranker.displayOrder(chosen) : chosen;
        if (!priceFocus && hasQueryText) {
            chosen = headFirst(chosen, intent.query(), lang);
        }
        PersonalPick pick = null;
        if (memory != null && !operatorDraft && !hasQueryText && company == null && intent.category() == null && intent.place() == null
                && intent.quickReply() == null && (RetrievalPlan.SORT_NEWEST.equals(ctx.signals().sort()) || ctx.recommendation())) {
            pick = personalPick(memory, lang, settings, today, RetrievalPlan.SORT_NEWEST.equals(ctx.signals().sort()));
        }
        if (pick != null) {
            chosen = merged(pick.items(), chosen, maxCards);
            for (RankedItem r : pick.items()) {
                allowed.add(r.candidate().key());
            }
        }
        List<ChatCard> cards = cardFactory.cards(chosen, lang);
        if (companyItem != null && cards.size() < maxCards) {
            ChatCard companyCard = cardFactory.card(companyItem, lang);
            if (companyCard != null) {
                cards.add(companyCard);
            }
        }
        cards = outputGuard.verifyCards(cards, allowed, today);
        if (!cards.isEmpty()) {
            cards.addAll(relatedCards(intent, plan, lang, settings, today, cards));
        }
        boolean usedLlm = message != null;
        if (message == null) {
            message = ladderHeader(ladder, lang);
            if (message == null && priceFocus) {
                message = priceHeader(intent, lang, chosen, ctx.signals());
            }
            if (message == null) {
                message = templateHeader(intent, lang, chosen, ctx.signals());
            }
        }
        if (pick != null) {
            message = pick.header() + "\n\n" + message;
        }
        String seeAll = seeAllPath(intent);
        if (seeAll != null && outputGuard.isInternalPath(seeAll)) {
            message = message + "\n\n" + languages.format("see_all", lang, seeAll);
        }
        if (def.role() == Role.LOCATION) {
            String map = languages.template("see_map", lang);
            if (!map.isBlank()) {
                message = message + (seeAll == null ? "\n\n" : " · ") + map;
            }
        }
        Outcome out = new Outcome(message, cards, confidence, escalate, llmResult)
                .route(usedLlm ? AiReply.ROUTE_LLM : AiReply.ROUTE_SEARCH).kind(ConversationContext.KIND_SEARCH);
        if (guardModified) {
            out.flag(AiReply.FLAG_OUTPUT_GUARD);
        }
        if (call.wanted() && !usedLlm) {
            out.flag(AiReply.FLAG_LLM_FALLBACK);
        }
        return out;
    }

    List<ChatCard> relatedCards(IntentResult intent, RetrievalPlan plan, String lang, ChatSettingsEntity settings,
                                LocalDate today, List<ChatCard> shown) {
        IntentCatalog.RelatedDef related = catalog.related();
        if (related == null || !related.enabled() || intent.browse() || plan == null || plan.types() == null
                || plan.query() == null || plan.query().isBlank()) {
            return List.of();
        }
        boolean trigger = false;
        for (String t : plan.types()) {
            trigger |= related.triggerTypes().contains(t);
        }
        if (!trigger) {
            return List.of();
        }
        List<String> types = new ArrayList<>();
        for (String t : retriever.usable(related.types(), false)) {
            if (retriever.isAvailable(t)) {
                types.add(t);
            }
        }
        if (types.isEmpty()) {
            return List.of();
        }
        RetrievalPlan relatedPlan = new RetrievalPlan(RetrievalPlan.RELATED, types, plan.query(), lang, null, null, false,
                related.max(), null, null, false, null);
        List<Candidate> found;
        try {
            found = retriever.retrieve(relatedPlan);
        } catch (RuntimeException e) {
            return List.of();
        }
        Set<String> seen = new HashSet<>();
        for (ChatCard card : shown == null ? List.<ChatCard>of() : shown) {
            seen.add(card.getType() + ":" + card.getId());
        }
        List<Candidate> organic = new ArrayList<>();
        for (Candidate c : found) {
            if (c != null && types.contains(c.type()) && !seen.contains(c.key())) {
                organic.add(new Candidate(c.type(), c.id(), c.slug(), c.titles(), c.imageId(), null, null, null,
                        c.companyId(), c.validTo(), c.freshnessMillis(), c.score(), false, null, null, null, c.path()));
            }
        }
        Ranker.Options base = Ranker.Options.from(settings, related.max());
        Ranker.Options options = new Ranker.Options(related.max(), 0, 0, base.excludePatterns());
        List<RankedItem> kept = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (RankedItem item : ranker.rank(organic, options, today)) {
            if (item.relevance() >= related.minRelevance()) {
                kept.add(item);
                keys.add(item.candidate().key());
            }
        }
        List<ChatCard> out = new ArrayList<>();
        for (ChatCard card : outputGuard.verifyCards(cardFactory.cards(kept, lang), keys, today)) {
            out.add(card.toBuilder().group(ChatCard.GROUP_RELATED).sponsored(false).build());
        }
        return out;
    }

    private ResponseRouter.LlmReason noResultsLlmReason(IntentResult intent, Ctx ctx, boolean hasQuery, String text) {
        if (ctx.operatorDraft()) {
            return responseRouter.allowsOperatorDraft() ? ResponseRouter.LlmReason.KNOWLEDGE_SYNTHESIS : null;
        }
        if (!responseRouter.normal()) {
            return hasQuery && responseRouter.rareAllowed(text, intent, ctx.threshold(), ctx.special())
                    ? ResponseRouter.LlmReason.NO_RESULTS : null;
        }
        if (ctx.recommendation() && responseRouter.allows(ResponseRouter.LlmReason.RECOMMENDATION)) {
            return ResponseRouter.LlmReason.RECOMMENDATION;
        }
        ResponseRouter.LlmReason reason = catalogLlmReason(intent, ctx);
        if (reason != null) {
            return reason;
        }
        return hasQuery && responseRouter.allows(ResponseRouter.LlmReason.NO_RESULTS) ? ResponseRouter.LlmReason.NO_RESULTS : null;
    }

    private List<RankedItem> recommendationItems(String lang, ChatSettingsEntity settings, LocalDate today) {
        List<String> types = retriever.usable(pricedTypes(), true);
        if (types.isEmpty()) {
            return List.of();
        }
        try {
            List<Candidate> found = retriever.retrieve(new RetrievalPlan(null, types, "", lang, null, null, true, 4,
                    null, null, true, null));
            return ranker.rank(found, Ranker.Options.from(settings, LLM_ITEMS), today);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private List<String> pricedTypes() {
        IntentCatalog.TypeRules defaults = catalog.defaultTypes();
        return defaults != null && defaults.withPrice() != null ? defaults.withPrice() : retriever.typeKeys();
    }

    private Outcome llmCatalogOutcome(LlmCall call, List<RankedItem> items, String lang, int maxCards, LocalDate today,
                                      double fallbackConfidence) {
        Outcome base = llmOutcome(call, fallbackConfidence);
        if (base == null || items.isEmpty()) {
            return base;
        }
        Set<String> ids = new HashSet<>(call.result().ids());
        List<RankedItem> chosen = new ArrayList<>();
        Set<String> allowed = new HashSet<>();
        for (RankedItem item : items) {
            allowed.add(item.candidate().key());
            if (ids.contains(item.candidate().key()) && chosen.size() < maxCards) {
                chosen.add(item);
            }
        }
        if (chosen.isEmpty()) {
            chosen.addAll(items.subList(0, Math.min(maxCards, items.size())));
        }
        List<ChatCard> cards = outputGuard.verifyCards(cardFactory.cards(Ranker.displayOrder(chosen), lang), allowed, today);
        Outcome out = new Outcome(base.text(), cards, base.confidence(), base.escalate(), base.llm()).route(AiReply.ROUTE_LLM);
        return out.flags(base.flags);
    }

    private Outcome noResultsFallback(IntentResult intent, IntentDef def, CompanyRef company, RankedItem companyItem,
                                      String text, String lang, Ctx ctx, boolean hasQuery) {
        if (def.role() == Role.SEARCH && (hasQuery || ctx.recommendation())) {
            if (!ctx.recommendation()) {
                KnowledgeHit hit = relevantKnowledge(text, lang);
                if (hit != null) {
                    return new Outcome(knowledgeExcerpt(hit, lang), List.of(), 0.5, false, null)
                            .route(AiReply.ROUTE_KNOWLEDGE).flag(AiReply.FLAG_NO_RESULTS);
                }
            }
            return clarifyOutcome(intent, lang, ctx.recommendation());
        }
        return emptyOutcome(intent, def, company, companyItem, lang);
    }

    KnowledgeHit relevantKnowledge(String text, String lang) {
        List<KnowledgeHit> hits;
        try {
            hits = knowledge.search(text, lang, null, 3);
        } catch (RuntimeException e) {
            return null;
        }
        if (hits == null || hits.isEmpty()) {
            return null;
        }
        KnowledgeHit best = hits.get(0);
        if (best.score() < 2 || (best.path() != null && catalog.pages().containsValue(best.path()))) {
            return null;
        }
        Set<String> hitStems = new HashSet<>();
        for (String t : TextNormalizer.tokens(best.title() + " " + best.text())) {
            hitStems.add(languages.stem(t));
        }
        int meaningful = 0;
        int overlap = 0;
        Set<String> seen = new HashSet<>();
        for (String t : TextNormalizer.tokens(text)) {
            if (t.length() < 4 || languages.isStopword(t) || !seen.add(languages.stem(t))) {
                continue;
            }
            meaningful++;
            if (hitStems.contains(languages.stem(t))) {
                overlap++;
            }
        }
        return overlap > 0 && overlap * 2 >= meaningful ? best : null;
    }

    private Outcome clarifyOutcome(IntentResult intent, String lang, boolean recommendation) {
        String q = intent.query() == null ? "" : intent.query().trim();
        String message = languages.template(q.isEmpty() || recommendation ? "clarify_generic" : "clarify_search", lang);
        String examples = languages.template("clarify_examples", lang);
        return new Outcome(examples.isBlank() ? message : message + "\n\n" + examples, List.of(),
                Math.min(intent.confidence(), 0.35), false, null)
                .route(AiReply.ROUTE_SEARCH).flag(AiReply.FLAG_NO_RESULTS);
    }

    private Outcome multiStoreOutcome(IntentResult intent, IntentRouter.StoreMatch stores, String text, String lang,
                                      AiRequest request, ChatSettingsEntity settings, boolean operatorDraft, int maxCards,
                                      LocalDate today) {
        List<CompanyRef> companies = stores.companies().size() > 3 ? stores.companies().subList(0, 3) : stores.companies();
        String q = stores.query() == null ? "" : stores.query().trim();
        List<String> types = retriever.usable(pricedTypes(), true);
        Ranker.Options options = Ranker.Options.from(settings, 4);
        java.util.Map<CompanyRef, List<RankedItem>> perStore = new java.util.LinkedHashMap<>();
        for (CompanyRef c : companies) {
            List<RankedItem> items = List.of();
            if (!types.isEmpty()) {
                RetrievalPlan p = new RetrievalPlan(intent.intent() == null ? null : intent.intent().id(), types, q, lang,
                        c.id(), intent.category(), q.isEmpty(), 6, intent.priceMin(), intent.priceMax(),
                        intent.sortDiscount(), null);
                try {
                    items = relevantTo(ranker.rank(retriever.retrieve(p), options, today), q);
                    if (items.isEmpty() && !q.isEmpty()) {
                        items = looseRelevant(rankSafely(p.withRelax(RetrievalPlan.RELAX_OR), options, today), q);
                    }
                } catch (RuntimeException e) {
                    items = List.of();
                }
            }
            List<RankedItem> sorted = new ArrayList<>(headPreferred(items, q, lang));
            sorted.sort(java.util.Comparator.comparingDouble(i -> i.candidate().price() == null ? Double.MAX_VALUE : i.candidate().price()));
            perStore.put(c, sorted);
        }
        List<String> headTerms = headStems(q);
        boolean anyHead = false;
        for (List<RankedItem> items : perStore.values()) {
            for (RankedItem r : items) {
                anyHead |= !headTerms.isEmpty() && headRank(r.candidate().title(lang), headTerms) >= 0;
            }
        }
        if (anyHead) {
            for (java.util.Map.Entry<CompanyRef, List<RankedItem>> e : perStore.entrySet()) {
                List<RankedItem> strict = new ArrayList<>(e.getValue());
                strict.removeIf(r -> headRank(r.candidate().title(lang), headTerms) < 0);
                e.setValue(strict);
            }
        }
        List<RankedItem> interleaved = new ArrayList<>();
        for (int i = 0; interleaved.size() < LLM_ITEMS; i++) {
            boolean any = false;
            for (List<RankedItem> items : perStore.values()) {
                if (i < items.size() && interleaved.size() < LLM_ITEMS) {
                    interleaved.add(items.get(i));
                    any = true;
                }
            }
            if (!any) {
                break;
            }
        }
        LlmCall call = LlmCall.NONE;
        boolean llmAllowed = operatorDraft ? responseRouter.allowsOperatorDraft()
                : responseRouter.allows(ResponseRouter.LlmReason.COMPARATIVE);
        if (!interleaved.isEmpty() && llmAllowed) {
            call = callLlm(settings, lang, text, intent, request, interleaved, List.of(), operatorDraft);
            Outcome viaLlm = llmCatalogOutcome(call, interleaved, lang, maxCards, today, 0.8);
            if (viaLlm != null) {
                if (viaLlm.cards().isEmpty()) {
                    return new Outcome(viaLlm.text(), groupedCards(perStore, lang, maxCards, today), viaLlm.confidence(),
                            viaLlm.escalate(), viaLlm.llm()).route(AiReply.ROUTE_LLM).flags(viaLlm.flags);
                }
                return viaLlm;
            }
        }
        StringBuilder sb = new StringBuilder();
        List<String> names = new ArrayList<>();
        for (CompanyRef c : companies) {
            names.add(c.name());
        }
        sb.append(languages.format("compare_header", lang, String.join(", ", names)));
        RankedItem cheapest = null;
        CompanyRef cheapestStore = null;
        int withItems = 0;
        for (java.util.Map.Entry<CompanyRef, List<RankedItem>> e : perStore.entrySet()) {
            List<RankedItem> items = e.getValue();
            if (items.isEmpty()) {
                sb.append("\n").append(languages.format("compare_none", lang, e.getKey().name()));
                continue;
            }
            withItems++;
            RankedItem best = items.get(0);
            String title = best.candidate().title(lang);
            sb.append("\n").append(languages.format("compare_line", lang, e.getKey().name(), itemLink(best.candidate(), lang),
                    priceText(best.candidate().price(), lang))).append(where(best.candidate(), lang));
            Double price = best.candidate().price();
            if (price != null && (cheapest == null || price < cheapest.candidate().price())) {
                cheapest = best;
                cheapestStore = e.getKey();
            }
        }
        if (withItems < 2 && !q.isEmpty() && !operatorDraft) {
            IntentResult open = new IntentResult(intent.intent(), intent.confidence(), null, intent.category(), q, false, false, null)
                    .with(intent.place(), intent.priceMin(), intent.priceMax(), false, null);
            Ctx c = new Ctx(true, false, false, ResponseRouter.DEFAULT_THRESHOLD, false, false, IntentRouter.Signals.NONE, false);
            Outcome wide = storesCompareOutcome(open, lang, settings, c);
            if (wide != null) {
                List<String> missing = new ArrayList<>();
                for (java.util.Map.Entry<CompanyRef, List<RankedItem>> e : perStore.entrySet()) {
                    if (e.getValue().isEmpty()) {
                        missing.add(plain(e.getKey().name()));
                    }
                }
                return missing.isEmpty() ? wide : wide.prefixed(languages.format("compare_two_missing", lang, String.join(", ", missing), q));
            }
        }
        if (cheapest != null && withItems >= 2) {
            String title = cheapest.candidate().title(lang);
            sb.append("\n\n").append(languages.format("compare_cheapest", lang, title == null ? "" : title,
                    cheapestStore.name(), priceText(cheapest.candidate().price(), lang)));
        }
        Outcome out = new Outcome(sb.toString(), groupedCards(perStore, lang, maxCards, today), withItems > 0 ? 0.8 : 0.4,
                false, call.result()).route(AiReply.ROUTE_SEARCH).kind(ConversationContext.KIND_COMPARE);
        if (withItems == 0) {
            out.flag(AiReply.FLAG_NO_RESULTS);
        }
        return call.wanted() ? out.flag(AiReply.FLAG_LLM_FALLBACK) : out;
    }

    private List<ChatCard> groupedCards(java.util.Map<CompanyRef, List<RankedItem>> perStore, String lang, int maxCards,
                                        LocalDate today) {
        int stores = Math.max(1, perStore.size());
        int perStoreLimit = Math.max(1, (int) Math.ceil(maxCards / (double) stores));
        List<RankedItem> chosen = new ArrayList<>();
        Set<String> allowed = new HashSet<>();
        for (List<RankedItem> items : perStore.values()) {
            for (int i = 0; i < items.size() && i < perStoreLimit && chosen.size() < maxCards; i++) {
                chosen.add(items.get(i));
                allowed.add(items.get(i).candidate().key());
            }
        }
        return outputGuard.verifyCards(cardFactory.cards(chosen, lang), allowed, today);
    }

    private String priceText(Double value, String lang) {
        if (value == null) {
            return "";
        }
        String number = value == Math.rint(value) ? String.valueOf(value.longValue())
                : String.format(java.util.Locale.ROOT, "%.2f", value);
        if (!"en".equals(lang)) {
            number = number.replace('.', ',');
        }
        String formatted = languages.format("price_value", lang, number);
        return formatted == null || formatted.isBlank() ? number : formatted;
    }

    private Outcome companyLink(IntentResult intent, String lang) {
        Candidate c;
        try {
            c = retriever.provider() == null ? null : retriever.provider().companyCandidate(intent.company());
        } catch (RuntimeException e) {
            c = null;
        }
        if (c == null || c.path() == null || !outputGuard.isInternalPath(c.path())) {
            return null;
        }
        String name = intent.company().name().replace("[", "").replace("]", "");
        String text = languages.format("external_link_company", lang, name, name, c.path());
        String promotions = seeAllPath(new IntentResult(intent.intent(), intent.confidence(), intent.company(), null, "", false,
                true, null));
        if (promotions != null && outputGuard.isInternalPath(promotions)) {
            text = text + languages.format("external_link_company_promotions", lang, promotions);
        }
        List<ChatCard> cards = new ArrayList<>();
        ChatCard card = cardFactory.card(new RankedItem(c, 0, 1, false), lang);
        if (card != null) {
            cards.add(card);
        }
        return new Outcome(text, cards, 0.9, false, null).route(AiReply.ROUTE_TEMPLATE);
    }

    private List<RankedItem> relevant(List<RankedItem> ranked, IntentResult intent) {
        String q = intent.query() == null ? "" : intent.query().trim();
        if (q.isEmpty() || intent.browse() || intent.intent() == null || intent.intent().role() != Role.SEARCH) {
            return ranked;
        }
        return relevantTo(ranked, q);
    }

    private List<RankedItem> relevantTo(List<RankedItem> ranked, String q) {
        if (q == null || q.isBlank()) {
            return ranked;
        }
        List<String> terms = new ArrayList<>();
        for (String t : TextNormalizer.tokens(q)) {
            if (t.length() >= 3 && !languages.isStopword(t) && !Character.isDigit(t.charAt(0))) {
                terms.add(languages.stem(t));
            }
        }
        if (router.expander() != null) {
            try {
                for (String v : router.expander().variants(q)) {
                    for (String t : TextNormalizer.tokens(v)) {
                        if (t.length() >= 3 && !languages.isStopword(t)) {
                            terms.add(languages.stem(t));
                        }
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }
        if (terms.isEmpty()) {
            return ranked;
        }
        List<RankedItem> out = new ArrayList<>();
        for (RankedItem item : ranked) {
            StringBuilder text = new StringBuilder();
            for (String title : item.candidate().titles().values()) {
                if (title != null) {
                    text.append(' ').append(title);
                }
            }
            try {
                CompanyRef company = item.candidate().companyId() == null ? null : directory.company(item.candidate().companyId());
                if (company != null) {
                    text.append(' ').append(company.name());
                }
            } catch (RuntimeException ignored) {
            }
            if (matchesAny(text.toString(), terms)) {
                out.add(item);
            }
        }
        return out;
    }

    private boolean matchesAny(String text, List<String> terms) {
        List<String> tokens = new ArrayList<>();
        for (String t : TextNormalizer.tokens(text)) {
            tokens.add(languages.stem(t));
            if (!t.isEmpty() && TextNormalizer.isCyrillic(t.charAt(0))) {
                tokens.add(TextNormalizer.transliterate(t));
            }
        }
        for (String term : terms) {
            String latin = !term.isEmpty() && TextNormalizer.isCyrillic(term.charAt(0)) ? TextNormalizer.transliterate(term) : term;
            for (String token : tokens) {
                for (String candidate : new String[]{term, latin}) {
                    if (token.startsWith(candidate) || (token.length() >= 4 && candidate.startsWith(token))
                            || (candidate.length() >= 5 && token.length() >= 5 && TextNormalizer.levenshtein(candidate, token, 1) <= 1)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private Outcome emptyOutcome(IntentResult intent, IntentDef def, CompanyRef company, RankedItem companyItem, String lang) {
        List<ChatCard> cards = new ArrayList<>();
        String message;
        if (def.emptyResultsTemplate() != null) {
            message = languages.template(def.emptyResultsTemplate(), lang);
        } else if (def.emptyQueryTemplate() != null && intent.query() != null && !intent.query().isBlank()) {
            message = languages.format(def.emptyQueryTemplate(), lang, intent.query());
        } else if (intent.place() != null) {
            message = languages.format("no_results_place", lang, intent.place().label(lang));
        } else if (company != null) {
            message = languages.format("no_results_company", lang, company.name());
            cards.add(cardFactory.card(companyItem, lang));
        } else if (intent.query() != null && !intent.query().isBlank()) {
            message = languages.format("no_results", lang, intent.query());
        } else {
            message = languages.template("no_results_generic", lang);
        }
        cards.removeIf(c -> c == null);
        return new Outcome(message, cards, Math.min(intent.confidence(), 0.35), false, null)
                .route(AiReply.ROUTE_SEARCH).flag(AiReply.FLAG_NO_RESULTS);
    }

    private Outcome llmOutcome(LlmCall call, double fallbackConfidence) {
        LlmResult result = call.result();
        if (result == null) {
            return null;
        }
        String clean = outputGuard.sanitize(result.text());
        if (clean.isBlank()) {
            return null;
        }
        Outcome out = new Outcome(clean, List.of(), result.confidence(), result.escalate(), result).route(AiReply.ROUTE_LLM);
        if (!clean.equals(result.text().trim())) {
            out.flag(AiReply.FLAG_OUTPUT_GUARD);
        }
        return out;
    }

    private Outcome knowledgeOutcome(IntentResult intent, String text, String lang, AiRequest request,
                                     ChatSettingsEntity settings, boolean operatorDraft, Ctx ctx) {
        IntentDef def = intent.intent();
        String preferred = def.knowledgeSource();
        String template = languages.template(def.template() == null ? "unknown" : def.template(), lang);
        if (intent.quickReply() != null || text == null || text.isBlank()) {
            return new Outcome(template, List.of(), intent.confidence(), false, null);
        }
        List<KnowledgeHit> hits;
        try {
            hits = knowledge.search(text, lang, preferred, 3);
        } catch (RuntimeException e) {
            hits = List.of();
        }
        boolean dominant = hits.size() == 1 || (hits.size() > 1 && hits.get(0).score() >= 1.5 * hits.get(1).score());
        boolean synthesis = (hits.size() > 1 && (!dominant || ctx.comparative())) || (ctx.operatorDraft() && !hits.isEmpty());
        LlmCall call = LlmCall.NONE;
        boolean synthesisAllowed = ctx.operatorDraft() ? responseRouter.allowsOperatorDraft()
                : responseRouter.allows(ResponseRouter.LlmReason.KNOWLEDGE_SYNTHESIS);
        if (synthesis && synthesisAllowed) {
            call = callLlm(settings, lang, text, intent, request, List.of(), hits, operatorDraft);
            Outcome viaLlm = llmOutcome(call, intent.confidence());
            if (viaLlm != null) {
                return viaLlm;
            }
        }
        LlmResult llmResult = call.result();
        if (!hits.isEmpty()) {
            KnowledgeHit best = hits.get(0);
            int templateOverlap = overlap(template, text);
            KnowledgeHit closest = null;
            int closestOverlap = 0;
            for (KnowledgeHit h : hits) {
                int o = overlap(h.title() + " " + h.text(), text);
                if (o > closestOverlap) {
                    closest = h;
                    closestOverlap = o;
                }
            }
            if (closest != null && closestOverlap > templateOverlap && closestOverlap >= 2) {
                best = closest;
            }
            if (best == closest && closestOverlap > templateOverlap && closestOverlap >= 2
                    || best.sourceKey() != null && !best.sourceKey().equals(preferred) && specific(best, text, hits.size() == 1 ? 1 : 2)
                    && overlap(best.title() + " " + best.text(), text) > templateOverlap) {
                Outcome out = new Outcome(knowledgeExcerpt(best, lang), List.of(), intent.confidence(), false, llmResult)
                        .route(AiReply.ROUTE_KNOWLEDGE);
                return call.wanted() ? out.flag(AiReply.FLAG_LLM_FALLBACK) : out;
            }
        }
        Outcome out = new Outcome(template, List.of(), intent.confidence(), false, llmResult);
        return call.wanted() ? out.flag(AiReply.FLAG_LLM_FALLBACK) : out;
    }

    private int overlap(String content, String text) {
        Set<String> stems = new HashSet<>();
        for (String t : TextNormalizer.tokens(content)) {
            stems.add(languages.stem(t));
        }
        Set<String> seen = new HashSet<>();
        int n = 0;
        for (String t : TextNormalizer.tokens(text)) {
            String s = languages.stem(t);
            if (t.length() >= 4 && !languages.isStopword(t) && seen.add(s) && stems.contains(s)) {
                n++;
            }
        }
        return n;
    }

    private boolean specific(KnowledgeHit hit, String text, int minOverlap) {
        Set<String> hitStems = new HashSet<>();
        for (String t : TextNormalizer.tokens(hit.title() + " " + hit.text())) {
            hitStems.add(languages.stem(t));
        }
        int overlap = 0;
        for (String t : TextNormalizer.tokens(text)) {
            if (t.length() >= 4 && !languages.isStopword(t) && hitStems.contains(languages.stem(t))) {
                overlap++;
            }
        }
        return overlap >= minOverlap;
    }

    private Outcome pageOutcome(IntentResult intent, String text, String lang) {
        String slug = intent.page() == null ? catalog.defaultPage() : intent.page();
        String path = catalog.pagePath(slug);
        String title = pageTitle(slug, lang);
        List<KnowledgeHit> hits;
        try {
            hits = knowledge.search(text, lang, null, 5);
        } catch (RuntimeException e) {
            hits = List.of();
        }
        for (KnowledgeHit hit : hits) {
            if (path.equals(hit.path()) && hit.text() != null && !hit.text().isBlank()) {
                String body = OutputGuard.cap(hit.text().replace('\n', ' ').replaceAll("\\s{2,}", " ").trim(), 320);
                return new Outcome(body + languages.format("knowledge_link", lang, title, path), List.of(),
                        intent.confidence(), false, null);
            }
        }
        return new Outcome(languages.format("page_link", lang, title, path), List.of(), intent.confidence(), false, null);
    }

    private String pageTitle(String slug, String lang) {
        String key = "page." + slug;
        String v = languages.template(key, lang);
        return v.isEmpty() ? slug : v;
    }

    private String categoriesText(IntentDef def, String lang) {
        List<CategoryRef> all;
        try {
            all = directory.categories();
        } catch (RuntimeException e) {
            all = List.of();
        }
        if (all == null) {
            all = List.of();
        }
        List<String> taxonomies = def.taxonomies() == null || def.taxonomies().isEmpty() ? null : def.taxonomies();
        List<List<CategoryRef>> groups = new ArrayList<>();
        if (taxonomies == null) {
            groups.add(all);
        } else {
            for (String taxonomy : taxonomies) {
                List<CategoryRef> group = new ArrayList<>();
                for (CategoryRef c : all) {
                    if (taxonomy.equals(c.taxonomy())) {
                        group.add(c);
                    }
                }
                groups.add(group);
            }
        }
        int limit = def.maxItems() > 0 ? def.maxItems() : 12;
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (List<CategoryRef> group : groups) {
            for (CategoryRef c : group) {
                if (n >= limit) {
                    break;
                }
                String label = c.label(lang);
                String path = categoryPath(c);
                if (label == null || path == null) {
                    continue;
                }
                sb.append("\n- [").append(label.replace("[", "").replace("]", "")).append("](").append(path).append(")");
                n++;
            }
            if (n > 0) {
                break;
            }
        }
        if (n == 0) {
            return languages.template("categories_empty", lang);
        }
        return languages.template("categories_intro", lang) + sb;
    }

    private String categoryPath(CategoryRef c) {
        if (c == null || c.id() == null) {
            return null;
        }
        try {
            return links.categoryPath(c);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String seeAllPath(IntentResult intent) {
        try {
            return links.seeAll(intent);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private LlmCall callLlm(ChatSettingsEntity settings, String lang, String text, IntentResult intent,
                            AiRequest request, List<RankedItem> items, List<KnowledgeHit> hits,
                            boolean operatorDraft) {
        if (!settings.isLlmEnabled() || llm == null || responseRouter.off()) {
            return LlmCall.NONE;
        }
        try {
            if (!llm.isEnabled()) {
                return LlmCall.NONE;
            }
            if (gate.isOpen() || gate.available() == 0) {
                return new LlmCall(null, true, true);
            }
            if (!llm.isAvailable()) {
                return new LlmCall(null, true, true);
            }
        } catch (RuntimeException e) {
            return new LlmCall(null, true, true);
        }
        if (!gate.tryAcquire()) {
            return new LlmCall(null, true, true);
        }
        try {
            List<RankedItem> forLlm = items.size() > LLM_ITEMS ? items.subList(0, LLM_ITEMS) : items;
            LlmRequest llmRequest = prompts.build(lang, text, intent.key(), request.history(), forLlm, hits,
                    operatorDraft);
            LlmResult result = llm.generate(llmRequest);
            if (result == null) {
                if (llm.lastCallTimedOut()) {
                    gate.recordTimeout();
                }
            } else {
                gate.recordSuccess();
            }
            if (result != null && isUnusableLlmText(result.text(), text)) {
                log.warn("[chatbot] llm reply rejected (echo or too short), search-only reply");
                return new LlmCall(null, true, true);
            }
            return new LlmCall(result, true, result == null);
        } catch (RuntimeException e) {
            if (com.naqqa.chatbot.ai.llm.OllamaLlmProvider.timedOut(e)) {
                gate.recordTimeout();
            }
            log.warn("[chatbot] llm call failed, search-only reply: {}", e.getMessage());
            return new LlmCall(null, true, true);
        } finally {
            gate.release();
        }
    }

    static boolean isUnusableLlmText(String reply, String question) {
        String answer = normalizeForEcho(reply);
        if (answer.length() < 12) {
            return true;
        }
        String asked = normalizeForEcho(question);
        if (asked.isEmpty()) {
            return false;
        }
        if (answer.equals(asked)) {
            return true;
        }
        return answer.contains(asked) && answer.length() <= asked.length() * 1.4 + 10;
    }

    private static String normalizeForEcho(String value) {
        if (value == null) {
            return "";
        }
        String folded = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return folded.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    RetrievalPlan plan(IntentResult intent, String lang) {
        return plan(intent, lang, IntentRouter.Signals.NONE);
    }

    RetrievalPlan plan(IntentResult intent, String lang, IntentRouter.Signals signals) {
        String q = intent.query() == null ? "" : intent.query();
        boolean hasQuery = !q.isBlank();
        Long company = intent.company() == null ? null : intent.company().id();
        IntentRouter.Signals s = signals == null ? IntentRouter.Signals.NONE : signals;
        boolean priced = intent.hasPrice() || s.minDiscount() != null;
        List<String> requested = types(intent, hasQuery, priced, company != null);
        List<String> types = new ArrayList<>(retriever.usable(requested, priced));
        boolean pricedIntent = intent.intent() == null || intent.intent().role() == Role.SEARCH || intent.intent().role() == Role.OFF_TOPIC
                || intent.intent().role() == Role.GREETING;
        for (String t : requested) {
            pricedIntent |= !List.of("BOOKLET", "BLOG", "RECIPE", "RAFFLE", "COMPANY").contains(t);
        }
        if (pricedIntent && (s.minDiscount() != null || s.sort() != null)) {
            for (String t : retriever.usable(pricedTypes(), true)) {
                if (!types.contains(t)) {
                    types.add(t);
                }
            }
        }
        int perType = requested.size() > 4 ? 6 : hasQuery ? 8 : 10;
        return new RetrievalPlan(intent.intent() == null ? null : intent.intent().id(), types, q, lang, company,
                intent.category(), !hasQuery, perType, intent.priceMin(), intent.priceMax(),
                intent.sortDiscount() || s.minDiscount() != null, intent.place(), RetrievalPlan.RELAX_NONE,
                s.minDiscount(), s.sort());
    }

    private boolean scenarioApplies(IntentDef def) {
        if (def.role() == Role.SEARCH || def.role() == Role.OFF_TOPIC || def.role() == Role.GREETING) {
            return true;
        }
        if (def.role() != Role.CATALOG && def.role() != Role.COMPANY && def.role() != Role.LOCATION
                && def.role() != Role.CATEGORY) {
            return false;
        }
        IntentCatalog.TypeRules rules = def.types();
        if (rules == null || rules.isEmpty()) {
            return true;
        }
        List<List<String>> lists = new ArrayList<>();
        lists.add(rules.defaults());
        lists.add(rules.withPrice());
        lists.add(rules.withCompany());
        lists.add(rules.withQuery());
        for (List<String> list : lists) {
            if (list == null) {
                continue;
            }
            for (String t : list) {
                com.naqqa.chatbot.ai.retrieval.ChatItemType type = retriever.type(t);
                if (type != null && type.priced()) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasSeveralStores(String text) {
        try {
            IntentRouter.StoreMatch stores = router.stores(text);
            return stores != null && stores.companies().size() >= 2;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private List<RankedItem> signalFilter(List<RankedItem> items, IntentRouter.Signals signals, LocalDate today) {
        if (signals == null || items.isEmpty()) {
            return items;
        }
        List<RankedItem> out = new ArrayList<>(items);
        if (signals.excluded() != null && !signals.excluded().isEmpty()) {
            out.removeIf(i -> excludedTitle(i.candidate(), signals.excluded()));
        }
        if (signals.minDiscount() != null) {
            double min = signals.minDiscount();
            out.removeIf(i -> i.candidate().discount() == null || i.candidate().discount() < min);
        }
        if (RetrievalPlan.SORT_EXPIRING.equals(signals.sort())) {
            LocalDate limit = today.plusDays(EXPIRING_DAYS);
            List<RankedItem> soon = new ArrayList<>();
            for (RankedItem i : out) {
                if (i.candidate().validTo() != null && !i.candidate().validTo().isAfter(limit)) {
                    soon.add(i);
                }
            }
            if (!soon.isEmpty()) {
                out = soon;
            }
            out.sort(java.util.Comparator.comparing(i -> i.candidate().validTo() == null ? LocalDate.MAX : i.candidate().validTo()));
        } else if (RetrievalPlan.SORT_NEWEST.equals(signals.sort())) {
            out.sort(java.util.Comparator.comparingLong((RankedItem i) -> i.candidate().freshnessMillis() == null ? Long.MIN_VALUE
                    : i.candidate().freshnessMillis()).reversed());
        }
        return out;
    }

    private LadderResult ladder(RetrievalPlan plan, Ranker.Options options, LocalDate today, IntentRouter.Signals signals) {
        String q = plan.query() == null ? "" : plan.query().trim();
        if (q.isEmpty() || plan.related() || plan.types() == null || plan.types().isEmpty()) {
            return LadderResult.EMPTY;
        }
        for (int level : RetrievalPlan.LADDER) {
            List<RankedItem> raw = rankSafely(plan.withRelax(level), options, today);
            boolean trusted = level == RetrievalPlan.RELAX_SYNONYMS && retriever.provider() != null
                    && retriever.provider().supportsRelaxation();
            List<RankedItem> found = signalFilter(trusted ? raw : looseRelevant(raw, q), signals, today);
            if (!found.isEmpty()) {
                return new LadderResult(found, level, null);
            }
        }
        List<String> words = new ArrayList<>();
        for (String t : TextNormalizer.tokens(q)) {
            if (t.length() >= 3 && !languages.isStopword(t) && !Character.isDigit(t.charAt(0)) && words.size() < 3) {
                words.add(t);
            }
        }
        if (words.size() >= 2) {
            List<RankedItem> covering = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (String t : words) {
                RetrievalPlan single = plan.withQuery(t, false).withRelax(RetrievalPlan.RELAX_FUZZY);
                for (RankedItem item : signalFilter(rankSafely(single, options, today), signals, today)) {
                    if (covers(item.candidate(), words) && seen.add(item.candidate().key())) {
                        covering.add(item);
                    }
                }
            }
            if (!covering.isEmpty()) {
                return new LadderResult(covering, STAGE_TOKENS, null);
            }
        }
        if (plan.category() == null) {
            List<CategoryRef> categories;
            try {
                categories = router.categoriesFor(q);
            } catch (RuntimeException e) {
                categories = List.of();
            }
            List<String> priced = retriever.usable(plan.types(), true);
            List<String> types = priced.isEmpty() ? plan.types() : priced;
            int tried = 0;
            for (CategoryRef c : categories) {
                if (tried++ >= 2) {
                    break;
                }
                RetrievalPlan byCategory = new RetrievalPlan(plan.intent(), types, "", plan.lang(), plan.companyId(), c, true,
                        plan.perType(), plan.priceMin(), plan.priceMax(), true, plan.place(), RetrievalPlan.RELAX_NONE,
                        plan.minDiscount(), plan.sort());
                List<RankedItem> found = signalFilter(rankSafely(byCategory, options, today), signals, today);
                if (!found.isEmpty()) {
                    return new LadderResult(found, STAGE_CATEGORY, c);
                }
            }
        }
        List<RankedItem> closest = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        int tokens = 0;
        for (String t : TextNormalizer.tokens(q)) {
            if (t.length() < 4 || languages.isStopword(t) || Character.isDigit(t.charAt(0)) || tokens >= 3) {
                continue;
            }
            tokens++;
            RetrievalPlan single = plan.withQuery(t, false).withRelax(RetrievalPlan.RELAX_FUZZY);
            for (RankedItem item : signalFilter(looseRelevant(rankSafely(single, options, today), t), signals, today)) {
                if (keys.add(item.candidate().key())) {
                    closest.add(item);
                }
            }
        }
        return closest.isEmpty() ? LadderResult.EMPTY : new LadderResult(closest, STAGE_CLOSEST, null);
    }

    private List<RankedItem> rankSafely(RetrievalPlan plan, Ranker.Options options, LocalDate today) {
        try {
            return ranker.rank(retriever.retrieve(plan), options, today);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private boolean covers(Candidate c, List<String> words) {
        StringBuilder text = new StringBuilder();
        for (String title : c.titles().values()) {
            if (title != null) {
                text.append(' ').append(title);
            }
        }
        for (String w : words) {
            if (!matchesLoosely(text.toString(), List.of(w))) {
                return false;
            }
        }
        return true;
    }

    private String ladderHeader(LadderResult ladder, String lang) {
        if (ladder == null) {
            return null;
        }
        if (ladder.stage() == STAGE_CATEGORY && ladder.category() != null) {
            return languages.format("results_category_fallback", lang, ladder.category().label(lang));
        }
        if (ladder.stage() == STAGE_CLOSEST) {
            return languages.template("closest_matches", lang);
        }
        return null;
    }

    List<RankedItem> looseRelevant(List<RankedItem> ranked, String q) {
        if (q == null || q.isBlank() || ranked.isEmpty()) {
            return ranked;
        }
        List<String> terms = new ArrayList<>();
        for (String t : TextNormalizer.tokens(q)) {
            if (t.length() >= 3 && !languages.isStopword(t) && !Character.isDigit(t.charAt(0))) {
                terms.add(t);
            }
        }
        if (router.expander() != null) {
            try {
                for (String v : router.expander().variants(q)) {
                    for (String t : TextNormalizer.tokens(v)) {
                        if (t.length() >= 3 && !languages.isStopword(t) && !Character.isDigit(t.charAt(0))) {
                            terms.add(t);
                        }
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }
        if (terms.isEmpty()) {
            return ranked;
        }
        List<RankedItem> out = new ArrayList<>();
        for (RankedItem item : ranked) {
            StringBuilder text = new StringBuilder();
            for (String title : item.candidate().titles().values()) {
                if (title != null) {
                    text.append(' ').append(title);
                }
            }
            if (matchesLoosely(text.toString(), terms)) {
                out.add(item);
            }
        }
        return out;
    }

    private boolean matchesLoosely(String text, List<String> terms) {
        List<String> tokens = new ArrayList<>();
        for (String t : TextNormalizer.tokens(text)) {
            tokens.add(t);
            if (!t.isEmpty() && TextNormalizer.isCyrillic(t.charAt(0))) {
                tokens.add(TextNormalizer.transliterate(t));
            }
        }
        for (String term : terms) {
            String latin = !term.isEmpty() && TextNormalizer.isCyrillic(term.charAt(0)) ? TextNormalizer.transliterate(term) : term;
            for (String token : tokens) {
                for (String candidate : new String[]{term, latin}) {
                    if (candidate.length() < 3 || token.length() < 3) {
                        continue;
                    }
                    String cs = languages.stem(candidate);
                    String ts = languages.stem(token);
                    if (cs.equals(ts) || (cs.length() >= 4 && token.startsWith(cs)) || (ts.length() >= 4 && cs.startsWith(ts))) {
                        return true;
                    }
                    int max = candidate.length() >= 8 ? 2 : candidate.length() >= 5 ? 1 : 0;
                    if (max > 0 && TextNormalizer.levenshtein(candidate, token, max) <= max) {
                        return true;
                    }
                    if (candidate.length() >= 6 && token.length() >= 6 && candidate.regionMatches(0, token, 0, 4)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private Outcome scenarioOutcome(IntentResult intent, String lang, ChatSettingsEntity settings, Ctx ctx) {
        ChatLanguages.Scenario scenario = ctx.signals().scenario();
        List<String> terms = scenario.terms(lang);
        if (terms.isEmpty()) {
            return null;
        }
        if (terms.size() > SCENARIO_MAX_TERMS) {
            terms = terms.subList(0, SCENARIO_MAX_TERMS);
        }
        List<String> types = retriever.usable(pricedTypes(), true);
        if (types.isEmpty()) {
            return null;
        }
        LocalDate today = LocalDate.now();
        Double budget = intent.priceMin() == null ? intent.priceMax() : null;
        Long company = intent.company() == null ? null : intent.company().id();
        Ranker.Options options = Ranker.Options.from(settings, 6);
        boolean food = !languages.scenarioCategoryLike().contains(scenario.id());
        List<String> wanted = new ArrayList<>();
        for (String term : terms) {
            if (!excludedText(term, ctx.signals().excluded())) {
                wanted.add(term);
            }
        }
        java.util.Map<String, List<RankedItem>> perTerm = inParallel(wanted, term -> {
            RetrievalPlan p = new RetrievalPlan(intent.intent() == null ? null : intent.intent().id(), types, term, lang,
                    company, null, false, 6, null, budget, true, intent.place(), RetrievalPlan.RELAX_NONE,
                    ctx.signals().minDiscount(), null);
            List<RankedItem> items = headOnly(signalFilter(relevantTo(rankSafely(p, options, today), term), ctx.signals(), today),
                    term, lang, food);
            if (items.isEmpty()) {
                items = headOnly(signalFilter(looseRelevant(rankSafely(p.withRelax(RetrievalPlan.RELAX_VARIANTS), options, today),
                        term), ctx.signals(), today), term, lang, food);
            }
            List<RankedItem> priced = new ArrayList<>();
            for (RankedItem i : items) {
                if (i.candidate().price() != null && i.candidate().price() > 0) {
                    priced.add(i);
                }
            }
            if (budget != null) {
                priced.sort(java.util.Comparator.comparingDouble(i -> i.candidate().price()));
            } else {
                priced.sort(java.util.Comparator.<RankedItem>comparingDouble(i -> i.candidate().discount() == null ? -1
                        : i.candidate().discount()).reversed().thenComparingDouble(i -> i.candidate().price()));
            }
            return priced;
        });
        List<String> missing = new ArrayList<>();
        List<String> overBudget = new ArrayList<>();
        java.util.Map<String, RankedItem> picks = new java.util.LinkedHashMap<>();
        double total = 0;
        for (java.util.Map.Entry<String, List<RankedItem>> e : perTerm.entrySet()) {
            if (e.getValue().isEmpty()) {
                missing.add(e.getKey());
                continue;
            }
            RankedItem pick = e.getValue().get(ctx.signals().alternative() && e.getValue().size() > 1 ? 1 : 0);
            double price = pick.candidate().price();
            if (budget != null && total + price > budget + 0.001) {
                overBudget.add(e.getKey());
                continue;
            }
            picks.put(e.getKey(), pick);
            total += price;
        }
        if (picks.isEmpty()) {
            return null;
        }
        String label = scenario.label(lang);
        StringBuilder sb = new StringBuilder(languages.format("scenario_header", lang, label));
        if (ctx.signals().people() != null) {
            sb.append(languages.format("scenario_people", lang, ctx.signals().people()));
        }
        for (java.util.Map.Entry<String, RankedItem> e : picks.entrySet()) {
            Candidate c = e.getValue().candidate();
            String title = c.title(lang);
            String discount = c.discount() != null && c.discount() >= 1 && c.discount() <= 99
                    ? languages.format("scenario_discount", lang, Math.round(c.discount())) : "";
            sb.append("\n").append(languages.format("scenario_line", lang, e.getKey(), itemLink(c, lang),
                    priceText(c.price(), lang), discount)).append(whereStore(c, lang));
        }
        if (budget != null) {
            sb.append("\n\n").append(languages.format("scenario_total", lang, priceText(round2(total), lang), priceText(budget, lang)));
            if (!overBudget.isEmpty()) {
                sb.append(" ").append(languages.format("scenario_over_budget", lang, String.join(", ", overBudget)));
            }
        } else {
            sb.append("\n\n").append(languages.format("scenario_total_plain", lang, priceText(round2(total), lang)));
        }
        if (ctx.signals().people() != null && ctx.signals().people() > 1) {
            sb.append(" ").append(languages.format("scenario_people_note", lang, ctx.signals().people()));
        }
        String mapLink = mapLink(lang);
        if (!mapLink.isBlank()) {
            sb.append(" ").append(mapLink);
        }
        if (!missing.isEmpty()) {
            sb.append("\n").append(languages.format("scenario_missing", lang, String.join(", ", missing)));
        }
        int maxCards = Math.min(10, Math.max(settings.getMaxCards() > 0 ? settings.getMaxCards() : 5, picks.size()));
        List<RankedItem> chosen = new ArrayList<>(picks.values());
        Set<String> allowed = new HashSet<>();
        for (RankedItem r : chosen) {
            allowed.add(r.candidate().key());
        }
        for (List<RankedItem> items : perTerm.values()) {
            if (chosen.size() >= maxCards) {
                break;
            }
            for (RankedItem r : items) {
                if (chosen.size() < maxCards && allowed.add(r.candidate().key())) {
                    chosen.add(r);
                    break;
                }
            }
        }
        List<ChatCard> cards = outputGuard.verifyCards(cardFactory.cards(chosen, lang), allowed, today);
        return new Outcome(sb.toString(), cards, 0.85, false, null).route(AiReply.ROUTE_SEARCH).kind(ConversationContext.KIND_SCENARIO);
    }

    private String aspectResidual(String query, String aspect) {
        if (query == null || query.isBlank()) {
            return "";
        }
        Set<String> words = new HashSet<>();
        for (String p : languages.storeAspects().getOrDefault(aspect, List.of())) {
            words.addAll(TextNormalizer.tokens(p));
        }
        List<String> out = new ArrayList<>();
        for (String t : TextNormalizer.tokens(query)) {
            if (!words.contains(t) && !languages.isStopword(t)) {
                out.add(t);
            }
        }
        return String.join(" ", out);
    }

    private List<String> headStems(String query) {
        List<String> q = new ArrayList<>();
        for (String t : TextNormalizer.tokens(query)) {
            if (t.length() >= 2 && !languages.isStopword(t) && !Character.isDigit(t.charAt(0))) {
                q.add(languages.stem(t));
            }
        }
        return q;
    }

    private static boolean sameWord(String stem, String s) {
        return stem.length() >= 2 && (stem.startsWith(s) && stem.length() <= s.length() + 1
                || s.startsWith(stem) && stem.length() >= s.length() - 1);
    }

    private volatile Set<String> typeStems;

    private Set<String> typeStems() {
        Set<String> out = typeStems;
        if (out == null) {
            List<String> terms = new ArrayList<>();
            for (ChatLanguages.Scenario s : languages.scenarios().values()) {
                for (List<String> list : s.terms().values()) {
                    terms.addAll(list);
                }
            }
            for (List<ChatLanguages.BasketItem> list : languages.basket().items().values()) {
                for (ChatLanguages.BasketItem item : list) {
                    terms.add(item.term());
                }
            }
            out = new HashSet<>();
            for (String term : terms) {
                List<String> tokens = TextNormalizer.tokens(term);
                if (tokens.size() == 1 && tokens.get(0).length() >= 2) {
                    out.add(languages.stem(tokens.get(0)));
                }
            }
            typeStems = out;
        }
        return out;
    }

    private boolean otherType(String stem, List<String> q) {
        for (String s : q) {
            if (sameWord(stem, s)) {
                return false;
            }
        }
        for (String type : typeStems()) {
            if (sameType(stem, type)) {
                return true;
            }
        }
        return false;
    }

    static boolean sameType(String stem, String type) {
        if (stem.length() < 4 || type.length() < 4) {
            return stem.equals(type);
        }
        return sameWord(stem, type);
    }

    private int headRank(String title, List<String> q) {
        List<String> tokens = TextNormalizer.tokens(title == null ? "" : title);
        for (int i = 0; i < Math.min(3, tokens.size()); i++) {
            if (languages.isHeadBreak(tokens.get(i))) {
                return -1;
            }
            String stem = languages.stem(tokens.get(i));
            for (String s : q) {
                if (sameWord(stem, s)) {
                    return i == 0 ? 0 : 1;
                }
            }
            if (otherType(stem, q)) {
                return -1;
            }
        }
        return -1;
    }

    private boolean hasAll(String title, List<String> q) {
        List<String> stems = new ArrayList<>();
        for (String t : TextNormalizer.tokens(title == null ? "" : title)) {
            stems.add(languages.stem(t));
        }
        for (String s : q) {
            boolean found = false;
            for (String stem : stems) {
                found |= stem.startsWith(s) || s.length() >= 3 && s.startsWith(stem) && stem.length() >= s.length() - 1;
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    private List<RankedItem> headFirst(List<RankedItem> items, String query, String lang) {
        List<String> q = headStems(query);
        if (q.isEmpty() || items.size() < 2) {
            return items;
        }
        List<RankedItem> head = new ArrayList<>();
        List<RankedItem> rest = new ArrayList<>();
        for (RankedItem r : items) {
            int rank = headRank(r.candidate().title(lang), q);
            (rank >= 0 ? head : rest).add(r);
        }
        if (head.size() >= 3) {
            rest.removeIf(r -> r.candidate().price() != null);
        } else if (!head.isEmpty() || rest.stream().anyMatch(r -> mentionsAsMain(r.candidate().title(lang), q))) {
            // A title that only mentions the term as a flavour/filling ("Iaurt cu banane") is not a match for "banane".
            rest.removeIf(r -> r.candidate().price() != null && !mentionsAsMain(r.candidate().title(lang), q));
        }
        head.addAll(rest);
        return head;
    }

    /** Like {@link #mentionsAny} but ignores mentions that follow a head-break word (cu, aroma, gust, din...). */
    private boolean mentionsAsMain(String title, List<String> q) {
        String previous = null;
        for (String t : TextNormalizer.tokens(title == null ? "" : title)) {
            String stem = languages.stem(t);
            boolean flavour = previous != null && languages.isHeadBreak(previous);
            if (!flavour) {
                for (String s : q) {
                    if (sameWord(stem, s)) {
                        return true;
                    }
                }
            }
            previous = t;
        }
        return false;
    }

    private boolean mentionsAny(String title, List<String> q) {
        for (String t : TextNormalizer.tokens(title == null ? "" : title)) {
            String stem = languages.stem(t);
            for (String s : q) {
                if (sameWord(stem, s)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final Set<String> UNIT_WORDS = Set.of("kg", "kilogram", "kilogramul", "kilogramului", "kilo", "litru", "litrul",
            "litri", "litrului", "l", "buc", "bucata", "кг", "килограмм", "килограмма", "кило", "литр", "литра", "литре", "шт",
            "штуку", "штука", "liter", "litre", "piece");

    private boolean unitRequested(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String phrase = " " + TextNormalizer.normalizedPhrase(text).trim() + " ";
        for (String p : languages.unitPricePhrases()) {
            if (phrase.contains(" " + p.trim() + " ")) {
                return true;
            }
        }
        return false;
    }

    private static String withoutUnitWords(String query, List<String> phrases) {
        Set<String> drop = new HashSet<>(UNIT_WORDS);
        for (String p : phrases) {
            drop.addAll(TextNormalizer.tokens(p));
        }
        List<String> out = new ArrayList<>();
        for (String t : query.trim().split("\\s+")) {
            String folded = TextNormalizer.fold(t).toLowerCase(java.util.Locale.ROOT);
            if (!drop.contains(folded) && !drop.contains(t.toLowerCase(java.util.Locale.ROOT))) {
                out.add(t);
            }
        }
        return String.join(" ", out).trim();
    }

    private static String preferredUnit(String text) {
        List<String> tokens = TextNormalizer.tokens(text == null ? "" : text);
        for (String t : tokens) {
            if (List.of("litru", "litrul", "litri", "liter", "litre", "литр", "литра", "литре").contains(t)) {
                return UnitPrice.L;
            }
        }
        for (String t : tokens) {
            if (List.of("kg", "kilogram", "kilogramul", "kilo", "кг", "килограмм", "килограмма").contains(t)) {
                return UnitPrice.KG;
            }
        }
        return null;
    }

    private Outcome unitOutcome(IntentResult intent, List<RankedItem> ranked, String lang, int maxCards, LocalDate today,
                                String preferred) {
        List<RankedItem> candidates = new ArrayList<>(headPreferred(ranked, intent.query(), lang));
        candidates.removeIf(r -> r.candidate().price() == null || languages.foreignTo(r.candidate().title(lang), intent.query()));
        java.util.Map<String, Integer> units = new java.util.HashMap<>();
        java.util.Map<String, UnitPrice.Size> sizes = new java.util.HashMap<>();
        for (RankedItem r : candidates) {
            UnitPrice.Size size = UnitPrice.size(r.candidate().title(lang));
            if (size == null) {
                size = UnitPrice.size(r.candidate().title("ro"));
            }
            if (size != null && UnitPrice.perUnit(r.candidate().price(), size) != null) {
                sizes.put(r.candidate().key(), size);
                units.merge(size.unit(), 1, Integer::sum);
            }
        }
        String unit = null;
        for (java.util.Map.Entry<String, Integer> e : units.entrySet()) {
            if (unit == null || e.getValue() > units.get(unit)) {
                unit = e.getKey();
            }
        }
        if (preferred != null && units.getOrDefault(preferred, 0) >= 2) {
            unit = preferred;
        }
        if (unit == null || units.get(unit) < 2) {
            return null;
        }
        String chosenUnit = unit;
        List<RankedItem> measured = new ArrayList<>();
        for (RankedItem r : candidates) {
            UnitPrice.Size s = sizes.get(r.candidate().key());
            if (s != null && s.unit().equals(chosenUnit)) {
                measured.add(r);
            }
        }
        measured.sort(java.util.Comparator.comparingDouble(r -> UnitPrice.perUnit(r.candidate().price(), sizes.get(r.candidate().key()))));
        List<RankedItem> top = measured.size() > maxCards ? measured.subList(0, maxCards) : measured;
        String unitLabel = languages.template("unit_" + chosenUnit, lang);
        StringBuilder sb = new StringBuilder(languages.format("unit_header", lang, intent.query().trim(), unitLabel));
        for (RankedItem r : top) {
            Candidate c = r.candidate();
            Double per = UnitPrice.perUnit(c.price(), sizes.get(c.key()));
            sb.append("\n").append(languages.format("unit_line", lang, itemLink(c, lang), priceText(c.price(), lang),
                    priceText(per, lang), unitLabel)).append(whereStore(c, lang));
        }
        Candidate best = top.get(0).candidate();
        Candidate worst = top.get(top.size() - 1).candidate();
        Double bestPer = UnitPrice.perUnit(best.price(), sizes.get(best.key()));
        Double worstPer = UnitPrice.perUnit(worst.price(), sizes.get(worst.key()));
        if (top.size() > 1 && bestPer != null && worstPer != null && worstPer > bestPer) {
            long percent = Math.round((worstPer - bestPer) * 100.0 / worstPer);
            sb.append("\n\n").append(languages.format("unit_saving", lang, percent, unitLabel));
        }
        sb.append("\n").append(languages.template("unit_note", lang));
        Set<String> allowed = new HashSet<>();
        for (RankedItem r : top) {
            allowed.add(r.candidate().key());
        }
        List<ChatCard> cards = outputGuard.verifyCards(cardFactory.cards(top, lang), allowed, today);
        if (cards.isEmpty()) {
            return null;
        }
        return new Outcome(sb.toString(), cards, 0.85, false, null).route(AiReply.ROUTE_SEARCH).kind(ConversationContext.KIND_SEARCH);
    }

    private static final java.util.concurrent.ExecutorService TERM_POOL = java.util.concurrent.Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "naqqa-chat-terms");
        t.setDaemon(true);
        return t;
    });

    private static <V> java.util.Map<String, V> inParallel(List<String> keys, java.util.function.Function<String, V> work) {
        java.util.Map<String, java.util.concurrent.CompletableFuture<V>> futures = new java.util.LinkedHashMap<>();
        for (String key : keys) {
            if (!futures.containsKey(key)) {
                futures.put(key, java.util.concurrent.CompletableFuture.supplyAsync(() -> work.apply(key), TERM_POOL));
            }
        }
        java.util.Map<String, V> out = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, java.util.concurrent.CompletableFuture<V>> e : futures.entrySet()) {
            try {
                V value = e.getValue().get(15, java.util.concurrent.TimeUnit.SECONDS);
                if (value != null) {
                    out.put(e.getKey(), value);
                }
            } catch (Exception ex) {
                e.getValue().cancel(true);
            }
        }
        return out;
    }

    private List<RankedItem> headPreferred(List<RankedItem> items, String query, String lang) {
        List<String> q = headStems(query == null ? "" : query);
        if (q.isEmpty() || items.isEmpty()) {
            return items;
        }
        List<RankedItem> head = new ArrayList<>();
        for (RankedItem r : items) {
            if (headRank(r.candidate().title(lang), q) >= 0) {
                head.add(r);
            }
        }
        return head.isEmpty() ? items : head;
    }

    private Outcome relaxedOutcome(IntentResult intent, RetrievalPlan plan, Ranker.Options options, String lang, LocalDate today,
                                   Ctx ctx, int maxCards) {
        String q = intent.query().trim();
        RetrievalPlan open = new RetrievalPlan(plan.intent(), plan.types(), q, lang, plan.companyId(), plan.category(), false,
                plan.perType(), null, null, false, plan.place(), RetrievalPlan.RELAX_NONE, null, plan.sort());
        IntentRouter.Signals loose = ctx.signals().withSort(null, ctx.signals().sort(), ctx.signals().cheapest());
        List<RankedItem> items = signalFilter(relevantTo(rankSafely(open, options, today), q), loose, today);
        List<RankedItem> priced = new ArrayList<>();
        for (RankedItem r : items) {
            if (r.candidate().price() != null && r.candidate().price() > 0) {
                priced.add(r);
            }
        }
        if (priced.isEmpty()) {
            return null;
        }
        String message;
        if (intent.hasPrice()) {
            priced = byPrice(priced);
            String constraint = intent.priceMin() != null && intent.priceMax() != null
                    ? languages.format("constraint_range", lang, priceText(intent.priceMin(), lang), priceText(intent.priceMax(), lang))
                    : intent.priceMax() != null ? languages.format("constraint_max", lang, priceText(intent.priceMax(), lang))
                    : languages.format("constraint_min", lang, priceText(intent.priceMin(), lang));
            Candidate first = priced.get(0).candidate();
            message = languages.format("price_relaxed", lang, q, constraint, priceText(first.price(), lang));
        } else {
            priced.sort(java.util.Comparator.comparingDouble((RankedItem r) -> r.candidate().discount() == null ? -1
                    : r.candidate().discount()).reversed());
            Double top = priced.get(0).candidate().discount();
            message = languages.format("discount_relaxed", lang, Math.round(ctx.signals().minDiscount()), q,
                    top == null ? 0 : Math.round(top));
        }
        List<RankedItem> chosen = priced.size() > maxCards ? priced.subList(0, maxCards) : priced;
        Set<String> allowed = new HashSet<>();
        for (RankedItem r : chosen) {
            allowed.add(r.candidate().key());
        }
        List<ChatCard> cards = outputGuard.verifyCards(cardFactory.cards(chosen, lang), allowed, today);
        if (cards.isEmpty()) {
            return null;
        }
        return new Outcome(message, cards, 0.75, false, null).route(AiReply.ROUTE_SEARCH).kind(ConversationContext.KIND_SEARCH);
    }

    private String scenarioResidual(String query, ChatLanguages.Scenario scenario) {
        if (query == null || query.isBlank() || scenario == null) {
            return "";
        }
        Set<String> words = new HashSet<>();
        for (String t : scenario.triggers()) {
            words.addAll(TextNormalizer.tokens(t));
        }
        for (ChatLanguages.ContextRule rule : scenario.context()) {
            words.add(rule.token());
            words.addAll(rule.before());
            words.addAll(rule.with());
        }
        words.addAll(languages.peopleWords());
        for (String p : languages.familyPhrases()) {
            words.addAll(TextNormalizer.tokens(p));
        }
        for (String p : languages.basket().triggers()) {
            words.addAll(TextNormalizer.tokens(p));
        }
        for (String p : languages.nutrition().triggers()) {
            words.addAll(TextNormalizer.tokens(p));
        }
        List<String> out = new ArrayList<>();
        for (String t : TextNormalizer.tokens(query)) {
            if (t.length() >= 3 && !words.contains(t) && !languages.isStopword(t) && !Character.isDigit(t.charAt(0))) {
                out.add(t);
            }
        }
        return String.join(" ", out);
    }

    private Outcome bookletsForCategory(IntentResult intent, String lang, ChatSettingsEntity settings) {
        List<String> priced = retriever.usable(pricedTypes(), true);
        if (priced.isEmpty()) {
            return null;
        }
        LocalDate today = LocalDate.now();
        RetrievalPlan products = new RetrievalPlan(intent.intent().id(), priced, "", lang, null, intent.category(), true, 30,
                null, null, true, intent.place());
        java.util.Map<Long, Integer> stores = new java.util.LinkedHashMap<>();
        for (RankedItem r : rankSafely(products, Ranker.Options.from(settings, 40), today)) {
            if (r.candidate().companyId() != null) {
                stores.merge(r.candidate().companyId(), 1, Integer::sum);
            }
        }
        if (stores.isEmpty()) {
            return null;
        }
        List<Long> ranked = new ArrayList<>(stores.keySet());
        ranked.sort(java.util.Comparator.comparingInt((Long id) -> -stores.get(id)));
        int maxCards = settings.getMaxCards() > 0 ? Math.min(settings.getMaxCards(), 10) : 5;
        List<RankedItem> chosen = new ArrayList<>();
        Set<String> allowed = new HashSet<>();
        for (Long store : ranked) {
            if (chosen.size() >= maxCards) {
                break;
            }
            RetrievalPlan booklets = new RetrievalPlan(intent.intent().id(), List.of("BOOKLET"), "", lang, store, null, true, 2,
                    null, null, false, null);
            for (RankedItem r : rankSafely(booklets, Ranker.Options.from(settings, 2), today)) {
                if (allowed.add(r.candidate().key())) {
                    chosen.add(r);
                    break;
                }
            }
        }
        if (chosen.isEmpty()) {
            return null;
        }
        List<ChatCard> cards = outputGuard.verifyCards(cardFactory.cards(chosen, lang), allowed, today);
        if (cards.isEmpty()) {
            return null;
        }
        String message = languages.format("booklets_for_category", lang, intent.category().label(lang));
        return new Outcome(message, cards, 0.85, false, null).route(AiReply.ROUTE_SEARCH).kind(ConversationContext.KIND_SEARCH);
    }

    private static List<RankedItem> byPrice(List<RankedItem> items) {
        List<RankedItem> out = new ArrayList<>(items);
        out.sort(java.util.Comparator.comparingDouble(i -> i.candidate().price() == null || i.candidate().price() <= 0
                ? Double.MAX_VALUE : i.candidate().price()));
        return out;
    }

    private String priceHeader(IntentResult intent, String lang, List<RankedItem> chosen, IntentRouter.Signals signals) {
        List<String> stems = headStems(intent.query() == null ? "" : intent.query());
        RankedItem first = null;
        boolean firstHead = false;
        for (RankedItem r : chosen) {
            Double price = r.candidate().price();
            if (price == null || price <= 0) {
                continue;
            }
            boolean head = !stems.isEmpty() && headRank(r.candidate().title(lang), stems) >= 0;
            if (first == null || head && !firstHead || head == firstHead && price < first.candidate().price()) {
                first = r;
                firstHead = head;
            }
        }
        if (first == null) {
            return null;
        }
        String q = intent.query() == null || intent.query().isBlank()
                ? intent.category() == null ? "" : intent.category().label(lang) : intent.query().trim();
        Candidate c = first.candidate();
        String header = signals.cheapest()
                ? languages.format("cheapest_header", lang, q, itemLink(c, lang), priceText(c.price(), lang), where(c, lang))
                : languages.format("price_header", lang, q, priceText(c.price(), lang), itemLink(c, lang), where(c, lang));
        if (chosen.size() > 1) {
            header = header + "\n" + languages.template("cheapest_more", lang);
        }
        return header;
    }

    private static String companyPath(CompanyRef company) {
        return "/company/" + (company.slug() == null || company.slug().isBlank() ? company.id() : company.slug());
    }

    private static String plain(String value) {
        return value == null ? "" : value.replace("[", "").replace("]", "").trim();
    }

    private Outcome storeAspectOutcome(IntentResult intent, String text, String lang, AiRequest request,
                                       ChatSettingsEntity settings, Ctx ctx) {
        String aspect = ctx.signals().storeAspect();
        CompanyRef company = intent.company();
        String name = plain(company.name());
        String path = companyPath(company);
        int maxCards = settings.getMaxCards() > 0 ? Math.min(settings.getMaxCards(), 10) : 5;
        LocalDate today = LocalDate.now();
        switch (aspect) {
            case "location", "schedule", "loyalty" -> {
                if (aspect.equals("location") && ctx.signals().storeCompare()) {
                    return null;
                }
                String message = languages.format("store_" + aspect, lang, name, path);
                List<ChatCard> cards = new ArrayList<>();
                RankedItem companyItem = companyItem(company);
                if (companyItem != null) {
                    ChatCard card = cardFactory.card(companyItem, lang);
                    if (card != null) {
                        cards.add(card);
                    }
                }
                List<String> types = retriever.usable(pricedTypes(), true);
                if (!types.isEmpty() && !aspect.equals("loyalty")) {
                    RetrievalPlan p = new RetrievalPlan(intent.intent() == null ? null : intent.intent().id(), types, "", lang,
                            company.id(), null, true, 3, null, null, true, null);
                    List<RankedItem> items = rankSafely(p, Ranker.Options.from(settings, 3), today);
                    List<RankedItem> top = items.size() > 3 ? items.subList(0, 3) : items;
                    Set<String> allowed = new HashSet<>();
                    for (RankedItem r : top) {
                        allowed.add(r.candidate().key());
                    }
                    cards.addAll(outputGuard.verifyCards(cardFactory.cards(top, lang), allowed, today));
                }
                return new Outcome(message, cards, 0.9, false, null).route(AiReply.ROUTE_TEMPLATE);
            }
            case "catalog" -> {
                IntentDef booklets = catalog.get("catalogs");
                if (booklets == null) {
                    return null;
                }
                IntentResult r = new IntentResult(booklets, 0.9, company, null, "", false, true, null)
                        .with(intent.place(), null, null, false, null);
                RetrievalPlan p = plan(r, lang, IntentRouter.Signals.NONE);
                List<RankedItem> items = rankSafely(p, Ranker.Options.from(settings, maxCards), today);
                if (items.isEmpty()) {
                    return null;
                }
                List<RankedItem> top = items.size() > maxCards ? items.subList(0, maxCards) : items;
                Set<String> allowed = new HashSet<>();
                for (RankedItem i : top) {
                    allowed.add(i.candidate().key());
                }
                List<ChatCard> cards = outputGuard.verifyCards(cardFactory.cards(top, lang), allowed, today);
                String message = languages.format("results_company_booklets", lang, name);
                return new Outcome(message, cards, 0.9, false, null).route(AiReply.ROUTE_SEARCH);
            }
            case "deals", "count" -> {
                if (!aspectResidual(intent.query(), aspect).isBlank() && aspect.equals("deals")) {
                    return null;
                }
                IntentDef def = intent.intent() != null && intent.intent().isCatalog() ? intent.intent() : catalog.first(Role.COMPANY);
                if (def == null) {
                    return null;
                }
                IntentResult r = new IntentResult(def, 0.9, company, intent.category(), "", false, true, null)
                        .with(intent.place(), intent.priceMin(), intent.priceMax(), true, null);
                return catalogOutcome(r, text, lang, request, settings, false, ctx);
            }
            default -> {
                return null;
            }
        }
    }

    private Outcome alternativesOutcome(IntentResult intent, RetrievalPlan plan, Ranker.Options options, String lang,
                                        LocalDate today, Ctx ctx, int maxCards) {
        String q = intent.query() == null ? "" : intent.query().trim();
        List<String> types = retriever.usable(pricedTypes(), true);
        if (types.isEmpty()) {
            return null;
        }
        RetrievalPlan open = new RetrievalPlan(plan.intent(), types, q, lang, null, plan.category(), q.isEmpty(), 10, plan.priceMin(),
                plan.priceMax(), plan.sortDiscount(), plan.place(), RetrievalPlan.RELAX_NONE, plan.minDiscount(), plan.sort());
        List<RankedItem> items = signalFilter(q.isEmpty() ? rankSafely(open, options, today) : relevantTo(rankSafely(open, options, today), q),
                ctx.signals(), today);
        if (q.isEmpty() && intent.category() != null) {
            q = intent.category().label(lang);
        }
        if (items.isEmpty()) {
            return null;
        }
        items = byPrice(items);
        List<RankedItem> top = items.size() > maxCards ? items.subList(0, maxCards) : items;
        Set<String> allowed = new HashSet<>();
        for (RankedItem r : top) {
            allowed.add(r.candidate().key());
        }
        List<ChatCard> cards = outputGuard.verifyCards(cardFactory.cards(top, lang), allowed, today);
        if (cards.isEmpty()) {
            return null;
        }
        Candidate best = top.get(0).candidate();
        StringBuilder sb = new StringBuilder(languages.format("store_alternatives", lang, plain(intent.company().name()), q));
        if (best.price() != null) {
            sb.append("\n").append(languages.format("compare_stores_line", lang, storeName(best), itemLink(best, lang),
                    priceText(best.price(), lang)));
        }
        RankedItem companyItem = companyItem(intent.company());
        if (companyItem != null) {
            ChatCard card = cardFactory.card(companyItem, lang);
            if (card != null) {
                cards.add(card);
            }
        }
        return new Outcome(sb.toString(), cards, 0.8, false, null).route(AiReply.ROUTE_SEARCH);
    }

    private String storeName(Candidate c) {
        if (c.companyId() == null) {
            return "";
        }
        try {
            CompanyRef company = directory.company(c.companyId());
            return company == null || company.name() == null ? "" : plain(company.name());
        } catch (RuntimeException e) {
            return "";
        }
    }

    private Outcome storesCompareOutcome(IntentResult intent, String lang, ChatSettingsEntity settings, Ctx ctx) {
        String q = intent.query() == null ? "" : intent.query().trim();
        List<String> types = retriever.usable(pricedTypes(), true);
        if (types.isEmpty()) {
            return null;
        }
        if (q.isEmpty() && intent.category() == null) {
            return ctx.signals().storeCompareCheap() ? basketCompareOutcome(intent, lang, settings, ctx) : null;
        }
        LocalDate today = LocalDate.now();
        Ranker.Options options = Ranker.Options.from(settings, 40);
        RetrievalPlan p = new RetrievalPlan(intent.intent() == null ? null : intent.intent().id(), types, q, lang, null,
                intent.category(), q.isEmpty(), 30, intent.priceMin(), intent.priceMax(), false, intent.place(),
                RetrievalPlan.RELAX_NONE, ctx.signals().minDiscount(), null);
        List<RankedItem> items = q.isEmpty() ? rankSafely(p, options, today) : relevantTo(rankSafely(p, options, today), q);
        if (items.isEmpty() && !q.isEmpty()) {
            items = looseRelevant(rankSafely(p.withRelax(RetrievalPlan.RELAX_VARIANTS), options, today), q);
        }
        items = headPreferred(signalFilter(items, ctx.signals(), today), q, lang);
        java.util.Map<Long, RankedItem> cheapest = new java.util.LinkedHashMap<>();
        for (RankedItem r : byPrice(items)) {
            Candidate c = r.candidate();
            if (c.companyId() == null || c.price() == null || c.price() <= 0 || cheapest.containsKey(c.companyId())) {
                continue;
            }
            cheapest.put(c.companyId(), r);
        }
        if (cheapest.size() < 2) {
            return null;
        }
        List<RankedItem> stores = new ArrayList<>(cheapest.values());
        List<RankedItem> shown = new ArrayList<>(stores.subList(0, Math.min(5, stores.size())));
        if (intent.company() != null && cheapest.containsKey(intent.company().id()) && !shown.contains(cheapest.get(intent.company().id()))) {
            shown.set(shown.size() - 1, cheapest.get(intent.company().id()));
        }
        String label = q.isEmpty() ? intent.category().label(lang) : q;
        StringBuilder sb = new StringBuilder(languages.format("compare_stores_header", lang, label));
        for (RankedItem r : shown) {
            Candidate c = r.candidate();
            sb.append("\n").append(languages.format("compare_stores_line", lang, storeName(c), itemLink(c, lang), priceText(c.price(), lang)));
        }
        Candidate best = shown.get(0).candidate();
        Candidate worst = shown.get(shown.size() - 1).candidate();
        double saved = round2(worst.price() - best.price());
        sb.append("\n\n");
        if (saved > 0) {
            long percent = Math.round(saved * 100.0 / worst.price());
            sb.append(languages.format("compare_stores_best", lang, storeName(best), priceText(best.price(), lang), storeName(worst),
                    priceText(saved, lang), percent));
        } else {
            sb.append(languages.format("compare_stores_same", lang, priceText(best.price(), lang)));
        }
        if (intent.company() != null && !cheapest.containsKey(intent.company().id())) {
            sb.append("\n").append(languages.format("compare_stores_missing", lang, plain(intent.company().name()), label));
        }
        int maxCards = settings.getMaxCards() > 0 ? Math.min(settings.getMaxCards(), 10) : 5;
        List<RankedItem> top = shown.size() > maxCards ? shown.subList(0, maxCards) : shown;
        Set<String> allowed = new HashSet<>();
        for (RankedItem r : top) {
            allowed.add(r.candidate().key());
        }
        List<ChatCard> cards = outputGuard.verifyCards(cardFactory.cards(top, lang), allowed, today);
        return new Outcome(sb.toString(), cards, 0.85, false, null).route(AiReply.ROUTE_SEARCH).kind(ConversationContext.KIND_COMPARE);
    }

    private Outcome basketCompareOutcome(IntentResult intent, String lang, ChatSettingsEntity settings, Ctx ctx) {
        List<String> terms = new ArrayList<>();
        for (ChatLanguages.BasketItem item : languages.basket().items(lang)) {
            if (item.essential() && terms.size() < 8 && !excludedText(item.term(), ctx.signals().excluded())) {
                terms.add(item.term());
            }
        }
        List<String> types = retriever.usable(pricedTypes(), true);
        if (terms.size() < 2 || types.isEmpty()) {
            return null;
        }
        LocalDate today = LocalDate.now();
        Ranker.Options options = Ranker.Options.from(settings, 30);
        java.util.Map<Long, java.util.Map<String, RankedItem>> perStore = new java.util.LinkedHashMap<>();
        for (String term : terms) {
            RetrievalPlan p = new RetrievalPlan(intent.intent() == null ? null : intent.intent().id(), types, term, lang, null, null,
                    false, 30, null, null, false, intent.place());
            for (RankedItem r : byPrice(relevantTo(rankSafely(p, options, today), term))) {
                Candidate c = r.candidate();
                if (c.companyId() == null || c.price() == null || c.price() <= 0) {
                    continue;
                }
                perStore.computeIfAbsent(c.companyId(), k -> new java.util.LinkedHashMap<>()).putIfAbsent(term, r);
            }
        }
        int maxCoverage = 0;
        for (java.util.Map<String, RankedItem> m : perStore.values()) {
            maxCoverage = Math.max(maxCoverage, m.size());
        }
        if (maxCoverage < 2) {
            return null;
        }
        int floor = Math.max(2, maxCoverage - 1);
        List<java.util.Map.Entry<Long, java.util.Map<String, RankedItem>>> ranked = new ArrayList<>();
        for (java.util.Map.Entry<Long, java.util.Map<String, RankedItem>> e : perStore.entrySet()) {
            if (e.getValue().size() >= floor) {
                ranked.add(e);
            }
        }
        if (ranked.size() < 2) {
            return null;
        }
        ranked.sort(java.util.Comparator.<java.util.Map.Entry<Long, java.util.Map<String, RankedItem>>>comparingInt(e -> -e.getValue().size())
                .thenComparingDouble(e -> total(e.getValue())));
        List<java.util.Map.Entry<Long, java.util.Map<String, RankedItem>>> shown = ranked.subList(0, Math.min(4, ranked.size()));
        StringBuilder sb = new StringBuilder(languages.format("compare_basket_header", lang, String.join(", ", terms)));
        for (java.util.Map.Entry<Long, java.util.Map<String, RankedItem>> e : shown) {
            CompanyRef company = directory.company(e.getKey());
            String name = company == null ? String.valueOf(e.getKey()) : plain(company.name());
            sb.append("\n").append(languages.format("compare_basket_line", lang, name, priceText(round2(total(e.getValue())), lang),
                    e.getValue().size(), terms.size()));
        }
        java.util.Map.Entry<Long, java.util.Map<String, RankedItem>> winner = shown.get(0);
        CompanyRef best = directory.company(winner.getKey());
        sb.append("\n\n").append(languages.format("compare_basket_best", lang, best == null ? "" : plain(best.name()),
                priceText(round2(total(winner.getValue())), lang)));
        int maxCards = settings.getMaxCards() > 0 ? Math.min(settings.getMaxCards(), 10) : 5;
        List<RankedItem> chosen = new ArrayList<>(winner.getValue().values());
        if (chosen.size() > maxCards) {
            chosen = chosen.subList(0, maxCards);
        }
        Set<String> allowed = new HashSet<>();
        for (RankedItem r : chosen) {
            allowed.add(r.candidate().key());
        }
        List<ChatCard> cards = outputGuard.verifyCards(cardFactory.cards(chosen, lang), allowed, today);
        return new Outcome(sb.toString(), cards, 0.85, false, null).route(AiReply.ROUTE_SEARCH).kind(ConversationContext.KIND_COMPARE);
    }

    private static double total(java.util.Map<String, RankedItem> items) {
        double sum = 0;
        for (RankedItem r : items.values()) {
            sum += r.candidate().price();
        }
        return sum;
    }

    private String itemLink(Candidate c, String lang) {
        String title = c.title(lang);
        String clean = title == null ? "" : title.replace("[", "").replace("]", "").trim();
        if (c.path() == null || !outputGuard.isInternalPath(c.path())) {
            return clean;
        }
        return languages.format("item_link", lang, clean, c.path());
    }

    private String mapLink(String lang) {
        if (!outputGuard.isInternalPath("/map")) {
            return "";
        }
        String map = languages.template("see_map", lang);
        if (map.isBlank()) {
            map = languages.template("where_map", lang).replaceFirst("^\s*·\s*", "");
        }
        return map.trim();
    }

    private String whereStore(Candidate c, String lang) {
        if (c.companyId() == null) {
            return "";
        }
        CompanyRef company;
        try {
            company = directory.company(c.companyId());
        } catch (RuntimeException e) {
            company = null;
        }
        if (company == null || company.name() == null) {
            return "";
        }
        String path = companyPath(company);
        return outputGuard.isInternalPath(path) ? languages.format("where_store", lang, plain(company.name()), path) : "";
    }

    private List<RankedItem> headOnly(List<RankedItem> items, String term, String lang, boolean food) {
        List<String> q = headStems(term);
        if (q.isEmpty()) {
            return items;
        }
        List<RankedItem> head = new ArrayList<>();
        List<RankedItem> near = new ArrayList<>();
        for (RankedItem r : items) {
            String title = r.candidate().title(lang);
            if (title == null || !hasAll(title, q) || food && (languages.foreignTo(title, term) || languages.nonFood(title, term))) {
                continue;
            }
            int rank = headRank(title, q);
            if (rank == 0) {
                head.add(r);
            } else if (rank == 1) {
                near.add(r);
            }
        }
        return head.isEmpty() ? near : head;
    }

    private String where(Candidate c, String lang) {
        if (c.companyId() == null) {
            return "";
        }
        CompanyRef company;
        try {
            company = directory.company(c.companyId());
        } catch (RuntimeException e) {
            company = null;
        }
        if (company == null || company.name() == null) {
            return "";
        }
        String path = "/company/" + (company.slug() == null || company.slug().isBlank() ? company.id() : company.slug());
        String name = company.name().replace("[", "").replace("]", "");
        StringBuilder sb = new StringBuilder();
        if (outputGuard.isInternalPath(path)) {
            sb.append(languages.format("where_store", lang, name, path));
        }
        if (outputGuard.isInternalPath("/map")) {
            sb.append(languages.template("where_map", lang));
        }
        return sb.toString();
    }

    private boolean excludedTitle(Candidate c, List<String> excluded) {
        StringBuilder sb = new StringBuilder();
        for (String t : c.titles().values()) {
            if (t != null) {
                sb.append(' ').append(t);
            }
        }
        return excludedText(sb.toString(), excluded);
    }

    private static boolean excludedText(String text, List<String> excluded) {
        if (excluded == null || excluded.isEmpty() || text == null) {
            return false;
        }
        for (String token : TextNormalizer.tokens(text)) {
            for (String word : excluded) {
                if (token.equals(word) || (word.length() >= 4 && token.startsWith(word))) {
                    return true;
                }
            }
        }
        return false;
    }

    private Outcome basketOutcome(IntentResult intent, String lang, ChatSettingsEntity settings, Ctx ctx) {
        IntentRouter.BasketRequest request = ctx.signals().basket();
        List<ChatLanguages.BasketItem> lines = new ArrayList<>();
        for (ChatLanguages.BasketItem line : languages.basket().items(lang)) {
            if (!excludedText(line.term(), ctx.signals().excluded())) {
                lines.add(line);
            }
        }
        List<String> types = retriever.usable(pricedTypes(), true);
        if (lines.isEmpty() || types.isEmpty()) {
            return null;
        }
        LocalDate today = LocalDate.now();
        Long company = intent.company() == null ? null : intent.company().id();
        Ranker.Options options = Ranker.Options.from(settings, 8);
        List<String> basketTerms = new ArrayList<>();
        for (ChatLanguages.BasketItem line : lines) {
            basketTerms.add(line.term());
        }
        java.util.Map<String, List<RankedItem>> candidates = inParallel(basketTerms, term -> {
            ChatLanguages.BasketItem line = lines.get(basketTerms.indexOf(term));
            RetrievalPlan p = new RetrievalPlan(intent.intent() == null ? null : intent.intent().id(), types, line.term(), lang,
                    company, null, false, 8, null, null, true, intent.place(), RetrievalPlan.RELAX_NONE,
                    ctx.signals().minDiscount(), null);
            List<RankedItem> items = headOnly(signalFilter(relevantTo(rankSafely(p, options, today), line.term()), ctx.signals(),
                    today), line.term(), lang, true);
            if (items.isEmpty()) {
                items = headOnly(signalFilter(looseRelevant(rankSafely(p.withRelax(RetrievalPlan.RELAX_VARIANTS), options, today),
                        line.term()), ctx.signals(), today), line.term(), lang, true);
            }
            if (!line.avoid().isEmpty()) {
                items = new ArrayList<>(items);
                items.removeIf(r -> languages.containsWord(r.candidate().title(lang), line.avoid()));
            }
            return items;
        });
        Double budget = intent.priceMin() == null ? intent.priceMax() : null;
        int people = Math.max(1, request.people());
        ShoppingPlanner.Plan plan = ShoppingPlanner.greedy(lines, candidates, ShoppingPlanner.factor(request.period(), people),
                budget, ctx.signals().alternative() ? 1 : 0);
        if (plan.picked().isEmpty()) {
            return null;
        }
        String who = people == 1 ? languages.template("basket_person", lang) : languages.format("basket_people", lang, people);
        String period = languages.template("basket_period_" + request.period(), lang);
        StringBuilder sb = new StringBuilder(languages.format("basket_header", lang, who, period));
        sb.append("\n");
        if (budget != null) {
            sb.append(languages.format("basket_total", lang, priceText(plan.total(), lang), priceText(budget, lang),
                    priceText(ShoppingPlanner.round2(budget - plan.total()), lang)));
        } else {
            sb.append(languages.format("basket_total_plain", lang, priceText(plan.total(), lang)));
        }
        List<RankedItem> chosen = new ArrayList<>();
        Set<String> allowed = new HashSet<>();
        for (ShoppingPlanner.Picked picked : plan.picked()) {
            Candidate c = picked.offer().item().candidate();
            sb.append("\n").append(languages.format("basket_line", lang, picked.line().term(),
                    ShoppingPlanner.quantity(ShoppingPlanner.round2(picked.need()), picked.line().unit()), itemLink(c, lang),
                    picked.offer().packs(), priceText(c.price(), lang), priceText(picked.offer().subtotal(), lang), whereStore(c, lang)));
            if (allowed.add(c.key())) {
                chosen.add(picked.offer().item());
            }
        }
        if (!plan.skipped().isEmpty()) {
            sb.append("\n").append(languages.format("basket_skipped", lang, String.join(", ", plan.skipped())));
        }
        if (!plan.missing().isEmpty()) {
            sb.append("\n").append(languages.format("basket_missing", lang, String.join(", ", plan.missing())));
        }
        sb.append("\n").append(languages.template("basket_note", lang));
        String map = mapLink(lang);
        if (!map.isBlank()) {
            sb.append(" ").append(map);
        }
        int maxCards = Math.min(10, Math.max(settings.getMaxCards() > 0 ? settings.getMaxCards() : 5, chosen.size()));
        List<ChatCard> cards = outputGuard.verifyCards(cardFactory.cards(chosen.size() > maxCards ? chosen.subList(0, maxCards)
                : chosen, lang), allowed, today);
        return new Outcome(sb.toString(), cards, 0.85, false, null).route(AiReply.ROUTE_SEARCH).kind(ConversationContext.KIND_BASKET);
    }

    private Outcome nutritionOutcome(IntentRouter.NutritionRequest request, IntentResult intent, String lang,
                                     ChatSettingsEntity settings, Ctx ctx) {
        if (request.refuse()) {
            return new Outcome(languages.template("nutrition_refuse", lang), List.of(), 0.9, false, null)
                    .route(AiReply.ROUTE_TEMPLATE);
        }
        ChatLanguages.Nutrition config = languages.nutrition();
        java.util.Map<String, List<String>> meals = request.protein() && !config.proteinMeals().isEmpty()
                ? config.proteinMeals() : config.meals();
        ShoppingPlanner.Menu menu = ShoppingPlanner.menu(request.kcal(), meals, foods());
        if (menu.portions().isEmpty()) {
            return null;
        }
        LocalDate today = LocalDate.now();
        List<String> types = retriever.usable(pricedTypes(), true);
        Ranker.Options options = Ranker.Options.from(settings, 6);
        Long company = intent.company() == null ? null : intent.company().id();
        StringBuilder sb = new StringBuilder(languages.format("nutrition_header", lang, request.kcal(),
                request.protein() ? languages.template("nutrition_protein", lang) : ""));
        List<RankedItem> chosen = new ArrayList<>();
        Set<String> allowed = new HashSet<>();
        String meal = null;
        for (ShoppingPlanner.Portion portion : menu.portions()) {
            if (!portion.meal().equals(meal)) {
                meal = portion.meal();
                int mealKcal = (int) Math.round(menu.portions().stream().filter(p -> p.meal().equals(portion.meal()))
                        .mapToDouble(ShoppingPlanner.Portion::kcal).sum());
                sb.append("\n\n").append(languages.format("nutrition_meal", lang, languages.template("meal_" + meal, lang), mealKcal));
            }
            String name = portion.food().name(lang);
            String label = name;
            if (!types.isEmpty() && !excludedText(name, ctx.signals().excluded())) {
                RetrievalPlan p = new RetrievalPlan(intent.intent() == null ? null : intent.intent().id(), types, name, lang,
                        company, null, false, 6, null, null, true, intent.place());
                List<RankedItem> items = headOnly(signalFilter(relevantTo(rankSafely(p, options, today), name), ctx.signals(), today),
                        name, lang, true);
                RankedItem best = null;
                for (RankedItem r : items) {
                    if (r.candidate().price() != null && (best == null || r.candidate().price() < best.candidate().price())) {
                        best = r;
                    }
                }
                if (best != null) {
                    label = name + ": " + itemLink(best.candidate(), lang) + " — " + priceText(best.candidate().price(), lang)
                            + where(best.candidate(), lang);
                    if (allowed.add(best.candidate().key())) {
                        chosen.add(best);
                    }
                }
            }
            sb.append("\n").append(languages.format("nutrition_line", lang, portion.grams(), label));
        }
        sb.append("\n\n").append(languages.format("nutrition_total", lang, (int) Math.round(menu.kcal()),
                (int) Math.round(menu.protein()), (int) Math.round(menu.fat()), (int) Math.round(menu.carbs())));
        sb.append("\n").append(languages.template("nutrition_disclaimer", lang));
        int maxCards = Math.min(10, Math.max(settings.getMaxCards() > 0 ? settings.getMaxCards() : 5, chosen.size()));
        List<ChatCard> cards = outputGuard.verifyCards(cardFactory.cards(chosen.size() > maxCards ? chosen.subList(0, maxCards)
                : chosen, lang), allowed, today);
        return new Outcome(sb.toString(), cards, 0.85, false, null).route(AiReply.ROUTE_SEARCH).kind(ConversationContext.KIND_NUTRITION);
    }

    private volatile List<ShoppingPlanner.Food> foods;

    private List<ShoppingPlanner.Food> foods() {
        List<ShoppingPlanner.Food> f = foods;
        if (f == null) {
            f = ShoppingPlanner.foods(com.naqqa.chatbot.i18n.ChatResources.defaults());
            foods = f;
        }
        return f;
    }


    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private Outcome productCompareOutcome(IntentResult intent, List<String> pair, String lang, ChatSettingsEntity settings) {
        List<String> types = retriever.usable(pricedTypes(), true);
        if (types.isEmpty() || pair.size() != 2) {
            return null;
        }
        LocalDate today = LocalDate.now();
        Long company = intent.company() == null ? null : intent.company().id();
        Ranker.Options options = Ranker.Options.from(settings, 6);
        List<RankedItem> best = new ArrayList<>();
        List<List<RankedItem>> sides = new ArrayList<>();
        for (String side : pair) {
            RetrievalPlan p = new RetrievalPlan(intent.intent() == null ? null : intent.intent().id(), types, side, lang,
                    company, null, false, 6, intent.priceMin(), intent.priceMax(), false, intent.place());
            List<RankedItem> items = relevantTo(rankSafely(p, options, today), side);
            if (items.isEmpty()) {
                items = looseRelevant(rankSafely(p.withRelax(RetrievalPlan.RELAX_FUZZY), options, today), side);
            }
            List<RankedItem> priced = new ArrayList<>();
            for (RankedItem i : items) {
                if (i.candidate().price() != null) {
                    priced.add(i);
                }
            }
            priced.sort(java.util.Comparator.comparingDouble(i -> i.candidate().price()));
            sides.add(priced);
            best.add(priced.isEmpty() ? null : priced.get(0));
        }
        if (best.get(0) == null && best.get(1) == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(languages.format("compare_products_header", lang, pair.get(0), pair.get(1)));
        for (int i = 0; i < 2; i++) {
            RankedItem item = best.get(i);
            if (item == null) {
                sb.append("\n").append(languages.format("compare_none", lang, pair.get(i)));
                continue;
            }
            Candidate c = item.candidate();
            String title = c.title(lang);
            String discount = c.discount() != null && c.discount() >= 1 && c.discount() <= 99
                    ? languages.format("scenario_discount", lang, Math.round(c.discount())) : "";
            sb.append("\n").append(languages.format("compare_line", lang, pair.get(i), itemLink(c, lang),
                    priceText(c.price(), lang) + discount)).append(where(c, lang));
        }
        if (best.get(0) != null && best.get(1) != null) {
            RankedItem cheaper = best.get(0).candidate().price() <= best.get(1).candidate().price() ? best.get(0) : best.get(1);
            String title = cheaper.candidate().title(lang);
            sb.append("\n\n").append(languages.format("compare_products_cheaper", lang, title == null ? "" : title,
                    priceText(cheaper.candidate().price(), lang)));
            RankedItem deeper = discountOf(best.get(0)) >= discountOf(best.get(1)) ? best.get(0) : best.get(1);
            if (discountOf(deeper) >= 1 && deeper != cheaper) {
                String dt = deeper.candidate().title(lang);
                sb.append(" ").append(languages.format("compare_products_discount", lang, dt == null ? "" : dt,
                        Math.round(discountOf(deeper))));
            }
        }
        int maxCards = settings.getMaxCards() > 0 ? Math.min(settings.getMaxCards(), 10) : 5;
        List<RankedItem> chosen = new ArrayList<>();
        Set<String> allowed = new HashSet<>();
        for (int round = 0; round < 3 && chosen.size() < maxCards; round++) {
            for (List<RankedItem> side : sides) {
                if (round < side.size() && chosen.size() < maxCards && allowed.add(side.get(round).candidate().key())) {
                    chosen.add(side.get(round));
                }
            }
        }
        List<ChatCard> cards = outputGuard.verifyCards(cardFactory.cards(chosen, lang), allowed, today);
        return new Outcome(sb.toString(), cards, 0.8, false, null).route(AiReply.ROUTE_SEARCH).kind(ConversationContext.KIND_COMPARE);
    }

    private static double discountOf(RankedItem item) {
        return item == null || item.candidate().discount() == null || item.candidate().discount() > 99 ? 0
                : item.candidate().discount();
    }

    private Outcome bestDealsOutcome(IntentResult intent, String lang, ChatSettingsEntity settings, MemoryContext memory) {
        LocalDate today = LocalDate.now();
        List<RankedItem> items = recommendationItems(lang, settings, today);
        if (items.isEmpty()) {
            return null;
        }
        int maxCards = settings.getMaxCards() > 0 ? Math.min(settings.getMaxCards(), 10) : 5;
        List<RankedItem> chosen = Ranker.displayOrder(items.size() > maxCards ? items.subList(0, maxCards) : items);
        PersonalPick pick = memory == null ? null : personalPick(memory, lang, settings, today, false);
        if (pick != null) {
            chosen = merged(pick.items(), chosen, maxCards);
        }
        Set<String> allowed = new HashSet<>();
        for (RankedItem r : chosen) {
            allowed.add(r.candidate().key());
        }
        List<ChatCard> cards = outputGuard.verifyCards(cardFactory.cards(chosen, lang), allowed, today);
        if (cards.isEmpty()) {
            return null;
        }
        String header = languages.template("recommend_header", lang);
        return new Outcome(pick == null ? header : pick.header() + "\n\n" + header, cards, Math.max(0.6, intent.confidence()), false, null)
                .route(AiReply.ROUTE_SEARCH);
    }

    private record PersonalPick(List<RankedItem> items, String header) {
    }

    private static List<RankedItem> merged(List<RankedItem> first, List<RankedItem> rest, int max) {
        List<RankedItem> out = new ArrayList<>(first);
        Set<String> keys = new HashSet<>();
        for (RankedItem r : first) {
            keys.add(r.candidate().key());
        }
        for (RankedItem r : rest) {
            if (keys.add(r.candidate().key())) {
                out.add(r);
            }
        }
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    private PersonalPick personalPick(MemoryContext memory, String lang, ChatSettingsEntity settings, LocalDate today, boolean newest) {
        if (memory == null || memory.products().isEmpty()) {
            return null;
        }
        MemoryContext.Pref product = memory.products().get(0);
        Long storeId = memory.defaultStoreId() != null ? memory.defaultStoreId() : memory.topStore() == null ? null : memory.topStore().id();
        CompanyRef store = storeId == null ? null : directory.company(storeId);
        List<RankedItem> items = store == null ? List.of() : memoryOffers(product.label(), store, lang, settings, today, newest);
        boolean atStore = !items.isEmpty();
        if (items.isEmpty()) {
            items = memoryOffers(product.label(), null, lang, settings, today, newest);
        }
        if (items.isEmpty()) {
            return null;
        }
        List<RankedItem> top = new ArrayList<>(items.subList(0, Math.min(2, items.size())));
        String header = atStore ? languages.format("memory_for_you_store", lang, plain(product.label()), plain(store.name()))
                : languages.format("memory_for_you", lang, plain(product.label()));
        return new PersonalPick(top, header);
    }

    private List<RankedItem> memoryOffers(String query, CompanyRef store, String lang, ChatSettingsEntity settings, LocalDate today,
                                          boolean newest) {
        IntentDef def = catalog.first(Role.SEARCH);
        if (def == null || query == null || query.isBlank()) {
            return List.of();
        }
        IntentResult r = new IntentResult(def, 0.9, store, null, query, false, false, null);
        RetrievalPlan plan = plan(r, lang, IntentRouter.Signals.NONE);
        if (newest) {
            plan = plan.withSignals(null, RetrievalPlan.SORT_NEWEST);
        }
        List<RankedItem> ranked = relevantTo(rankSafely(plan, Ranker.Options.from(settings, 20), today), query);
        List<RankedItem> kept = new ArrayList<>();
        for (RankedItem item : ranked) {
            if ("COMPANY".equals(item.candidate().type())) {
                continue;
            }
            if (store != null && !store.id().equals(item.candidate().companyId())) {
                continue;
            }
            kept.add(item);
        }
        List<String> q = headStems(query);
        if (q.isEmpty()) {
            return kept;
        }
        List<RankedItem> head = new ArrayList<>();
        List<RankedItem> mentioned = new ArrayList<>();
        for (RankedItem item : kept) {
            String title = item.candidate().title(lang);
            if (headRank(title, q) >= 0) {
                head.add(item);
            } else if (mentionsAny(title, q)) {
                mentioned.add(item);
            }
        }
        return head.isEmpty() ? mentioned : head;
    }

    @Override
    public AiReply welcome(AiRequest request) {
        MemoryContext memory = request == null ? null : request.memory();
        if (memory == null) {
            return null;
        }
        long start = System.nanoTime();
        String lang = languages.normalize(request.lang());
        ChatSettingsEntity settings = request.settings() != null ? request.settings() : new ChatSettingsEntity();
        LocalDate today = LocalDate.now();
        MemoryContext.Summary last = memory.last();
        ConversationContext ctx = last == null ? null : last.parsed();
        String topic = last != null && !last.topics().isEmpty() ? last.topics().get(last.topics().size() - 1)
                : ctx != null && ctx.query() != null && !ctx.query().isBlank() ? ctx.query().trim() : null;
        if (topic == null && memory.products().isEmpty()) {
            return null;
        }
        CompanyRef store = ctx != null && ctx.companyId() != null ? directory.company(ctx.companyId()) : null;
        StringBuilder sb = new StringBuilder(languages.template("memory_welcome_back", lang));
        List<RankedItem> shown = new ArrayList<>();
        if (topic != null) {
            sb.append(' ').append(store != null ? languages.format("memory_welcome_last_store", lang, plain(topic), plain(store.name()))
                    : languages.format("memory_welcome_last", lang, plain(topic)));
            List<RankedItem> offers = memoryOffers(topic, store, lang, settings, today, true);
            List<RankedItem> fresh = fresh(offers, last == null ? null : last.at());
            if (!fresh.isEmpty()) {
                sb.append(' ').append(fresh.size() == 1 ? languages.format("memory_welcome_new_one", lang, plain(topic))
                        : languages.format("memory_welcome_new", lang, fresh.size(), plain(topic)));
                shown.addAll(fresh.subList(0, Math.min(3, fresh.size())));
            } else if (!offers.isEmpty()) {
                sb.append(' ').append(offers.size() == 1 ? languages.format("memory_welcome_active_one", lang, plain(topic))
                        : languages.format("memory_welcome_active", lang, offers.size(), plain(topic)));
                shown.addAll(offers.subList(0, Math.min(3, offers.size())));
            }
        }
        int checked = 0;
        for (MemoryContext.Pref p : memory.products()) {
            if (checked >= 3) {
                break;
            }
            if (p.label() == null || topic != null && TextNormalizer.compact(p.label()).equals(TextNormalizer.compact(topic))) {
                continue;
            }
            checked++;
            List<RankedItem> fresh = fresh(memoryOffers(p.label(), null, lang, settings, today, true), p.lastSeen());
            if (!fresh.isEmpty()) {
                sb.append("\n\n").append(fresh.size() == 1 ? languages.format("memory_tip_new_one", lang, plain(p.label()))
                        : languages.format("memory_tip_new", lang, fresh.size(), plain(p.label())));
                for (RankedItem r : fresh) {
                    if (shown.stream().noneMatch(x -> x.candidate().key().equals(r.candidate().key()))) {
                        shown.add(r);
                        break;
                    }
                }
                break;
            }
        }
        sb.append("\n\n").append(languages.template("memory_welcome_hint", lang));
        Set<String> allowed = new HashSet<>();
        for (RankedItem r : shown) {
            allowed.add(r.candidate().key());
        }
        List<ChatCard> cards = shown.isEmpty() ? List.of() : outputGuard.verifyCards(cardFactory.cards(shown, lang), allowed, today);
        Outcome o = new Outcome(sb.toString(), cards, 1.0, false, null)
                .route(cards.isEmpty() ? AiReply.ROUTE_TEMPLATE : AiReply.ROUTE_SEARCH);
        return reply(o, null, "memory_welcome", start, false, lang, List.of(), settings);
    }

    private static List<RankedItem> fresh(List<RankedItem> items, Instant since) {
        if (since == null) {
            return List.of();
        }
        long after = since.toEpochMilli();
        List<RankedItem> out = new ArrayList<>();
        for (RankedItem r : items) {
            Long f = r.candidate().freshnessMillis();
            if (f != null && f > after) {
                out.add(r);
            }
        }
        return out;
    }

    private List<String> types(IntentResult intent, boolean hasQuery, boolean priced, boolean hasCompany) {
        IntentCatalog.TypeRules rules = intent.intent() == null ? null : intent.intent().types();
        if (rules == null || rules.isEmpty()) {
            IntentCatalog.TypeRules defaults = catalog.defaultTypes();
            if (priced && defaults != null && defaults.withPrice() != null) {
                return defaults.withPrice();
            }
            if (defaults != null && defaults.defaults() != null) {
                return defaults.defaults();
            }
            return retriever.typeKeys();
        }
        if (hasCompany && rules.withCompany() != null) {
            return rules.withCompany();
        }
        if (intent.category() != null && rules.byTaxonomy() != null && rules.byTaxonomy().get(intent.category().taxonomy()) != null) {
            return rules.byTaxonomy().get(intent.category().taxonomy());
        }
        if (priced && rules.withPrice() != null) {
            return rules.withPrice();
        }
        if (intent.sortDiscount() && rules.withDiscountSort() != null) {
            return rules.withDiscountSort();
        }
        if (hasQuery && rules.withQuery() != null) {
            return rules.withQuery();
        }
        return rules.defaults() == null ? retriever.typeKeys() : rules.defaults();
    }

    private RankedItem companyItem(CompanyRef company) {
        Candidate c = null;
        try {
            c = retriever.provider() == null ? null : retriever.provider().companyCandidate(company);
        } catch (RuntimeException ignored) {
        }
        return c == null ? null : new RankedItem(c, 0, 1, false);
    }

    private String templateHeader(IntentResult intent, String lang, List<RankedItem> items, IntentRouter.Signals signals) {
        String q = intent.query() == null ? "" : intent.query().trim();
        String header;
        IntentRouter.Signals s = signals == null ? IntentRouter.Signals.NONE : signals;
        if (q.isEmpty() && intent.company() == null && intent.category() == null && intent.place() == null
                && s.minDiscount() != null) {
            header = languages.format("results_min_discount", lang, Math.round(s.minDiscount()));
        } else if (q.isEmpty() && intent.company() == null && intent.category() == null && intent.place() == null
                && RetrievalPlan.SORT_EXPIRING.equals(s.sort())) {
            header = languages.template("results_expiring", lang);
        } else if (q.isEmpty() && intent.company() == null && intent.category() == null && intent.place() == null
                && RetrievalPlan.SORT_NEWEST.equals(s.sort())) {
            header = languages.template("results_newest", lang);
        } else if (intent.company() != null) {
            header = q.isEmpty() ? languages.format("results_company", lang, intent.company().name())
                    : languages.format("results_company_query", lang, q, intent.company().name());
        } else if (intent.category() != null) {
            header = languages.format("results_category", lang, intent.category().label(lang));
        } else if (intent.place() != null) {
            header = languages.format("results_place", lang, intent.place().label(lang));
        } else if (intent.hasPrice() && q.isEmpty()) {
            header = intent.priceMin() != null && intent.priceMax() != null
                    ? languages.format("results_price_range", lang, money(intent.priceMin()), money(intent.priceMax()))
                    : intent.priceMax() != null ? languages.format("results_price_max", lang, money(intent.priceMax()))
                    : languages.format("results_price_min", lang, money(intent.priceMin()));
        } else if (intent.sortDiscount() && q.isEmpty()) {
            header = languages.template("results_discount", lang);
        } else if (!q.isEmpty()) {
            header = languages.format("results_query", lang, q);
        } else {
            String key = intent.intent() == null ? null : intent.intent().resultsTemplate();
            header = languages.template(key == null ? "results" : key, lang);
        }
        double maxDiscount = 0;
        boolean sponsored = false;
        boolean firstRegular = true;
        for (RankedItem item : items) {
            sponsored |= item.sponsored();
            if (item.sponsored() || !firstRegular) {
                continue;
            }
            firstRegular = false;
            Double d = item.candidate().discount();
            if (d != null && d <= 99) {
                maxDiscount = d;
            }
        }
        StringBuilder sb = new StringBuilder(header);
        if (maxDiscount >= 5) {
            sb.append(languages.format("top_discount", lang, Math.round(maxDiscount)));
        }
        if (sponsored) {
            sb.append(languages.template("sponsored_note", lang));
        }
        return sb.toString();
    }

    private String knowledgeExcerpt(KnowledgeHit hit, String lang) {
        String excerpt = structuredExcerpt(cleanKnowledge(hit.text()), 420);
        String title = hit.title() == null ? "" : cleanKnowledge(hit.title()).split(" — ")[0].split(" \\| ")[0].trim().replace("[", "").replace("]", "");
        if (title.isEmpty()) {
            return excerpt;
        }
        if (outputGuard.isInternalPath(hit.path())) {
            return excerpt + "\n\n" + languages.format("knowledge_link", lang, title, hit.path()).trim();
        }
        return excerpt + "\n\n" + languages.format("knowledge_more", lang, title).trim();
    }

    private String cleanKnowledge(String value) {
        if (value == null) {
            return null;
        }
        String replaced = knowledge.replace(value);
        return replaced == null ? value : replaced;
    }

    static String structuredExcerpt(String text, int max) {
        if (text == null || text.isBlank()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (String raw : text.split("\\n")) {
            String line = raw.replaceAll("\\s{2,}", " ").trim();
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        StringBuilder out = new StringBuilder();
        int used = 0;
        boolean previousWasItem = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).replaceAll("^[-*•·]\\s*", "").replaceAll("^\\d{1,2}[.)]\\s+", "");
            if (line.endsWith("?") && line.length() <= 160) {
                String question = "**" + line.replace("*", "") + "**";
                if (used > 0 && used + question.length() > max) {
                    break;
                }
                if (out.length() > 0) {
                    out.append("\n\n");
                }
                out.append(question);
                used += question.length();
                previousWasItem = false;
                continue;
            }
            boolean shortLine = line.length() <= 70 && !line.matches(".*[.!?:;…]$");
            String rendered;
            if (i == 0 && shortLine) {
                rendered = "**" + line.replace("*", "") + "**";
                previousWasItem = false;
            } else if (shortLine) {
                rendered = "- " + line;
                previousWasItem = true;
            } else {
                if (used + line.length() > max) {
                    String cut = OutputGuard.cap(line, used == 0 ? max : Math.max(80, max - used));
                    if (used == 0) {
                        out.append(cut);
                    } else if (cut.length() >= 40) {
                        out.append(previousWasItem ? "\n\n" : "\n").append(cut);
                    }
                    break;
                }
                rendered = line;
                if (previousWasItem) {
                    out.append('\n');
                }
                previousWasItem = false;
            }
            if (used > 0 && used + rendered.length() > max) {
                break;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(rendered);
            used += rendered.length();
        }
        return out.toString().trim();
    }

    public static String money(Double value) {
        if (value == null) {
            return "";
        }
        return value % 1 == 0 ? String.valueOf(value.longValue()) : String.valueOf(value);
    }

    private static String lastVisitorText(List<AiTurn> history) {
        if (history == null) {
            return "";
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            AiTurn t = history.get(i);
            if (t != null && "user".equals(t.role()) && t.text() != null && !t.text().isBlank()) {
                return t.text();
            }
        }
        return "";
    }

    private AiReply reply(Outcome outcome, IntentDef intent, String fallbackKey, long start, boolean flagged, String lang,
                          List<String> quickReplies, ChatSettingsEntity settings) {
        LlmResult llmResult = outcome.llm();
        boolean llmUsed = llmResult != null && llmResult.text() != null && !llmResult.text().isBlank();
        String safe = outputGuard.sanitize(outcome.text(), llmUsed ? OutputGuard.MAX_LENGTH : OutputGuard.RULE_MAX_LENGTH);
        if (safe.isBlank()) {
            safe = languages.template("unknown", lang);
        }
        int in = llmResult == null ? 0 : llmResult.tokensIn();
        int out = llmResult == null ? 0 : llmResult.tokensOut();
        long latency = (System.nanoTime() - start) / 1_000_000L;
        double conf = Math.max(0, Math.min(1, outcome.confidence()));
        String key = intent != null ? intent.key() : fallbackKey;
        List<String> flags = new ArrayList<>(outcome.flags);
        if (!flagged && settings != null && conf < settings.getMinConfidence() && !flags.contains(AiReply.FLAG_LOW_CONFIDENCE)) {
            flags.add(AiReply.FLAG_LOW_CONFIDENCE);
        }
        String route = llmUsed ? AiReply.ROUTE_LLM : outcome.route;
        return new AiReply(safe, outcome.cards() == null ? List.of() : List.copyOf(outcome.cards()), quickReplies, key, conf,
                outcome.escalate(), llmUsed, in, out, latency, flagged, route, flags, lang);
    }
}
