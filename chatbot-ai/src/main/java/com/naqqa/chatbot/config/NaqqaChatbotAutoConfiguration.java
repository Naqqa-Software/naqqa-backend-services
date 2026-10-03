package com.naqqa.chatbot.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.naqqa.chatbot.ai.ChatAiEngine;
import com.naqqa.chatbot.ai.DefaultChatAiEngine;
import com.naqqa.chatbot.ai.InputGuard;
import com.naqqa.chatbot.ai.IntentCatalog;
import com.naqqa.chatbot.ai.IntentRouter;
import com.naqqa.chatbot.ai.OutputGuard;
import com.naqqa.chatbot.ai.knowledge.ClasspathKnowledgeSource;
import com.naqqa.chatbot.ai.knowledge.KnowledgeService;
import com.naqqa.chatbot.ai.llm.LlmGate;
import com.naqqa.chatbot.ai.llm.LlmProvider;
import com.naqqa.chatbot.ai.llm.LlmResponseParser;
import com.naqqa.chatbot.ai.llm.OllamaLlmProvider;
import com.naqqa.chatbot.ai.llm.PromptBuilder;
import com.naqqa.chatbot.ai.retrieval.Candidate;
import com.naqqa.chatbot.ai.retrieval.CardFactory;
import com.naqqa.chatbot.ai.retrieval.ChatItemType;
import com.naqqa.chatbot.ai.retrieval.ChatRetrievalService;
import com.naqqa.chatbot.ai.retrieval.Ranker;
import com.naqqa.chatbot.ai.retrieval.RetrievalPlan;
import com.naqqa.chatbot.ai.safety.AbuseGuard;
import com.naqqa.chatbot.ai.safety.ChatSafety;
import com.naqqa.chatbot.ai.safety.CrisisGuard;
import com.naqqa.chatbot.ai.safety.SafetyPacks;
import com.naqqa.chatbot.i18n.ChatLanguages;
import com.naqqa.chatbot.i18n.ChatResources;
import com.naqqa.chatbot.repository.ChatAuditLogRepository;
import com.naqqa.chatbot.repository.ChatConversationRepository;
import com.naqqa.chatbot.repository.ChatMessageRepository;
import com.naqqa.chatbot.repository.ChatMongoIndexes;
import com.naqqa.chatbot.repository.ChatRecommendationEventRepository;
import com.naqqa.chatbot.repository.ChatSettingsRepository;
import com.naqqa.chatbot.security.ChatPermissions;
import com.naqqa.chatbot.security.ChatVisitorTokenService;
import com.naqqa.chatbot.security.DefaultChatOperatorResolver;
import com.naqqa.chatbot.service.ChatAdminService;
import com.naqqa.chatbot.service.ChatAuditService;
import com.naqqa.chatbot.service.ChatConversationStore;
import com.naqqa.chatbot.service.ChatEscalation;
import com.naqqa.chatbot.service.ChatHasher;
import com.naqqa.chatbot.service.ChatMaintenanceJobs;
import com.naqqa.chatbot.service.ChatMapper;
import com.naqqa.chatbot.service.ChatRateLimiter;
import com.naqqa.chatbot.service.ChatReviewService;
import com.naqqa.chatbot.service.ChatService;
import com.naqqa.chatbot.service.ChatSettingsService;
import com.naqqa.chatbot.service.ChatSponsorService;
import com.naqqa.chatbot.service.ChatStatsService;
import com.naqqa.chatbot.service.ChatSttService;
import com.naqqa.chatbot.service.ChatTexts;
import com.naqqa.chatbot.spi.ChatContentProvider;
import com.naqqa.chatbot.spi.ChatEntityResolver;
import com.naqqa.chatbot.spi.ChatFileStorage;
import com.naqqa.chatbot.spi.ChatHumanVerifier;
import com.naqqa.chatbot.spi.ChatKnowledgeSearcher;
import com.naqqa.chatbot.spi.ChatKnowledgeSource;
import com.naqqa.chatbot.spi.ChatOperatorResolver;
import com.naqqa.chatbot.spi.ChatSearchLinkBuilder;
import com.naqqa.chatbot.spi.ChatSponsorProvider;
import com.naqqa.chatbot.spi.ChatTokenCodec;
import com.naqqa.chatbot.spi.ChatUserResolver;
import com.naqqa.chatbot.sse.ChatSseHub;
import com.naqqa.chatbot.web.ChatAdminController;
import com.naqqa.chatbot.web.ChatExceptionHandler;
import com.naqqa.chatbot.web.ChatPublicController;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@AutoConfiguration(after = {MongoDataAutoConfiguration.class, RedisAutoConfiguration.class, JacksonAutoConfiguration.class})
@ConditionalOnProperty(prefix = "naqqa.chatbot", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean(MongoTemplate.class)
@EnableConfigurationProperties(NaqqaChatbotProperties.class)
public class NaqqaChatbotAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ChatResources naqqaChatbotResources(org.springframework.context.ApplicationContext context) {
        return new ChatResources(context.getClassLoader());
    }

    @Bean
    @ConditionalOnMissingBean
    public ChatLanguages naqqaChatbotLanguages(NaqqaChatbotProperties properties, ChatResources resources) {
        return new ChatLanguages(properties.getLanguages(), properties.templatePlaceholders(), resources);
    }

    @Bean
    @ConditionalOnMissingBean
    public IntentCatalog naqqaChatbotIntentCatalog(ChatResources resources) {
        return IntentCatalog.load(resources);
    }

    @Bean
    @ConditionalOnMissingBean
    public ChatPermissions naqqaChatbotPermissions(NaqqaChatbotProperties properties) {
        return ChatPermissions.of(properties.getPermissions());
    }

    @Bean
    @ConditionalOnMissingBean
    public ChatEntityResolver naqqaChatbotEntityResolver() {
        return ChatEntityResolver.NONE;
    }

    @Bean
    @ConditionalOnMissingBean
    public ChatSearchLinkBuilder naqqaChatbotLinkBuilder() {
        return ChatSearchLinkBuilder.NONE;
    }

    @Bean
    @ConditionalOnMissingBean
    public ChatUserResolver naqqaChatbotUserResolver() {
        return ChatUserResolver.NONE;
    }

    @Bean
    @ConditionalOnMissingBean
    public ChatOperatorResolver naqqaChatbotOperatorResolver(ChatPermissions permissions, ChatUserResolver users) {
        return new DefaultChatOperatorResolver(permissions, users, "Operator");
    }

    @Bean
    @ConditionalOnMissingBean
    public ChatContentProvider naqqaChatbotContentProvider() {
        return new ChatContentProvider() {
            @Override
            public List<ChatItemType> itemTypes() {
                return List.of();
            }

            @Override
            public List<Candidate> retrieve(RetrievalPlan plan) {
                return List.of();
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public ChatTokenCodec naqqaChatbotTokenCodec(NaqqaChatbotProperties properties, ObjectProvider<JwtEncoder> encoder,
                                                ObjectProvider<JwtDecoder> decoder) {
        String secret = properties.getVisitorToken().getSecret();
        if (secret != null && !secret.isBlank()) {
            return ChatVisitorTokenService.hmacCodec(secret);
        }
        JwtEncoder e = encoder.getIfUnique();
        JwtDecoder d = decoder.getIfUnique();
        if (e != null && d != null) {
            return ChatVisitorTokenService.codec(e, d);
        }
        log.warn("[chatbot] naqqa.chatbot.visitor-token.secret is not set; visitor tokens use a random key and expire on restart");
        return ChatVisitorTokenService.hmacCodec(null);
    }

    @Bean
    @ConditionalOnMissingBean
    public ChatVisitorTokenService naqqaChatbotVisitorTokens(ChatTokenCodec codec, NaqqaChatbotProperties properties) {
        boolean mac = properties.getVisitorToken().getSecret() != null && !properties.getVisitorToken().getSecret().isBlank();
        if (mac) {
            return ChatVisitorTokenService.hmac(properties.getVisitorToken().getSecret(), properties.getVisitorToken().getTtlHours(),
                    properties.getVisitorToken().getIssuer());
        }
        return new ChatVisitorTokenService(codec, properties.getVisitorToken().getTtlHours(), properties.getVisitorToken().getIssuer());
    }

    @Bean
    public ChatConversationRepository naqqaChatConversationRepository(MongoTemplate mongo, NaqqaChatbotProperties p) {
        return new ChatConversationRepository(mongo, p.getCollections().getConversation());
    }

    @Bean
    public ChatMessageRepository naqqaChatMessageRepository(MongoTemplate mongo, NaqqaChatbotProperties p) {
        return new ChatMessageRepository(mongo, p.getCollections().getMessage());
    }

    @Bean
    public ChatRecommendationEventRepository naqqaChatEventRepository(MongoTemplate mongo, NaqqaChatbotProperties p) {
        return new ChatRecommendationEventRepository(mongo, p.getCollections().getRecommendationEvent());
    }

    @Bean
    public ChatSettingsRepository naqqaChatSettingsRepository(MongoTemplate mongo, NaqqaChatbotProperties p) {
        return new ChatSettingsRepository(mongo, p.getCollections().getSettings());
    }

    @Bean
    public ChatAuditLogRepository naqqaChatAuditRepository(MongoTemplate mongo, NaqqaChatbotProperties p) {
        return new ChatAuditLogRepository(mongo, p.getCollections().getAuditLog());
    }

    @Bean
    public ChatMongoIndexes naqqaChatMongoIndexes(MongoTemplate mongo, NaqqaChatbotProperties p) {
        return new ChatMongoIndexes(mongo, p.getCollections());
    }

    @Bean
    public ChatConversationStore naqqaChatConversationStore(ChatConversationRepository repository) {
        return new ChatConversationStore(repository);
    }

    @Bean
    public ChatAuditService naqqaChatAuditService(ChatAuditLogRepository repository) {
        return new ChatAuditService(repository);
    }

    @Bean
    public ChatHasher naqqaChatHasher(MongoTemplate mongo, NaqqaChatbotProperties p) {
        return new ChatHasher(mongo, p.getCollections().getSecret(), p.getHashSalt());
    }

    @Bean
    @ConditionalOnMissingBean
    public ChatRateLimiter naqqaChatRateLimiter(ListableBeanFactory beanFactory, NaqqaChatbotProperties p) {
        if (!RedisSupport.present()) {
            return ChatRateLimiter.inMemory();
        }
        return new ChatRateLimiter(RedisSupport.provider(beanFactory), p.getRateLimit().getRedisPrefix());
    }

    @Bean
    public ChatTexts naqqaChatTexts(ChatLanguages languages) {
        return new ChatTexts(languages);
    }

    @Bean
    public ChatEscalation naqqaChatEscalation(ChatLanguages languages, IntentCatalog catalog) {
        return new ChatEscalation(languages, catalog.escalateQuickReply());
    }

    @Bean
    public ChatSettingsService naqqaChatSettingsService(ChatSettingsRepository repository, NaqqaChatbotProperties p,
                                                       ChatLanguages languages, IntentCatalog catalog,
                                                       ObjectProvider<com.naqqa.chatbot.spi.ChatSettingsMigration> migrations) {
        ChatSettingsService service = new ChatSettingsService(repository, p, languages, catalog.welcomeQuickReplies());
        service.setMigrations(migrations.orderedStream().toList());
        return service;
    }

    @Bean
    public ChatMapper naqqaChatMapper(ChatSettingsService settings, ChatUserResolver users) {
        return new ChatMapper(settings, users);
    }

    @Bean
    public ChatSseHub naqqaChatSseHub() {
        return new ChatSseHub();
    }

    @Bean
    public ChatSttService naqqaChatSttService(NaqqaChatbotProperties p) {
        return new ChatSttService(p);
    }

    @Bean
    public CrisisGuard naqqaChatCrisisGuard(ChatResources resources, NaqqaChatbotProperties p) {
        return new CrisisGuard(SafetyPacks.load(resources, p.getLanguages()));
    }

    @Bean
    public AbuseGuard naqqaChatAbuseGuard(ChatResources resources, NaqqaChatbotProperties p) {
        return new AbuseGuard(SafetyPacks.load(resources, p.getLanguages()));
    }

    @Bean
    public com.naqqa.chatbot.ai.safety.TopicGuard naqqaChatTopicGuard(ChatResources resources, NaqqaChatbotProperties p) {
        return new com.naqqa.chatbot.ai.safety.TopicGuard(SafetyPacks.load(resources, p.getLanguages()));
    }

    @Bean
    public ChatSafety naqqaChatSafety(CrisisGuard crisis, AbuseGuard abuse, ChatLanguages languages, NaqqaChatbotProperties p) {
        return new ChatSafety(crisis, abuse, languages, p.getCrisis().getEmergency(), p.getCrisis().getHelplines());
    }

    @Bean
    public ChatService naqqaChatService(ChatConversationStore store, ChatMessageRepository messages,
                                        ChatRecommendationEventRepository events, ChatSettingsService settings,
                                        ChatMapper mapper, ChatSseHub hub, ChatRateLimiter rateLimiter, ChatHasher hasher,
                                        ChatSttService stt, ChatVisitorTokenService tokens, ObjectProvider<ChatAiEngine> engine,
                                        ChatTexts texts, ChatEscalation escalation, NaqqaChatbotProperties p,
                                        ChatSafety safety, ChatAuditService audit) {
        ChatService service = new ChatService(store, messages, events, settings, mapper, hub, rateLimiter, hasher, stt, tokens,
                engine, texts, escalation, p);
        service.setSafety(safety, audit);
        return service;
    }

    @Bean
    @ConditionalOnMissingBean(com.naqqa.chatbot.spi.ChatMessageSearch.class)
    public com.naqqa.chatbot.search.MongoChatMessageSearch naqqaChatMessageSearch(ChatMessageRepository messages,
                                                                                 ObjectProvider<com.naqqa.chatbot.spi.ChatQueryExpander> expander) {
        return new com.naqqa.chatbot.search.MongoChatMessageSearch(messages, expander.getIfAvailable());
    }

    @Bean
    public org.springframework.beans.factory.SmartInitializingSingleton naqqaChatSearchWiring(ChatMessageRepository messages,
                                                                                            com.naqqa.chatbot.spi.ChatMessageSearch search,
                                                                                            ChatService chat, ChatAdminService admin) {
        return () -> {
            messages.setSearch(search);
            chat.setMessageSearch(search);
            admin.setMessageSearch(search);
        };
    }

    @Bean
    public ChatAdminService naqqaChatAdminService(ChatConversationStore store, ChatMessageRepository messages,
                                                  ChatRecommendationEventRepository events, ChatAuditLogRepository auditRepository,
                                                  ChatAuditService audit, ChatSettingsService settings, ChatService chat,
                                                  ChatMapper mapper, ChatSseHub hub, MongoTemplate mongo,
                                                  ObjectProvider<ChatAiEngine> engine) {
        return new ChatAdminService(store, messages, events, auditRepository, audit, settings, chat, mapper, hub, mongo, engine);
    }

    @Bean
    public ChatStatsService naqqaChatStatsService(MongoTemplate mongo, ChatSettingsService settings, NaqqaChatbotProperties p) {
        return new ChatStatsService(mongo, settings, p.getCollections().getConversation(), p.getCollections().getMessage(),
                p.getCollections().getRecommendationEvent());
    }

    @Bean
    public ChatReviewService naqqaChatReviewService(ChatMessageRepository messages, ChatSettingsService settings, ChatAuditService audit,
                                                    MongoTemplate mongo, NaqqaChatbotProperties p, ChatLanguages languages,
                                                    IntentCatalog catalog) {
        java.util.Set<String> knowledge = new java.util.HashSet<>();
        for (com.naqqa.chatbot.ai.IntentDef def : catalog.all()) {
            if (def.isKnowledge() || def.role() == com.naqqa.chatbot.ai.IntentDef.Role.CONTACT || def.role() == com.naqqa.chatbot.ai.IntentDef.Role.PAGE) {
                knowledge.add(def.code());
            }
        }
        return new ChatReviewService(messages, settings, audit, mongo, p.getCollections().getReviewSuggestion(), languages, knowledge);
    }

    @Bean
    public ChatSponsorService naqqaChatSponsorService(ObjectProvider<ChatSponsorProvider> provider, ChatAuditService audit) {
        return new ChatSponsorService(provider.getIfAvailable(), audit);
    }

    @Bean
    public ChatMaintenanceJobs naqqaChatMaintenanceJobs(ChatConversationRepository conversations, ChatMessageRepository messages,
                                                        ChatRecommendationEventRepository events, ChatAdminService admin,
                                                        ChatSettingsService settings, MongoTemplate mongo, NaqqaChatbotProperties p) {
        ChatMaintenanceJobs jobs = new ChatMaintenanceJobs(conversations, messages, events, admin, settings, mongo);
        jobs.setInactivity(Duration.ofMinutes(Math.max(1, p.getJobs().getInactivityMinutes())));
        return jobs;
    }

    @Bean
    public InputGuard naqqaChatInputGuard(ChatLanguages languages, NaqqaChatbotProperties p) {
        return new InputGuard(languages, p.getAllowedDomains());
    }

    @Bean
    public OutputGuard naqqaChatOutputGuard(NaqqaChatbotProperties p) {
        List<String> preserved = new ArrayList<>();
        preserved.add(p.getContact().getEmail());
        preserved.add(p.getContact().getPhone());
        return new OutputGuard(p.getInternalPathPrefixes(), p.getAllowedDomains(), preserved);
    }

    @Bean
    public IntentRouter naqqaChatIntentRouter(ChatLanguages languages, IntentCatalog catalog, ChatEntityResolver directory,
                                              ObjectProvider<com.naqqa.chatbot.spi.ChatQueryExpander> expander) {
        IntentRouter router = new IntentRouter(languages, catalog, directory);
        router.setExpander(expander.getIfAvailable());
        return router;
    }

    @Bean
    public ChatRetrievalService naqqaChatRetrievalService(ChatContentProvider provider, ObjectProvider<ObjectMapper> mapper,
                                                          ListableBeanFactory beanFactory, NaqqaChatbotProperties p, ChatSafety safety) {
        ObjectMapper om = mapper.getIfAvailable(() -> new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
        ChatRetrievalService service = new ChatRetrievalService(provider, om, RedisSupport.supplier(beanFactory),
                p.getCache().getPrefix(), Duration.ofMinutes(Math.max(1, p.getCache().getTtlMinutes())));
        if (p.getSafety().isFilterAdultResults()) {
            service.setBlockedTitle(safety::isSexualTitle);
        }
        return service;
    }

    @Bean
    public Ranker naqqaChatRanker(ChatRetrievalService retrieval) {
        return new Ranker(retrieval::type);
    }

    @Bean
    public CardFactory naqqaChatCardFactory(ChatContentProvider provider, ChatEntityResolver directory) {
        return new CardFactory(provider, directory);
    }

    @Bean
    @ConditionalOnProperty(prefix = "naqqa.chatbot.knowledge", name = "classpath-enabled", havingValue = "true", matchIfMissing = true)
    public ClasspathKnowledgeSource naqqaChatClasspathKnowledge(NaqqaChatbotProperties p, org.springframework.context.ApplicationContext context) {
        return new ClasspathKnowledgeSource(p.getKnowledge().getLocation(), context.getClassLoader());
    }

    @Bean
    public KnowledgeService naqqaChatKnowledgeService(MongoTemplate mongo, NaqqaChatbotProperties p,
                                                      ObjectProvider<ChatKnowledgeSource> sources,
                                                      ObjectProvider<ChatKnowledgeSearcher> searcher,
                                                      ChatRetrievalService retrieval, ChatLanguages languages, InputGuard guard) {
        KnowledgeService service = new KnowledgeService(mongo, p.getCollections().getKnowledgeChunk(), sources.orderedStream().toList(),
                searcher.getIfAvailable(), retrieval, languages, guard);
        service.setReplacements(p.getKnowledge().getReplacements());
        return service;
    }

    @Bean
    public LlmGate naqqaChatLlmGate(NaqqaChatbotProperties p) {
        return new LlmGate(p.getLlm().getMaxConcurrent(), p.getLlm().getQueueWaitMs());
    }

    @Bean
    @ConditionalOnMissingBean(LlmProvider.class)
    public OllamaLlmProvider naqqaChatLlmProvider(NaqqaChatbotProperties p, ChatRetrievalService retrieval) {
        OllamaLlmProvider provider = new OllamaLlmProvider(p.getLlm().getProvider(), p.getLlm().getUrl(), p.getLlm().getModel(),
                p.getLlm().getTimeoutMs());
        provider.setParser(new LlmResponseParser(retrieval.typeKeys()));
        return provider;
    }

    @Bean
    public PromptBuilder naqqaChatPromptBuilder(ChatEntityResolver directory, ChatLanguages languages, InputGuard guard,
                                                ChatContentProvider provider) {
        return new PromptBuilder(directory, languages, guard, provider.companyType());
    }

    @Bean
    @ConditionalOnMissingBean(ChatAiEngine.class)
    public DefaultChatAiEngine chatAiEngine(InputGuard input, IntentRouter router, ChatRetrievalService retrieval, Ranker ranker,
                                            CardFactory cards, KnowledgeService knowledge, ObjectProvider<LlmProvider> llm,
                                            LlmGate gate, PromptBuilder prompts, OutputGuard output, ChatEntityResolver directory,
                                            ChatSearchLinkBuilder links, ChatSafety safety, IntentCatalog catalog,
                                            NaqqaChatbotProperties p, com.naqqa.chatbot.ai.safety.TopicGuard topicGuard) {
        DefaultChatAiEngine engine = new DefaultChatAiEngine(input, router, retrieval, ranker, cards, knowledge, llm.getIfAvailable(),
                gate, prompts, output, directory, links);
        engine.setSafety(safety, catalog.escalateQuickReply());
        engine.setTopicGuard(topicGuard);
        engine.setResponseRouter(new com.naqqa.chatbot.ai.ResponseRouter(input.languages(), p.getLlm().getRoutes()));
        return engine;
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "naqqaChatbotScheduler")
    public ThreadPoolTaskScheduler naqqaChatbotScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("naqqa-chatbot-");
        scheduler.setDaemon(true);
        scheduler.initialize();
        return scheduler;
    }

    @Bean
    public ChatJobs naqqaChatJobs(NaqqaChatbotProperties p, ThreadPoolTaskScheduler naqqaChatbotScheduler, ChatSseHub hub,
                                  ChatMaintenanceJobs maintenance, KnowledgeService knowledge, ChatSettingsService settings,
                                  ChatMongoIndexes indexes, ChatReviewService review) {
        return new ChatJobs(p, naqqaChatbotScheduler, hub, maintenance, knowledge, settings, indexes, List.of(review::rebuild));
    }

    @Bean
    public ChatPublicController naqqaChatPublicController(ChatService chat, ChatSseHub hub, ChatUserResolver users,
                                                          ObjectProvider<ChatHumanVerifier> verifier, NaqqaChatbotProperties p) {
        return new ChatPublicController(chat, hub, users, verifier, p);
    }

    @Bean
    public ChatAdminController naqqaChatAdminController(ChatAdminService admin, ChatStatsService stats, ChatSponsorService sponsors,
                                                        ChatSettingsService settings, ChatAuditService audit, ChatSseHub hub,
                                                        ChatOperatorResolver operators, ChatPermissions permissions,
                                                        ObjectProvider<ChatFileStorage> files, ObjectProvider<ChatAiEngine> engine,
                                                        ChatReviewService review) {
        ChatAdminController controller = new ChatAdminController(admin, stats, sponsors, settings, audit, hub, operators, permissions, files, engine);
        controller.setReviewService(review);
        return controller;
    }

    @Bean
    public ChatExceptionHandler naqqaChatExceptionHandler() {
        return new ChatExceptionHandler();
    }
}
