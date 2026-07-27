package com.naqqa.outreach.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.naqqa.outreach.config.OutreachProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Generates the outreach email via the local Ollama model — faithful port of the chat call in the script. */
@Service
@RequiredArgsConstructor
@Slf4j
public class OllamaClient {

    public record GeneratedEmail(boolean shouldSend, String skipReason, String language, String subject, String emailBody) {
    }

    /** One earlier message in a thread, oldest first, fed to the follow-up generator as context. */
    public record ThreadMessage(int step, String subject, String body) {
    }

    private static final String FOLLOWUP_SYSTEM_PROMPT = """
            You write short B2B follow-up emails as a REPLY within an existing cold-outreach thread for
            a company offering IT outstaffing services (dedicated developers / dedicated teams / team
            extension / IT staffing only).

            You are given the full thread so far (our previous message(s) to this contact) and the
            follow-up level. Write the next follow-up as a natural, polite reply that:
            - Clearly refers to the previous message without repeating it.
            - Is SHORTER than the original, adds a light, low-pressure nudge, no guilt-tripping.
            - Escalates gently: level 1 = gentle bump; higher levels = final, respectful note that you
              will stop following up.
            - Uses the SAME language as the previous messages (en / ro / ru).
            - Plain text only. No HTML, links, attachments, signature, sender name, or sign-off.
            - Starts with "Hello," (or "Hello, [FirstName]," only if a clear first name is known).
            - Max 90 words, 2-4 short paragraphs.
            - OPTIONAL (only in ~1 of 3 follow-ups, understated, never salesy): remind them of ONE
              differentiator not stressed before — either that our developers work WHITE-LABEL under
              their own brand as an extension of their team, OR that Eastern-European rates run
              roughly 30-60% below typical Western budgets. Never a headline, never a guarantee.

            Never use: guarantee, proven, world-class, boost, revolutionary, cutting-edge,
            game-changer, leverage, touch base, circle back, at your convenience, brief chat, dear sir,
            dear madam. Do not invent facts. Only allowed services above.

            Return ONLY valid JSON: { "emailBody": string }  (emailBody must not be empty).
            """;

    /** Generates the next follow-up body (a reply) from the whole thread history. Null on failure. */
    public String generateFollowup(String companyName, List<String> companyInfo,
                                   List<ThreadMessage> thread, int followupLevel) {
        StringBuilder history = new StringBuilder();
        for (ThreadMessage m : thread) {
            history.append("--- Message (step ").append(m.step()).append(") ---\n")
                    .append("Subject: ").append(nz(m.subject())).append("\n")
                    .append(nz(m.body())).append("\n\n");
        }
        String infoJson;
        try {
            infoJson = mapper.writeValueAsString(companyInfo == null ? List.of()
                    : companyInfo.stream().limit(5).toList());
        } catch (Exception e) {
            infoJson = "[]";
        }

        String userMsg = "Write follow-up level " + followupLevel + " as a reply in this thread.\n\n"
                + "Company: " + nz(companyName) + "\n"
                + "Company info: " + infoJson + "\n\n"
                + "Thread so far (oldest first):\n" + history + "\n"
                + "Return JSON only: {\"emailBody\": \"...\"}.";

        Map<String, Object> body = Map.of(
                "model", props.getOllamaModel(),
                "stream", false,
                "format", "json",
                "messages", List.of(
                        Map.of("role", "system", "content", FOLLOWUP_SYSTEM_PROMPT),
                        Map.of("role", "user", "content", userMsg)));

        JsonNode res = chat(body);
        String content = res == null ? "{}" : res.path("message").path("content").asText("{}");
        try {
            String out = mapper.readTree(content).path("emailBody").asText("");
            return out == null || out.isBlank() ? null : out;
        } catch (Exception e) {
            log.warn("Ollama follow-up unparseable: {}", e.getMessage());
            return null;
        }
    }

    private final OutreachProperties props;
    private final OllamaThrottle throttle;
    private final ObjectMapper mapper = new ObjectMapper();

    /** One inbound email to classify (subject + body). */
    public record ReplyInput(String subject, String body) {
    }

    private static final String CLASSIFY_SYSTEM_PROMPT = """
            You classify inbound emails that arrived in reply to our cold B2B outreach. For EACH
            numbered email decide ONE category. Return ONLY JSON with one entry per input, SAME order:
            { "results": [ { "i": 1, "category": "UNSUBSCRIBE" }, { "i": 2, "category": "OTHER" } ] }.

            - UNSUBSCRIBE: the person explicitly asks to stop being contacted — unsubscribe, remove me,
              do not email/contact me, opt out, stop emailing, take me off your list.
            - BOUNCE: an AUTOMATED delivery-failure / system notice — mailer-daemon, undeliverable,
              address not found, mailbox full/over quota, delivery status notification, message blocked.
            - OTHER: any genuine human reply (interested, not interested, questions, out-of-office, etc.).

            A plain "not interested" WITHOUT a request to stop is OTHER, not UNSUBSCRIBE.
            When unsure, choose OTHER.
            """;

    /**
     * Classify a BATCH of inbound replies in ONE model call — returns a category
     * (UNSUBSCRIBE / BOUNCE / OTHER) per input, aligned by index. Any entry that can't be parsed
     * defaults to "OTHER" (safe — a genuine reply just goes to manual triage, never wrongly acted on).
     */
    public List<String> classifyReplies(List<ReplyInput> items) {
        List<String> out = new ArrayList<>();
        if (items == null || items.isEmpty()) {
            return out;
        }
        StringBuilder user = new StringBuilder("Classify these ").append(items.size()).append(" emails.\n\n");
        for (int i = 0; i < items.size(); i++) {
            ReplyInput it = items.get(i);
            user.append("Email ").append(i + 1).append(":\n")
                    .append("Subject: ").append(nz(it.subject())).append("\n")
                    .append("Body:\n").append(it.body() == null ? "" : it.body()).append("\n\n");
        }
        user.append("Return JSON only: {\"results\":[{\"i\":1,\"category\":\"UNSUBSCRIBE|BOUNCE|OTHER\"}, ...]} ")
                .append("with exactly ").append(items.size()).append(" entries in order.");

        Map<String, Object> req = Map.of(
                "model", props.getOllamaModel(),
                "stream", false,
                "format", "json",
                "messages", List.of(
                        Map.of("role", "system", "content", CLASSIFY_SYSTEM_PROMPT),
                        Map.of("role", "user", "content", user.toString())));

        String[] cats = new String[items.size()];
        java.util.Arrays.fill(cats, "OTHER");
        JsonNode res = chat(req);
        if (res != null) {
            try {
                JsonNode content = mapper.readTree(res.path("message").path("content").asText("{}"));
                for (JsonNode r : content.path("results")) {
                    int idx = r.path("i").asInt(0) - 1;
                    String c = r.path("category").asText("OTHER").trim().toUpperCase();
                    if (idx >= 0 && idx < cats.length && ("UNSUBSCRIBE".equals(c) || "BOUNCE".equals(c))) {
                        cats[idx] = c;
                    }
                }
            } catch (Exception e) {
                log.warn("🤖 [AI] batch classification unparseable: {}", e.getMessage());
            }
        }
        for (String c : cats) {
            out.add(c);
        }
        return out;
    }

    private static final String SYSTEM_PROMPT = """
            You write B2B cold outreach emails for a company offering IT outstaffing services.

            IMPORTANT:
            Always create an email ready to send.
            Do NOT decide whether to send.
            Do NOT skip the lead.
            Do NOT return shouldSend=false.
            Do NOT return {}.

            Return ONLY valid JSON:
            {
              "shouldSend": true,
              "skipReason": null,
              "language": "en" | "ro" | "ru",
              "subject": string,
              "emailBody": string
            }

            FIELD RULES:
            - shouldSend must always be true.
            - skipReason must always be null.
            - language must be "en", "ro", or "ru".
            - subject must not be empty.
            - emailBody must not be empty.

            LANGUAGE:
            - Company info mainly Romanian -> write in Romanian and set language = "ro".
            - Company info mainly Russian -> write in Russian and set language = "ru".
            - Otherwise -> write in English and set language = "en".
            - Do not mix languages.

            SUBJECT:
            - Max 50 characters.
            - 2-6 words.
            - Neutral and professional.
            - Do NOT include the recipient company name.
            - Do NOT include Naqqa or Naqqa Software.
            - Good directions:
              "Team extension"
              "Engineering capacity"
              "Development team support"
              "Dedicated developers"
              "IT staffing support"
              "Extra development capacity"

            UNIQUENESS:
            Every email must feel individually written.
            Vary sentence length, opener, wording, CTA, and service framing.
            Do not copy the example structure too closely.
            Do not reuse the same phrases repeatedly.

            TONE:
            - Professional, formal, natural.
            - No hype. No buzzwords. No marketing cliches.
            - No guarantees, no ROI/speed promises. ONE measured, factual cost-advantage mention is
              allowed (see DIFFERENTIATORS) — understated, never salesy.
            - Do not invent facts.
            - Reference only one detail clearly supported by company data.

            ALLOWED SERVICES ONLY:
            - IT outstaffing
            - dedicated developers
            - dedicated teams
            - team extension
            - IT staffing
            - white-label dedicated developers / teams (they work under YOUR brand and process,
              as an extension of your own team)

            FORBIDDEN SERVICES:
            Never mention or imply: custom software development, end-to-end delivery, consulting,
            system integration, implementation, technical support, QA, DevOps, UI/UX design,
            product development, managed services.

            DIFFERENTIATORS (weave in naturally where the offer is made — never a bullet list,
            never in every email, keep it understated and human):
            - White-label: our developers work under YOUR brand, inside your own process, tools and
              delivery — a true extension of your team, invisible to your end clients.
            - Cost: Eastern-European rates typically run ~30-60% below Western-European / US budgets
              for equivalent seniority. You MAY reference this cost advantage AT MOST ONCE, phrased
              factually and understated (e.g. "at Eastern-European rates, roughly 30-60% below typical
              Western budgets" or "trim engineering costs by 30-60%"). Never a headline, never a
              guarantee.
            Use AT MOST ONE differentiator per email (white-label OR cost, not both, not every time).

            GREETING:
            Paragraph 1 only.
            - If Contact name contains a clear human first name -> "Hello, [FirstName],"
            - If uncertain or empty -> exactly "Hello,"
            - Never use full name.
            - Never guess from role emails like info@, sales@, hello@, support@, admin@, office@, team@, hr@.

            COMPANY NAME RULE:
            You receive companyNameConfidence: high, medium, or low.
            - high -> you may use the company name once in emailBody.
            - medium or low -> do not use company name; say "your company", "your team", or "your engineering team".
            - Never use company name more than once.

            EMAIL BODY:
            Plain text only. No HTML. No links. No attachments. No signature. No sender name.
            No sender role. No company website. No closing/sign-off.
            Exactly 5 short paragraphs. Max 135 words total.

            STRUCTURE:
            P1: Greeting only.
            P2: Open with interest in potential collaboration and mention one specific company detail from input.
                Vary using openerVariant: collaboration, company-detail-first, team-extension, staffing-partner, engineering-capacity.
            P3: Introduce our background. Mention that we are an IT Park-resident company from Eastern Europe,
                with people in the Republic of Moldova and Romania. Vary the angle naturally.
            P4: Present the service offer as a proposal. Use only allowed services. Where it fits
                naturally, weave in ONE differentiator (white-label OR the 30-60% cost advantage — not
                both, and not in every email) so it reads human, not salesy. Vary phrasing based on styleVariant.
            P5: CTA. Invite a reply or further conversation. Do not ask to schedule/book a meeting.
                Do not say "at your convenience" or "brief chat". Vary using ctaVariant.

            STYLE VARIANTS:
            - direct: simple and short
            - soft: warmer but still professional
            - operator: practical and business-like
            - peer-to-peer: natural founder-to-founder tone
            - capacity-focused: focus on extra engineering capacity
            - engineering-focused: focus on developers/team extension

            HARD BANS:
            Never use: guarantee, guaranteed, proven, world-class, top-tier, industry-leading, best in class,
            boost, revolutionary, cutting-edge, game-changer, disruptive,
            potential partnership, synergies, seamlessly, impressed by, at your convenience, brief chat,
            I am excited, I am thrilled, touch base, circle back, move the needle, leverage, bandwidth,
            pain points, unlock, transform, empower, elevate, robust, tailored solutions, reach out,
            warm wishes, dear sir, dear madam.
            """;

    private static final String FEWSHOT_USER = """
            Company: Generic Software Studio
            Industry: software development
            Country: NL
            Company info: ["Custom software solutions for SMEs", "Agile teams across Europe"]
            Contact name: john smith
            Contact title: CTO
            Email: john.smith@genericsoftware.nl
            companyNameConfidence: high
            styleVariant: direct
            openerVariant: company-detail-first
            ctaVariant: ask-relevance
            """;

    private static final String FEWSHOT_ASSISTANT =
            "{\"shouldSend\":true,\"skipReason\":null,\"language\":\"en\",\"subject\":\"Team extension\","
                    + "\"emailBody\":\"Hello, John,\\n\\nGeneric Software Studio's work with agile teams across Europe "
                    + "made me think there could be room for a practical collaboration.\\n\\nWe are an IT Park-resident "
                    + "company from Eastern Europe, with people in the Republic of Moldova and Romania.\\n\\nOur focus is "
                    + "IT outstaffing, dedicated developers, dedicated teams, and team extension for companies that already "
                    + "have their own delivery structure.\\n\\nWould this be relevant for your team?\"}";

    public GeneratedEmail generate(String companyName, String industry, String country,
                                   List<String> companyInfo, String contactName, String contactTitle,
                                   String email, String companyNameConfidence) {
        String style = pick(OutreachConstants.STYLE_VARIANTS);
        String opener = pick(OutreachConstants.OPENER_VARIANTS);
        String cta = pick(OutreachConstants.CTA_VARIANTS);

        String infoJson;
        try {
            infoJson = mapper.writeValueAsString(companyInfo == null ? List.of()
                    : companyInfo.stream().limit(5).toList());
        } catch (Exception e) {
            infoJson = "[]";
        }

        boolean noInfo = companyInfo == null || companyInfo.stream().noneMatch(s -> s != null && s.trim().length() > 20);
        String userMsg = ("Write a cold outreach email for the following lead.\n\n"
                + "Company: " + nz(companyName) + "\n"
                + "Industry: " + nz(industry) + "\n"
                + "Country: " + nz(country) + "\n"
                + "Company info: " + infoJson + "\n"
                + "Contact name: " + (contactName == null ? "" : contactName) + "\n"
                + "Contact title: " + (contactTitle == null ? "" : contactTitle) + "\n"
                + "Email: " + email + "\n"
                + "companyNameConfidence: " + companyNameConfidence + "\n"
                + "styleVariant: " + style + "\n"
                + "openerVariant: " + opener + "\n"
                + "ctaVariant: " + cta + "\n\n"
                + (noInfo
                    ? "NOTE: No public company info is available. Do NOT invent or assume any company "
                      + "detail. Write a GENERIC version: in P2 open with general interest in a possible "
                      + "collaboration (no specific company fact), and use \"your team\"/\"your company\".\n\n"
                    : "")
                + "Return JSON only.\nAlways set shouldSend=true.\nAlways set skipReason=null.\n"
                + "Create the email ready to send.");

        Map<String, Object> body = Map.of(
                "model", props.getOllamaModel(),
                "stream", false,
                "format", "json",
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", FEWSHOT_USER),
                        Map.of("role", "assistant", "content", FEWSHOT_ASSISTANT),
                        Map.of("role", "user", "content", userMsg)));

        JsonNode res = chat(body);
        String content = res == null ? "{}" : res.path("message").path("content").asText("{}");
        try {
            JsonNode j = mapper.readTree(content);
            return new GeneratedEmail(
                    j.path("shouldSend").asBoolean(false),
                    j.path("skipReason").isNull() ? null : j.path("skipReason").asText(null),
                    j.path("language").asText("en"),
                    j.path("subject").asText(""),
                    j.path("emailBody").asText(""));
        } catch (Exception e) {
            log.warn("Ollama returned unparseable content: {}", e.getMessage());
            return new GeneratedEmail(false, "parse_error", "en", "", "");
        }
    }

    /**
     * POST to Ollama {@code /api/chat} and parse the JSON body OURSELVES from raw bytes. Ollama behind
     * the Caddy proxy returns {@code Content-Type: application/octet-stream}, which Spring's RestClient
     * can't convert to a JsonNode ("Error while extracting response ... content type octet-stream") —
     * so we read {@code byte[]} (always convertible) and run it through Jackson regardless of the header.
     */
    private JsonNode chat(Map<String, Object> body) {
        RestClient client = ollamaClient();
        // Global chat-lock + cooldown across both profiles (protects the local model).
        byte[] raw = throttle.execute(() -> client.post().uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(byte[].class));
        if (raw == null || raw.length == 0) {
            log.warn("🤖 [AI] Ollama returned an empty body.");
            return null;
        }
        log.info("🤖 [AI] Ollama response received ({} bytes).", raw.length);
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            log.warn("Ollama response not valid JSON ({} bytes): {}", raw.length, e.getMessage());
            return null;
        }
    }

    /** Ollama HTTP client — bearer token (if configured) + connect/read timeouts so it can't hang forever. */
    private RestClient ollamaClient() {
        org.springframework.http.client.SimpleClientHttpRequestFactory rf =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(30_000);
        rf.setReadTimeout((int) Math.min(props.getOllamaTimeoutMs(), Integer.MAX_VALUE));
        return RestClient.builder().baseUrl(props.getOllamaUrl())
                .requestFactory(rf)
                .defaultHeaders(h -> {
                    String token = props.getOllamaToken();
                    if (token != null && !token.isBlank()) {
                        h.setBearerAuth(token.trim());
                    }
                }).build();
    }

    private String pick(List<String> options) {
        return options.get(ThreadLocalRandom.current().nextInt(options.size()));
    }

    private String nz(String s) {
        return s == null || s.isBlank() ? "N/A" : s;
    }
}
