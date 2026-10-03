package com.naqqa.chatbot;

import com.naqqa.chatbot.service.ChatCsv;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatCsvTest {

    @Test
    void formulaPrefixesAreNeutralised() {
        assertEquals("'=SUM(A1:A2)", ChatCsv.cell("=SUM(A1:A2)"));
        assertEquals("'+123", ChatCsv.cell("+123"));
        assertEquals("'-1", ChatCsv.cell("-1"));
        assertEquals("'@cmd", ChatCsv.cell("@cmd"));
    }

    @Test
    void quotesAndSeparatorsAreEscaped() {
        assertEquals("\"a,b\"", ChatCsv.cell("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", ChatCsv.cell("say \"hi\""));
        assertEquals("\"line1\nline2\"", ChatCsv.cell("line1\nline2"));
        assertEquals("\"'=1,2\"", ChatCsv.cell("=1,2"));
    }

    @Test
    void rowJoinsCellsWithCrlf() {
        assertEquals("a,,'=x\r\n", ChatCsv.row(Arrays.asList("a", null, "=x")));
        assertEquals("plain", ChatCsv.cell("plain"));
    }
}
