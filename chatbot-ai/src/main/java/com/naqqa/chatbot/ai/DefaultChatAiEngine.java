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
import com.naqqa.chatbot.ai.retrieval.RankedItem;
import com.naqqa.chatbot.ai.retrieval.Ranker;
import com.naqqa.chatbot.ai.retrieval.RetrievalPlan;
import com.naqqa.chatbot.ai.safety.ChatSafety;
import com.naqqa.chatbot.ai.safety.TopicGuard;
import com.naqqa.chatbot.entities.ChatCard;
import com.naqqa.chatbot.entities.ChatSettingsEntity;
import com.naqqa.chatbot.i18n.ChatLanguages;
import com.naqqa.chatbot.spi.ChatEntityResolver;
import com.naqqa.chatbot.spi.ChatSearchLinkBuilder;
import lombok.extern.slf4j.Slf4j;

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
    }

    public void setTopicGuard(TopicGuard topicGuard) {
        this.topicGuard = topicGuard;
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
    }

    private record Ctx(boolean comparative, boolean followUp, boolean carried, double threshold, boolean operatorDraft,
                       boolean recommendation, IntentRouter.Signals signals, boolean multiPart) {

        boolean special() {
            return comparative || followUp || recommendation || multiPart || signals.hasScenario()
                    || signals.hasComparePair();
        }
    }

    static final int STAGE_CATEGORY = 6;
    static final int STAGE_CLOSEST = 7;
    private static final int SCENARIO_MAX_TERMS = 8;
    private static final int EXPIRING_DAYS = 3;

    private record LadderResult(List<RankedItem> items, int stage, CategoryRef category) {

        static final LadderResult EMPTY = new LadderResult(List.of(), -1, null);
    }

    private record LlmCall(LlmResult result, boolean wanted, boolean failed) {

        static final LlmCall NONE = new LlmCall(null, false, false);
    }

    private AiReply run(AiRequest request, boolean operatorDraft) {
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
        boolean followUp = quickReply == null && responseRouter.followUp(guard.text());
        boolean carried = false;
        String signalText = guard.text();
        if (followUp) {
            String previous = lastVisitorText(request.history());
            if (previous != null && !previous.isBlank() && !previous.trim().equals(guard.text().trim())) {
                try {
                    String joined = TextNormalizer.clean(PiiMasker.mask(previous)) + " " + guard.text();
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
        Ctx ctx = new Ctx(quickReply == null && responseRouter.comparative(guard.text()), followUp, carried,
                ResponseRouter.threshold(settings.getLlmConfidenceThreshold()), operatorDraft,
                quickReply == null && responseRouter.recommendation(guard.text()), signals,
                quickReply == null && responseRouter.multiPart(guard.text()));
        Outcome outcome;
        try {
            outcome = handle(intent, guard.text(), lang, request, settings, operatorDraft, ctx);
        } catch (RuntimeException e) {
            log.warn("[chatbot] reply pipeline failed: {}", e.getMessage());
            outcome = new Outcome(languages.template("unknown", lang), List.of(), 0.3, false, null);
        }
        return reply(outcome, intent.intent(), null, start, false, lang,
                catalog.quickRepliesFor(intent.intent(), !outcome.cards().isEmpty()), settings);
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
        if (!operatorDraft && request.quickReply() == null && ctx.signals().basket() != null && scenarioApplies(def)) {
            Outcome basket = basketOutcome(intent, lang, settings, ctx);
            if (basket != null) {
                return basket;
            }
        }
        if (!operatorDraft && request.quickReply() == null && ctx.signals().hasScenario() && scenarioApplies(def)
                && !(ctx.recommendation() && responseRouter.allows(ResponseRouter.LlmReason.RECOMMENDATION))) {
            Outcome scenario = scenarioOutcome(intent, lang, settings, ctx);
            if (scenario != null) {
                return scenario;
            }
        }
        if (!operatorDraft && request.quickReply() == null && ctx.comparative() && ctx.signals().hasComparePair()
                && scenarioApplies(def) && !hasSeveralStores(text) && !responseRouter.allows(ResponseRouter.LlmReason.COMPARATIVE)) {
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
            return catalogOutcome(browse, text, lang, request, settings, operatorDraft, ctx);
        }
        if (!operatorDraft && ctx.recommendation() && !responseRouter.allows(ResponseRouter.LlmReason.RECOMMENDATION)
                && (def.role() == Role.SEARCH || def.role() == Role.OFF_TOPIC)
                && (intent.query() == null || intent.query().isBlank())) {
            Outcome deals = bestDealsOutcome(intent, lang, settings);
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
        RetrievalPlan plan = plan(intent, lang, ctx.signals());
        List<Candidate> candidates = retriever.retrieve(plan);
        Ranker.Options options = Ranker.Options.from(settings, Math.max(maxCards, LLM_ITEMS));
        List<RankedItem> ranked = signalFilter(relevant(ranker.rank(candidates, options, today), intent), ctx.signals(), today);
        CompanyRef company = intent.company();
        RankedItem companyItem = company == null ? null : companyItem(company);
        LadderResult ladder = LadderResult.EMPTY;
        if (ranked.isEmpty() && intent.quickReply() == null) {
            ladder = ladder(plan, options, today, ctx.signals());
            ranked = ladder.items();
        }
        if (ranked.isEmpty()) {
            boolean hasQuery = intent.query() != null && !intent.query().isBlank();
            if (ctx.recommendation() && !operatorDraft && !responseRouter.allows(ResponseRouter.LlmReason.RECOMMENDATION)) {
                Outcome deals = bestDealsOutcome(intent, lang, settings);
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
        chosen = ctx.signals().sort() == null ? Ranker.displayOrder(chosen) : chosen;
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
            if (message == null) {
                message = templateHeader(intent, lang, chosen, ctx.signals());
            }
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
                .route(usedLlm ? AiReply.ROUTE_LLM : AiReply.ROUTE_SEARCH);
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
        String links = categoryLinks(lang, 6);
        return new Outcome(message + links, List.of(), Math.min(intent.confidence(), 0.35), false, null)
                .route(AiReply.ROUTE_SEARCH).flag(AiReply.FLAG_NO_RESULTS);
    }

    private String categoryLinks(String lang, int limit) {
        IntentDef list = catalog.first(Role.CATEGORY_LIST);
        List<CategoryRef> all;
        try {
            all = directory.categories();
        } catch (RuntimeException e) {
            all = List.of();
        }
        if (all == null || all.isEmpty()) {
            return "";
        }
        List<String> taxonomies = list == null || list.taxonomies() == null || list.taxonomies().isEmpty() ? null : list.taxonomies();
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (CategoryRef c : all) {
            if (n >= limit) {
                break;
            }
            if (taxonomies != null && !taxonomies.contains(c.taxonomy())) {
                continue;
            }
            String label = c.label(lang);
            String path = categoryPath(c);
            if (label == null || path == null || languages.genericCategoryWords().contains(TextNormalizer.fold(label))) {
                continue;
            }
            sb.append("\n- [").append(label.replace("[", "").replace("]", "")).append("](").append(path).append(")");
            n++;
        }
        return sb.toString();
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
            List<RankedItem> sorted = new ArrayList<>(items);
            sorted.sort(java.util.Comparator.comparingDouble(i -> i.candidate().price() == null ? Double.MAX_VALUE : i.candidate().price()));
            perStore.put(c, sorted);
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
        if (cheapest != null && withItems >= 2) {
            String title = cheapest.candidate().title(lang);
            sb.append("\n\n").append(languages.format("compare_cheapest", lang, title == null ? "" : title,
                    cheapestStore.name(), priceText(cheapest.candidate().price(), lang)));
        }
        Outcome out = new Outcome(sb.toString(), groupedCards(perStore, lang, maxCards, today), withItems > 0 ? 0.8 : 0.4,
                false, call.result()).route(AiReply.ROUTE_SEARCH);
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
            if (t.length() >= 3 && !languages.isStopword(t)) {
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
            if (best.sourceKey() != null && !best.sourceKey().equals(preferred) && specific(best, text, hits.size() == 1 ? 1 : 2)) {
                Outcome out = new Outcome(knowledgeExcerpt(best, lang), List.of(), intent.confidence(), false, llmResult)
                        .route(AiReply.ROUTE_KNOWLEDGE);
                return call.wanted() ? out.flag(AiReply.FLAG_LLM_FALLBACK) : out;
            }
        }
        Outcome out = new Outcome(template, List.of(), intent.confidence(), false, llmResult);
        return call.wanted() ? out.flag(AiReply.FLAG_LLM_FALLBACK) : out;
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
        if (s.minDiscount() != null || s.sort() != null) {
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
        java.util.Map<String, List<RankedItem>> perTerm = new java.util.LinkedHashMap<>();
        for (String term : terms) {
            if (excludedText(term, ctx.signals().excluded())) {
                continue;
            }
            RetrievalPlan p = new RetrievalPlan(intent.intent() == null ? null : intent.intent().id(), types, term, lang,
                    company, null, false, 6, null, budget, true, intent.place(), RetrievalPlan.RELAX_NONE,
                    ctx.signals().minDiscount(), null);
            List<RankedItem> items = signalFilter(relevantTo(rankSafely(p, options, today), term), ctx.signals(), today);
            if (items.isEmpty()) {
                items = signalFilter(looseRelevant(rankSafely(p.withRelax(RetrievalPlan.RELAX_VARIANTS), options, today), term),
                        ctx.signals(), today);
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
            perTerm.put(term, priced);
        }
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
                    priceText(c.price(), lang), discount)).append(where(c, lang));
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
        return new Outcome(sb.toString(), cards, 0.85, false, null).route(AiReply.ROUTE_SEARCH);
    }

    private String itemLink(Candidate c, String lang) {
        String title = c.title(lang);
        String clean = title == null ? "" : title.replace("[", "").replace("]", "").trim();
        if (c.path() == null || !outputGuard.isInternalPath(c.path())) {
            return clean;
        }
        return languages.format("item_link", lang, clean, c.path());
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
        java.util.Map<String, List<RankedItem>> candidates = new java.util.LinkedHashMap<>();
        for (ChatLanguages.BasketItem line : lines) {
            RetrievalPlan p = new RetrievalPlan(intent.intent() == null ? null : intent.intent().id(), types, line.term(), lang,
                    company, null, false, 8, null, null, true, intent.place(), RetrievalPlan.RELAX_NONE,
                    ctx.signals().minDiscount(), null);
            List<RankedItem> items = signalFilter(relevantTo(rankSafely(p, options, today), line.term()), ctx.signals(), today);
            if (items.isEmpty()) {
                items = signalFilter(looseRelevant(rankSafely(p.withRelax(RetrievalPlan.RELAX_VARIANTS), options, today),
                        line.term()), ctx.signals(), today);
            }
            candidates.put(line.term(), items);
        }
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
        List<RankedItem> chosen = new ArrayList<>();
        Set<String> allowed = new HashSet<>();
        for (ShoppingPlanner.Picked picked : plan.picked()) {
            Candidate c = picked.offer().item().candidate();
            sb.append("\n").append(languages.format("basket_line", lang, picked.line().term(),
                    ShoppingPlanner.quantity(ShoppingPlanner.round2(picked.need()), picked.line().unit()), itemLink(c, lang),
                    picked.offer().packs(), priceText(c.price(), lang), priceText(picked.offer().subtotal(), lang), where(c, lang)));
            if (allowed.add(c.key())) {
                chosen.add(picked.offer().item());
            }
        }
        sb.append("\n\n");
        if (budget != null) {
            sb.append(languages.format("basket_total", lang, priceText(plan.total(), lang), priceText(budget, lang),
                    priceText(ShoppingPlanner.round2(budget - plan.total()), lang)));
        } else {
            sb.append(languages.format("basket_total_plain", lang, priceText(plan.total(), lang)));
        }
        if (!plan.skipped().isEmpty()) {
            sb.append("\n").append(languages.format("basket_skipped", lang, String.join(", ", plan.skipped())));
        }
        if (!plan.missing().isEmpty()) {
            sb.append("\n").append(languages.format("basket_missing", lang, String.join(", ", plan.missing())));
        }
        sb.append("\n").append(languages.template("basket_note", lang));
        int maxCards = Math.min(10, Math.max(settings.getMaxCards() > 0 ? settings.getMaxCards() : 5, chosen.size()));
        List<ChatCard> cards = outputGuard.verifyCards(cardFactory.cards(chosen.size() > maxCards ? chosen.subList(0, maxCards)
                : chosen, lang), allowed, today);
        return new Outcome(sb.toString(), cards, 0.85, false, null).route(AiReply.ROUTE_SEARCH);
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
                List<RankedItem> items = signalFilter(relevantTo(rankSafely(p, options, today), name), ctx.signals(), today);
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
        return new Outcome(sb.toString(), cards, 0.85, false, null).route(AiReply.ROUTE_SEARCH);
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
        return new Outcome(sb.toString(), cards, 0.8, false, null).route(AiReply.ROUTE_SEARCH);
    }

    private static double discountOf(RankedItem item) {
        return item == null || item.candidate().discount() == null || item.candidate().discount() > 99 ? 0
                : item.candidate().discount();
    }

    private Outcome bestDealsOutcome(IntentResult intent, String lang, ChatSettingsEntity settings) {
        LocalDate today = LocalDate.now();
        List<RankedItem> items = recommendationItems(lang, settings, today);
        if (items.isEmpty()) {
            return null;
        }
        int maxCards = settings.getMaxCards() > 0 ? Math.min(settings.getMaxCards(), 10) : 5;
        List<RankedItem> chosen = Ranker.displayOrder(items.size() > maxCards ? items.subList(0, maxCards) : items);
        Set<String> allowed = new HashSet<>();
        for (RankedItem r : chosen) {
            allowed.add(r.candidate().key());
        }
        List<ChatCard> cards = outputGuard.verifyCards(cardFactory.cards(chosen, lang), allowed, today);
        if (cards.isEmpty()) {
            return null;
        }
        return new Outcome(languages.template("recommend_header", lang), cards, Math.max(0.6, intent.confidence()), false, null)
                .route(AiReply.ROUTE_SEARCH);
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
