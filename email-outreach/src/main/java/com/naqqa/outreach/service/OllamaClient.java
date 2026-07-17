package com.naqqa.outreach.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.naqqa.outreach.config.OutreachProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

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

            Never use: guarantee, proven, world-class, save money, boost, revolutionary, cutting-edge,
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

        RestClient client = ollamaClient();
        JsonNode res = throttle.execute(() -> client.post().uri("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(JsonNode.class));
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
            - No promises. No guarantees. No ROI, savings, speed, or price claims.
            - Do not invent facts.
            - Reference only one detail clearly supported by company data.

            ALLOWED SERVICES ONLY:
            - IT outstaffing
            - dedicated developers
            - dedicated teams
            - team extension
            - IT staffing

            FORBIDDEN SERVICES:
            Never mention or imply: custom software development, end-to-end delivery, consulting,
            system integration, implementation, technical support, QA, DevOps, UI/UX design,
            product development, managed services.

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
            P4: Present the service offer as a proposal. Use only allowed services. Vary phrasing based on styleVariant.
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
            save money, reduce costs, boost, revolutionary, cutting-edge, game-changer, disruptive,
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

        RestClient client = ollamaClient();
        // Global chat-lock + cooldown across both profiles (protects the local model).
        JsonNode res = throttle.execute(() -> client.post().uri("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(JsonNode.class));

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

    /** Ollama HTTP client, adding an {@code Authorization: Bearer} header when a token is configured. */
    private RestClient ollamaClient() {
        return RestClient.builder().baseUrl(props.getOllamaUrl())
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
