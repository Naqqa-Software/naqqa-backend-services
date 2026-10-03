package com.naqqa.chatbot.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatAiExcerptTest {

    @Test
    void keepsHeadingsAndListItemsOnSeparateLines() {
        String text = "Ajutor și suport\nAi nevoie de ajutor? Suntem aici pentru tine.\nCum te putem ajuta\n"
                + "Întrebări despre cont, liste sau tombole\nProbleme cu afișarea promoțiilor sau a cataloagelor\n"
                + "Sugestii și feedback\nScrie-ne la contact@omy.md sau sună la +373 22 000 111.";

        String excerpt = DefaultChatAiEngine.structuredExcerpt(text, 420);

        assertThat(excerpt).startsWith("**Ajutor și suport**\nAi nevoie de ajutor? Suntem aici pentru tine.");
        assertThat(excerpt).contains("\n- Cum te putem ajuta\n- Întrebări despre cont, liste sau tombole\n");
        assertThat(excerpt).contains("- Sugestii și feedback\n\nScrie-ne la contact@omy.md");
    }

    @Test
    void capsLongTextOnSentenceBoundary() {
        String sentence = "Aceasta este o propoziție destul de lungă pentru a testa limita fragmentului. ";
        String excerpt = DefaultChatAiEngine.structuredExcerpt(sentence.repeat(20), 200);

        assertThat(excerpt.length()).isLessThanOrEqualTo(210);
        assertThat(excerpt).endsWith(".");
    }

    @Test
    void emptyTextGivesEmptyExcerpt() {
        assertThat(DefaultChatAiEngine.structuredExcerpt("  ", 200)).isEmpty();
        assertThat(DefaultChatAiEngine.structuredExcerpt(null, 200)).isEmpty();
    }

    @Test
    void numberedQuestionsBecomeBoldHeadings() {
        String text = "1. Ce înseamnă să accept termenii?\nPrin accesarea site-ului accepți termenii.\n2. Cine poate utiliza acest site?\nDoar persoanele de peste 18 ani.";
        String excerpt = DefaultChatAiEngine.structuredExcerpt(text, 420);
        assertThat(excerpt).isEqualTo("**Ce înseamnă să accept termenii?**\nPrin accesarea site-ului accepți termenii.\n\n**Cine poate utiliza acest site?**\nDoar persoanele de peste 18 ani.");
    }
}
