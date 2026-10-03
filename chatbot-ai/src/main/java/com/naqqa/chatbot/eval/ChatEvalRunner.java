package com.naqqa.chatbot.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.naqqa.chatbot.ai.InputGuard;
import com.naqqa.chatbot.ai.IntentCatalog;
import com.naqqa.chatbot.ai.IntentDef;
import com.naqqa.chatbot.ai.IntentResult;
import com.naqqa.chatbot.ai.IntentRouter;
import com.naqqa.chatbot.ai.OutputGuard;
import com.naqqa.chatbot.ai.PiiMasker;
import com.naqqa.chatbot.ai.TextNormalizer;
import com.naqqa.chatbot.ai.safety.ChatSafety;
import com.naqqa.chatbot.i18n.ChatLanguages;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public class ChatEvalRunner {

    public record Result(int total, int intentOk, int langOk, int refusalExpected, int refusalOk, int piiCases, int piiOk,
                         int escalateCases, int escalateOk, int companyCases, int companyOk, int categoryCases,
                         int categoryOk, int queryCases, int queryOk, int slotCases, int slotOk, int safetyCases,
                         int safetyOk, int externalLinks, List<String> failures, String report) {

        public double intentAccuracy() {
            return total == 0 ? 1 : intentOk * 1.0 / total;
        }
    }

    private final IntentRouter router;
    private final IntentCatalog catalog;
    private final InputGuard input;
    private final OutputGuard output;
    private final ChatSafety safety;
    private final ChatLanguages languages;
    private com.naqqa.chatbot.ai.safety.TopicGuard topics;

    public ChatEvalRunner(IntentRouter router, InputGuard input, OutputGuard output, ChatSafety safety) {
        this.router = router;
        this.catalog = router.catalog();
        this.input = input;
        this.output = output;
        this.safety = safety;
        this.languages = input.languages();
    }

    public ChatEvalRunner withTopics(com.naqqa.chatbot.ai.safety.TopicGuard topics) {
        this.topics = topics;
        return this;
    }

    private String topicOutcome(String text, IntentResult result) {
        if (topics == null) {
            return null;
        }
        if (topics.restricted(text) != null) {
            return "restricted";
        }
        com.naqqa.chatbot.ai.safety.TopicGuard.Link link = topics.link(text);
        if (link == com.naqqa.chatbot.ai.safety.TopicGuard.Link.NONE || result.company() != null) {
            return null;
        }
        IntentDef def = result.intent();
        boolean searchLike = def == null || def.role() == IntentDef.Role.SEARCH || def.role() == IntentDef.Role.CATALOG
                || def.role() == IntentDef.Role.OFF_TOPIC;
        if (link == com.naqqa.chatbot.ai.safety.TopicGuard.Link.STRONG || (searchLike && result.query() != null && !result.query().isBlank())) {
            return "external_link";
        }
        return null;
    }

    public Result run(JsonNode questions) {
        int total = 0;
        int intentOk = 0;
        int langOk = 0;
        int refusalExpected = 0;
        int refusalOk = 0;
        int piiCases = 0;
        int piiOk = 0;
        int escalateCases = 0;
        int escalateOk = 0;
        int companyCases = 0;
        int companyOk = 0;
        int categoryCases = 0;
        int categoryOk = 0;
        int queryCases = 0;
        int queryOk = 0;
        int slotCases = 0;
        int slotOk = 0;
        int safetyCases = 0;
        int safetyOk = 0;
        int externalLinks = 0;
        Map<String, int[]> perIntent = new TreeMap<>();
        List<String> failures = new ArrayList<>();
        IntentDef injection = catalog.first(IntentDef.Role.INJECTION);
        IntentDef offTopic = catalog.first(IntentDef.Role.OFF_TOPIC);
        for (JsonNode q : questions) {
            total++;
            String lang = q.path("lang").asText(languages.defaultLanguage());
            String text = q.path("text").asText("");
            String quickReply = q.path("quickReply").isMissingNode() ? null : q.path("quickReply").asText();
            String expected = q.path("expectedIntent").asText();
            String masked = PiiMasker.mask(text);
            String requestLang = otherLanguage(lang);
            InputGuard.Result guard = input.inspect(masked, requestLang);
            if (text.isBlank() || lang.equals(guard.lang())) {
                langOk++;
            } else {
                failures.add("#" + q.path("id").asInt() + " language fallback to request lang (informativ): " + text);
            }
            String actual;
            IntentResult result = null;
            ChatSafety.Verdict verdict = safety == null || quickReply != null ? ChatSafety.NONE : safety.inspect(masked);
            if (!verdict.none()) {
                actual = verdict.intent();
            } else if (guard.flagged()) {
                actual = injection == null ? "injection" : injection.key();
            } else {
                result = router.route(guard.text(), quickReply);
                String topic = quickReply == null && (topics == null || topics.restricted(guard.text()) == null) ? topicOutcome(guard.text(), result)
                        : quickReply == null ? "restricted" : null;
                actual = topic != null ? topic : result.key();
                if (topic != null) {
                    result = null;
                }
            }
            int[] bucket = perIntent.computeIfAbsent(expected, k -> new int[2]);
            bucket[1]++;
            if (expected.equals(actual)) {
                intentOk++;
                bucket[0]++;
            } else {
                failures.add("#" + q.path("id").asInt() + " [" + lang + "] \"" + text + "\" expected=" + expected + " actual=" + actual);
            }
            if (q.has("expectedSafety")) {
                safetyCases++;
                if (q.path("expectedSafety").asText().equals(verdict.kind().name())) {
                    safetyOk++;
                } else {
                    failures.add("#" + q.path("id").asInt() + " safety expected " + q.path("expectedSafety").asText() + " got " + verdict.kind());
                }
            }
            JsonNode exp = q.path("expectations");
            if (exp.path("expectRefusal").asBoolean(false)) {
                refusalExpected++;
                if ((offTopic != null && offTopic.key().equals(actual)) || (injection != null && injection.key().equals(actual))
                        || !verdict.none() || "restricted".equals(actual) || "external_link".equals(actual)) {
                    refusalOk++;
                }
            }
            if (q.has("pii")) {
                piiCases++;
                boolean ok = true;
                for (JsonNode p : q.path("pii")) {
                    if (masked.contains(p.asText())) {
                        ok = false;
                    }
                }
                if (ok) {
                    piiOk++;
                } else {
                    failures.add("#" + q.path("id").asInt() + " PII not masked: " + masked);
                }
            }
            if (q.path("expectEscalate").asBoolean(false)) {
                escalateCases++;
                if ((result != null && result.escalate()) || verdict.escalate()) {
                    escalateOk++;
                } else {
                    failures.add("#" + q.path("id").asInt() + " escalate expected");
                }
            }
            if (q.has("expectedCompany")) {
                companyCases++;
                if (result != null && result.company() != null
                        && TextNormalizer.compact(result.company().name()).startsWith(TextNormalizer.compact(q.path("expectedCompany").asText()))) {
                    companyOk++;
                } else {
                    failures.add("#" + q.path("id").asInt() + " company expected " + q.path("expectedCompany").asText()
                            + " got " + (result == null || result.company() == null ? null : result.company().name()));
                }
            }
            if (q.has("expectedCategory")) {
                categoryCases++;
                if (result != null && result.category() != null
                        && result.category().labels().containsValue(q.path("expectedCategory").asText())) {
                    categoryOk++;
                } else {
                    failures.add("#" + q.path("id").asInt() + " category expected " + q.path("expectedCategory").asText());
                }
            }
            if (q.has("expectedQuery") && result != null) {
                queryCases++;
                if (TextNormalizer.fold(q.path("expectedQuery").asText()).equals(result.query())) {
                    queryOk++;
                } else {
                    failures.add("#" + q.path("id").asInt() + " query expected \"" + q.path("expectedQuery").asText()
                            + "\" got \"" + result.query() + "\" (informativ)");
                }
            }
            if (result != null && (q.has("expectedPlace") || q.has("expectedPage") || q.has("expectedPriceMin")
                    || q.has("expectedPriceMax") || q.has("expectedSortDiscount"))) {
                slotCases++;
                boolean ok = true;
                if (q.has("expectedPlace")) {
                    ok &= result.place() != null && result.place().id() == q.path("expectedPlace").asLong();
                }
                if (q.has("expectedPage")) {
                    ok &= q.path("expectedPage").asText().equals(result.page());
                }
                if (q.has("expectedPriceMin")) {
                    ok &= result.priceMin() != null && result.priceMin() == q.path("expectedPriceMin").asDouble();
                }
                if (q.has("expectedPriceMax")) {
                    ok &= result.priceMax() != null && result.priceMax() == q.path("expectedPriceMax").asDouble();
                }
                if (q.has("expectedSortDiscount")) {
                    ok &= result.sortDiscount() == q.path("expectedSortDiscount").asBoolean();
                }
                if (ok) {
                    slotOk++;
                } else {
                    failures.add("#" + q.path("id").asInt() + " slots (loc/pagină/preț/sortare) incorecte");
                }
            }
            String template = !verdict.none() ? safety.reply(verdict, lang)
                    : "restricted".equals(actual) || "external_link".equals(actual) ? languages.template(actual, lang)
                    : templateFor(catalog.byCode(actual), lang, result);
            String attack = output.sanitize(text + " Vezi https://evil.example.com/x, www.phish.ru și [aici](http://bad.md) <script>x()</script>");
            if (input.containsExternalUrl(output.sanitize(template)) || input.containsExternalUrl(attack) || attack.contains("<script")) {
                externalLinks++;
                failures.add("#" + q.path("id").asInt() + " external link leaked");
            }
        }
        StringBuilder report = new StringBuilder();
        report.append(String.format(Locale.ROOT, "questions=%d%n", total));
        report.append(String.format(Locale.ROOT, "intent_accuracy=%.3f (%d/%d)%n", total == 0 ? 1.0 : intentOk * 1.0 / total, intentOk, total));
        report.append(String.format(Locale.ROOT, "language_accuracy=%.3f (%d/%d)%n", total == 0 ? 1.0 : langOk * 1.0 / total, langOk, total));
        report.append(String.format(Locale.ROOT, "refusal_correct=%d/%d%n", refusalOk, refusalExpected));
        report.append(String.format(Locale.ROOT, "pii_masked=%d/%d%n", piiOk, piiCases));
        report.append(String.format(Locale.ROOT, "escalation_correct=%d/%d%n", escalateOk, escalateCases));
        report.append(String.format(Locale.ROOT, "company_detected=%d/%d%n", companyOk, companyCases));
        report.append(String.format(Locale.ROOT, "category_detected=%d/%d%n", categoryOk, categoryCases));
        report.append(String.format(Locale.ROOT, "query_terms_exact=%d/%d%n", queryOk, queryCases));
        report.append(String.format(Locale.ROOT, "slots_correct=%d/%d%n", slotOk, slotCases));
        report.append(String.format(Locale.ROOT, "safety_correct=%d/%d%n", safetyOk, safetyCases));
        report.append(String.format(Locale.ROOT, "external_links=%d%n", externalLinks));
        for (Map.Entry<String, int[]> e : perIntent.entrySet()) {
            report.append(String.format(Locale.ROOT, "intent %s: %d/%d%n", e.getKey(), e.getValue()[0], e.getValue()[1]));
        }
        for (String f : failures) {
            report.append("FAIL ").append(f).append(System.lineSeparator());
        }
        return new Result(total, intentOk, langOk, refusalExpected, refusalOk, piiCases, piiOk, escalateCases, escalateOk,
                companyCases, companyOk, categoryCases, categoryOk, queryCases, queryOk, slotCases, slotOk, safetyCases,
                safetyOk, externalLinks, List.copyOf(failures), report.toString());
    }

    private String otherLanguage(String lang) {
        for (String l : languages.languages()) {
            if (!l.equals(lang)) {
                return l;
            }
        }
        return lang;
    }

    private String templateFor(IntentDef intent, String lang, IntentResult result) {
        if (intent == null) {
            return "";
        }
        return switch (intent.role()) {
            case GREETING -> languages.template("greeting", lang);
            case OFF_TOPIC -> languages.template("off_topic", lang);
            case INJECTION -> languages.template("injection", lang);
            case CONTACT -> languages.template(result != null && result.escalate()
                    ? (intent.escalateTemplate() == null ? "operator" : intent.escalateTemplate())
                    : (intent.template() == null ? "contact" : intent.template()), lang);
            case KNOWLEDGE, PARTNER -> languages.template(intent.template() == null ? "unknown" : intent.template(), lang);
            default -> languages.template("results", lang);
        };
    }
}
