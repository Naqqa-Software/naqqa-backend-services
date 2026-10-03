package com.naqqa.chatbot.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatAiEchoGuardTest {

    @Test
    void rejectsEchoOfTheQuestion() {
        assertThat(DefaultChatAiEngine.isUnusableLlmText("Vreau mic dejun sub 100 lei", "Vreau mic dejun sub 100 lei")).isTrue();
        assertThat(DefaultChatAiEngine.isUnusableLlmText("Vreau mic dejun sub 100 lei.", "vreau mic dejun sub 100 lei")).isTrue();
        assertThat(DefaultChatAiEngine.isUnusableLlmText("Ok", "ce promoții sunt")).isTrue();
    }

    @Test
    void acceptsRealAnswers() {
        assertThat(DefaultChatAiEngine.isUnusableLlmText(
                "Pentru un mic dejun sub 100 lei îți recomand ouă, pâine și lapte de la Kaufland, toate la reducere.",
                "Vreau mic dejun sub 100 lei")).isFalse();
    }
}
