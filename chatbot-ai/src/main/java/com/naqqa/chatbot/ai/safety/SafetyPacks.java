package com.naqqa.chatbot.ai.safety;

import com.fasterxml.jackson.databind.JsonNode;
import com.naqqa.chatbot.i18n.ChatResources;
import org.springframework.core.io.Resource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SafetyPacks {

    private static final Pattern LANG = Pattern.compile("naqqa-chatbot/lang/([A-Za-z_-]{2,10})/pack\\.json$");

    private SafetyPacks() {
    }

    public static Map<String, JsonNode> load(ChatResources resources, Collection<String> preferred) {
        List<String> langs = new ArrayList<>();
        if (preferred != null) {
            for (String l : preferred) {
                if (l != null && !l.isBlank() && !langs.contains(l.trim().toLowerCase())) {
                    langs.add(l.trim().toLowerCase());
                }
            }
        }
        for (Resource r : resources.all(ChatResources.ROOT + "lang/*/pack.json")) {
            try {
                Matcher m = LANG.matcher(r.getURL().toString());
                if (m.find() && !langs.contains(m.group(1).toLowerCase())) {
                    langs.add(m.group(1).toLowerCase());
                }
            } catch (Exception ignored) {
            }
        }
        Map<String, JsonNode> out = new LinkedHashMap<>();
        for (String lang : langs) {
            out.put(lang, resources.json(ChatResources.ROOT + "lang/" + lang + "/pack.json"));
        }
        return out;
    }
}
