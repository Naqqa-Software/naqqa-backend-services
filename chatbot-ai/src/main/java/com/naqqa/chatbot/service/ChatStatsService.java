package com.naqqa.chatbot.service;

import com.naqqa.chatbot.dto.ChatDtos.ChatStatsDto;
import com.naqqa.chatbot.dto.ChatDtos.DayCountDto;
import com.naqqa.chatbot.dto.ChatDtos.IntentCountDto;
import com.naqqa.chatbot.dto.ChatDtos.RouteCountDto;
import com.naqqa.chatbot.dto.ChatDtos.SponsoredItemStatsDto;
import com.naqqa.chatbot.dto.ChatDtos.TextCountDto;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ChatStatsService {

    private final String conversations;
    private final String messages;
    private final String events;

    private final MongoTemplate mongoTemplate;
    private final ChatSettingsService settingsService;

    public ChatStatsService(MongoTemplate mongoTemplate, ChatSettingsService settingsService) {
        this(mongoTemplate, settingsService, "chat_conversation", "chat_message", "chat_recommendation_event");
    }

    public ChatStatsService(MongoTemplate mongoTemplate, ChatSettingsService settingsService, String conversations,
                            String messages, String events) {
        this.mongoTemplate = mongoTemplate;
        this.settingsService = settingsService;
        this.conversations = conversations;
        this.messages = messages;
        this.events = events;
    }

    public ChatStatsDto stats(LocalDate from, LocalDate to) {
        ZoneId zone = ChatSchedule.zone(settingsService.get());
        LocalDate end = to == null ? LocalDate.now(zone) : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        if (start.isAfter(end)) {
            throw ChatException.badRequest("from must be before to.");
        }
        if (start.plusDays(366).isBefore(end)) {
            throw ChatException.badRequest("The period must not exceed one year.");
        }
        Date fromDate = Date.from(start.atStartOfDay(zone).toInstant());
        Date toDate = Date.from(end.plusDays(1).atStartOfDay(zone).toInstant());
        Document createdRange = new Document("$gte", fromDate).append("$lt", toDate);

        Map<String, Long> perDay = new LinkedHashMap<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            perDay.put(d.toString(), 0L);
        }
        for (Document d : aggregate(conversations, List.of(
                new Document("$match", new Document("created_at", createdRange)),
                new Document("$group", new Document("_id", new Document("$dateToString",
                        new Document("format", "%Y-%m-%d").append("date", "$created_at").append("timezone", zone.getId())))
                        .append("count", new Document("$sum", 1)))))) {
            perDay.merge(String.valueOf(d.get("_id")), number(d.get("count")), Long::sum);
        }
        List<DayCountDto> conversationsPerDay = perDay.entrySet().stream().map(e -> new DayCountDto(e.getKey(), e.getValue())).toList();

        long total = 0;
        long escalated = 0;
        long resolvedByAi = 0;
        Document totals = first(aggregate(conversations, List.of(
                new Document("$match", new Document("created_at", createdRange)),
                new Document("$group", new Document("_id", null)
                        .append("total", new Document("$sum", 1))
                        .append("escalated", new Document("$sum", new Document("$cond", List.of(new Document("$eq", List.of("$escalated", true)), 1, 0))))
                        .append("ai", new Document("$sum", new Document("$cond", List.of(
                                new Document("$and", List.of(
                                        new Document("$ne", List.of("$escalated", true)),
                                        new Document("$eq", List.of(new Document("$ifNull", List.of("$assigned_operator_id", null)), null)))),
                                1, 0))))))));
        if (totals != null) {
            total = number(totals.get("total"));
            escalated = number(totals.get("escalated"));
            resolvedByAi = number(totals.get("ai"));
        }

        Double avgFirstResponse = null;
        Document response = first(aggregate(conversations, List.of(
                new Document("$match", new Document("created_at", createdRange)
                        .append("operator_first_response_at", new Document("$ne", null))),
                new Document("$project", new Document("delta", new Document("$subtract", List.of("$operator_first_response_at",
                        new Document("$ifNull", List.of("$escalated_at", new Document("$ifNull", List.of("$ai_paused_at", "$operator_first_response_at")))))))),
                new Document("$match", new Document("delta", new Document("$gte", 0))),
                new Document("$group", new Document("_id", null).append("avg", new Document("$avg", "$delta"))))));
        if (response != null && response.get("avg") != null) {
            avgFirstResponse = round(((Number) response.get("avg")).doubleValue() / 1000.0);
        }

        List<TextCountDto> topQuestions = new ArrayList<>();
        for (Document d : aggregate(messages, List.of(
                new Document("$match", new Document("created_at", createdRange).append("sender_type", "VISITOR")
                        .append("text", new Document("$type", "string"))),
                new Document("$group", new Document("_id", new Document("$toLower", "$text")).append("count", new Document("$sum", 1))),
                new Document("$sort", new Document("count", -1)),
                new Document("$limit", 10)))) {
            topQuestions.add(new TextCountDto(String.valueOf(d.get("_id")), number(d.get("count"))));
        }

        List<IntentCountDto> topIntents = new ArrayList<>();
        for (Document d : aggregate(messages, List.of(
                new Document("$match", new Document("created_at", createdRange).append("sender_type", "BOT")
                        .append("intent", new Document("$nin", java.util.Arrays.asList(null, "", "error")))),
                new Document("$group", new Document("_id", "$intent").append("count", new Document("$sum", 1))),
                new Document("$sort", new Document("count", -1)),
                new Document("$limit", 10)))) {
            topIntents.add(new IntentCountDto(String.valueOf(d.get("_id")), number(d.get("count"))));
        }

        Document shownRange = new Document("shown_at", createdRange);
        long shown = count(events, shownRange);
        long clicked = count(events, new Document(shownRange).append("clicked_at", new Document("$ne", null)));
        long sponsoredShown = count(events, new Document(shownRange).append("sponsored", true));
        long sponsoredClicked = count(events, new Document(shownRange).append("sponsored", true).append("clicked_at", new Document("$ne", null)));

        List<SponsoredItemStatsDto> sponsoredByItem = new ArrayList<>();
        for (Document d : aggregate(events, List.of(
                new Document("$match", new Document(shownRange).append("sponsored", true)),
                new Document("$group", new Document("_id", new Document("type", "$item_type").append("id", "$item_id"))
                        .append("title", new Document("$first", "$title"))
                        .append("shown", new Document("$sum", 1))
                        .append("clicked", new Document("$sum", new Document("$cond", List.of(
                                new Document("$gt", List.of(new Document("$ifNull", List.of("$clicked_at", null)), null)), 1, 0))))),
                new Document("$sort", new Document("shown", -1)),
                new Document("$limit", 100)))) {
            Document id = (Document) d.get("_id");
            Object itemId = id == null ? null : id.get("id");
            sponsoredByItem.add(new SponsoredItemStatsDto(id == null ? null : id.getString("type"),
                    itemId instanceof Number n ? n.longValue() : null, d.getString("title"),
                    number(d.get("shown")), number(d.get("clicked"))));
        }

        long llmCalls = 0;
        long tokensIn = 0;
        long tokensOut = 0;
        Document llm = first(aggregate(messages, List.of(
                new Document("$match", new Document("created_at", createdRange).append("sender_type", "BOT").append("llm_used", true)),
                new Document("$group", new Document("_id", null)
                        .append("calls", new Document("$sum", 1))
                        .append("in", new Document("$sum", "$tokens_in"))
                        .append("out", new Document("$sum", "$tokens_out"))))));
        if (llm != null) {
            llmCalls = number(llm.get("calls"));
            tokensIn = number(llm.get("in"));
            tokensOut = number(llm.get("out"));
        }

        Document latencyMatch = new Document("created_at", createdRange).append("sender_type", "BOT")
                .append("latency_ms", new Document("$type", "number"));
        Double avgLatency = null;
        Long p95 = null;
        Document latency = first(aggregate(messages, List.of(
                new Document("$match", latencyMatch),
                new Document("$group", new Document("_id", null).append("avg", new Document("$avg", "$latency_ms")).append("n", new Document("$sum", 1))))));
        if (latency != null && latency.get("avg") != null) {
            avgLatency = round(((Number) latency.get("avg")).doubleValue());
            long n = number(latency.get("n"));
            long skip = percentileIndex(n, 0.95);
            Document p = first(aggregate(messages, List.of(
                    new Document("$match", latencyMatch),
                    new Document("$sort", new Document("latency_ms", 1)),
                    new Document("$skip", skip),
                    new Document("$limit", 1),
                    new Document("$project", new Document("latency_ms", 1)))));
            if (p != null && p.get("latency_ms") != null) {
                p95 = number(p.get("latency_ms"));
            }
        }

        List<RouteCountDto> routes = new ArrayList<>();
        long botTotal = 0;
        long llmRoute = 0;
        for (Document d : aggregate(messages, List.of(
                new Document("$match", new Document("created_at", createdRange).append("sender_type", "BOT")
                        .append("route", new Document("$type", "string"))),
                new Document("$group", new Document("_id", "$route").append("count", new Document("$sum", 1))),
                new Document("$sort", new Document("count", -1))))) {
            long n = number(d.get("count"));
            routes.add(new RouteCountDto(String.valueOf(d.get("_id")), n));
            botTotal += n;
            if ("LLM".equals(String.valueOf(d.get("_id")))) {
                llmRoute += n;
            }
        }
        Document botRange = new Document("created_at", createdRange).append("sender_type", "BOT");
        long thumbsUp = count(messages, new Document(botRange).append("feedback", 1));
        long thumbsDown = count(messages, new Document(botRange).append("feedback", -1));
        List<TextCountDto> topUnanswered = new ArrayList<>();
        for (Document d : aggregate(messages, List.of(
                new Document("$match", new Document(botRange).append("quality_flags", "NO_RESULTS")
                        .append("question", new Document("$type", "string"))),
                new Document("$group", new Document("_id", new Document("$toLower", "$question")).append("count", new Document("$sum", 1))),
                new Document("$sort", new Document("count", -1)),
                new Document("$limit", 10)))) {
            topUnanswered.add(new TextCountDto(String.valueOf(d.get("_id")), number(d.get("count"))));
        }
        List<IntentCountDto> topThumbsDown = new ArrayList<>();
        for (Document d : aggregate(messages, List.of(
                new Document("$match", new Document(botRange).append("feedback", -1)),
                new Document("$group", new Document("_id", "$intent").append("count", new Document("$sum", 1))),
                new Document("$sort", new Document("count", -1)),
                new Document("$limit", 10)))) {
            topThumbsDown.add(new IntentCountDto(String.valueOf(d.get("_id")), number(d.get("count"))));
        }
        return new ChatStatsDto(conversationsPerDay, total, pct(resolvedByAi, total), pct(escalated, total), avgFirstResponse,
                topQuestions, topIntents, shown, clicked, pct(clicked, shown), sponsoredShown, sponsoredClicked,
                pct(sponsoredClicked, sponsoredShown), sponsoredByItem, llmCalls, tokensIn, tokensOut, 0.0, avgLatency, p95,
                routes, pct(llmRoute, botTotal), thumbsUp, thumbsDown, pct(thumbsUp, thumbsUp + thumbsDown), topUnanswered,
                topThumbsDown);
    }

    public static long percentileIndex(long n, double percentile) {
        if (n <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(n - 1, (long) Math.ceil(percentile * n) - 1));
    }

    public static double pct(long part, long whole) {
        return whole <= 0 ? 0.0 : round(part * 100.0 / whole);
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private List<Document> aggregate(String collection, List<Document> pipeline) {
        List<Document> result = new ArrayList<>();
        mongoTemplate.getCollection(collection).aggregate(pipeline).allowDiskUse(true).into(result);
        return result;
    }

    private long count(String collection, Document filter) {
        return mongoTemplate.getCollection(collection).countDocuments(filter);
    }

    private static Document first(List<Document> documents) {
        return documents.isEmpty() ? null : documents.get(0);
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }
}
