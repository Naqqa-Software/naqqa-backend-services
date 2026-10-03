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

    private record Ctx(boolean comparative, boolean followUp, boolean carried, double threshold, boolean operatorDraft) {
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
        if (followUp) {
            String previous = lastVisitorText(request.history());
            if (previous != null && !previous.isBlank() && !previous.trim().equals(guard.text().trim())) {
                try {
                    IntentResult combined = router.route(TextNormalizer.clean(PiiMasker.mask(previous)) + " " + guard.text(), null);
                    if (combined.intent() != null && combined.intent().isCatalog()) {
                        intent = combined;
                        carried = true;
                    }
                } catch (RuntimeException ignored) {
                }
            }
        }
        Ctx ctx = new Ctx(quickReply == null && responseRouter.comparative(guard.text()), followUp, carried,
                ResponseRouter.threshold(settings.getLlmConfidenceThreshold()), operatorDraft);
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
            return ResponseRouter.LlmReason.KNOWLEDGE_SYNTHESIS;
        }
        if (intent.browse() || intent.quickReply() != null) {
            return null;
        }
        if (ctx.comparative() && responseRouter.allows(ResponseRouter.LlmReason.COMPARATIVE)) {
            return ResponseRouter.LlmReason.COMPARATIVE;
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
        RetrievalPlan plan = plan(intent, lang);
        List<Candidate> candidates = retriever.retrieve(plan);
        LocalDate today = LocalDate.now();
        Ranker.Options options = Ranker.Options.from(settings, Math.max(maxCards, LLM_ITEMS));
        List<RankedItem> ranked = relevant(ranker.rank(candidates, options, today), intent);
        CompanyRef company = intent.company();
        RankedItem companyItem = company == null ? null : companyItem(company);
        if (ranked.isEmpty()) {
            if (def.role() == Role.SEARCH && !intent.query().isBlank()) {
                List<KnowledgeHit> hits = knowledge.search(text, lang, null, 3);
                if (!hits.isEmpty() && hits.get(0).score() >= 2) {
                    return new Outcome(knowledgeExcerpt(hits.get(0), lang), List.of(), 0.5, false, null)
                            .route(AiReply.ROUTE_KNOWLEDGE).flag(AiReply.FLAG_NO_RESULTS);
                }
            }
            boolean hasQuery = intent.query() != null && !intent.query().isBlank();
            if (hasQuery && intent.quickReply() == null && responseRouter.allows(ResponseRouter.LlmReason.NO_RESULTS)) {
                LlmCall call = callLlm(settings, lang, text, intent, request, List.of(), List.of(), operatorDraft);
                Outcome viaLlm = llmOutcome(call, Math.min(intent.confidence(), 0.35));
                if (viaLlm != null) {
                    return viaLlm.flag(AiReply.FLAG_NO_RESULTS);
                }
                if (call.failed()) {
                    return emptyOutcome(intent, def, company, companyItem, lang).flag(AiReply.FLAG_LLM_FALLBACK);
                }
            }
            return emptyOutcome(intent, def, company, companyItem, lang);
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
                if (!llmResult.ids().isEmpty()) {
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
        chosen = Ranker.displayOrder(chosen);
        List<ChatCard> cards = cardFactory.cards(chosen, lang);
        if (companyItem != null && cards.size() < maxCards) {
            ChatCard companyCard = cardFactory.card(companyItem, lang);
            if (companyCard != null) {
                cards.add(companyCard);
            }
        }
        cards = outputGuard.verifyCards(cards, allowed, today);
        boolean usedLlm = message != null;
        if (message == null) {
            message = templateHeader(intent, lang, chosen);
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
        if (synthesis && responseRouter.allows(ResponseRouter.LlmReason.KNOWLEDGE_SYNTHESIS)) {
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
        if (!settings.isLlmEnabled() || llm == null) {
            return LlmCall.NONE;
        }
        try {
            if (!llm.isEnabled()) {
                return LlmCall.NONE;
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
            return new LlmCall(result, true, result == null);
        } catch (RuntimeException e) {
            log.warn("[chatbot] llm call failed, search-only reply: {}", e.getMessage());
            return new LlmCall(null, true, true);
        } finally {
            gate.release();
        }
    }

    RetrievalPlan plan(IntentResult intent, String lang) {
        String q = intent.query() == null ? "" : intent.query();
        boolean hasQuery = !q.isBlank();
        Long company = intent.company() == null ? null : intent.company().id();
        boolean priced = intent.hasPrice();
        List<String> requested = types(intent, hasQuery, priced, company != null);
        List<String> types = retriever.usable(requested, priced);
        int perType = requested.size() > 4 ? 6 : hasQuery ? 8 : 10;
        return new RetrievalPlan(intent.intent() == null ? null : intent.intent().id(), types, q, lang, company,
                intent.category(), !hasQuery, perType, intent.priceMin(), intent.priceMax(), intent.sortDiscount(),
                intent.place());
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

    private String templateHeader(IntentResult intent, String lang, List<RankedItem> items) {
        String q = intent.query() == null ? "" : intent.query().trim();
        String header;
        if (intent.company() != null) {
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
        String safe = outputGuard.sanitize(outcome.text());
        if (safe.isBlank()) {
            safe = languages.template("unknown", lang);
        }
        LlmResult llmResult = outcome.llm();
        boolean llmUsed = llmResult != null && llmResult.text() != null && !llmResult.text().isBlank();
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
