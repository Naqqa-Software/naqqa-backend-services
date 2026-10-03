package com.naqqa.chatbot.spi;

import com.naqqa.chatbot.entities.ChatSettingsEntity;

public interface ChatSettingsMigration {

    boolean migrate(ChatSettingsEntity settings);
}
