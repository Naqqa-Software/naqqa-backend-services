package com.naqqa.chatbot.spi;

public interface ChatMemoryCipher {

    ChatMemoryCipher NONE = new ChatMemoryCipher() {
    };

    default String encrypt(String plain) {
        return plain;
    }

    default String decrypt(String stored) {
        return stored;
    }

    default boolean encrypting() {
        return false;
    }
}
