package com.naqqa.chatbot;

import com.naqqa.chatbot.ai.ChatAiEngine;
import com.naqqa.chatbot.config.NaqqaChatbotAutoConfiguration;
import com.naqqa.chatbot.i18n.ChatLanguages;
import com.naqqa.chatbot.security.ChatVisitorTokenService;
import com.naqqa.chatbot.web.ChatAdminController;
import com.naqqa.chatbot.web.ChatPublicController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class NaqqaChatbotAutoConfigurationTest {

    @Configuration
    static class Host {
        @Bean
        MongoTemplate mongoTemplate() {
            return mock(MongoTemplate.class);
        }
    }

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NaqqaChatbotAutoConfiguration.class))
            .withUserConfiguration(Host.class);

    @Test
    void wiresTheWholeBotWithDefaults() {
        runner.withPropertyValues("naqqa.chatbot.visitor-token.secret=test-secret", "naqqa.chatbot.languages=ro,ru,en")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ChatPublicController.class);
                    assertThat(context).hasSingleBean(ChatAdminController.class);
                    assertThat(context).hasSingleBean(ChatAiEngine.class);
                    assertThat(context.getBean(ChatLanguages.class).languages()).isEqualTo(List.of("ro", "ru", "en"));
                    ChatVisitorTokenService tokens = context.getBean(ChatVisitorTokenService.class);
                    String token = tokens.issue("abc");
                    assertThat(tokens.isValidFor(token, "abc")).isTrue();
                    assertThat(tokens.isValidFor(token, "other")).isFalse();
                });
    }

    @Test
    void canBeDisabled() {
        runner.withPropertyValues("naqqa.chatbot.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ChatPublicController.class));
    }
}
