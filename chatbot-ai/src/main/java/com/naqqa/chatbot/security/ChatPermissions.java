package com.naqqa.chatbot.security;

import com.naqqa.chatbot.config.NaqqaChatbotProperties;

public record ChatPermissions(String readAll, String readAssigned, String takeover, String export, String delete,
                              String settings, String stats) {

    public static final ChatPermissions DEFAULT = new ChatPermissions(ChatAccess.READ_ALL, ChatAccess.READ_ASSIGNED,
            ChatAccess.TAKEOVER, ChatAccess.EXPORT, ChatAccess.DELETE, ChatAccess.SETTINGS, ChatAccess.STATS);

    public static ChatPermissions of(NaqqaChatbotProperties.Permissions p) {
        if (p == null) {
            return DEFAULT;
        }
        return new ChatPermissions(p.getReadAll(), p.getReadAssigned(), p.getTakeover(), p.getExport(), p.getDelete(),
                p.getSettings(), p.getStats());
    }
}
