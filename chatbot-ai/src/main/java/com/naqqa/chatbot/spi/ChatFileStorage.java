package com.naqqa.chatbot.spi;

import org.springframework.web.multipart.MultipartFile;

public interface ChatFileStorage {

    default void validate(MultipartFile file) throws Exception {
    }

    String storeAvatar(MultipartFile file) throws Exception;
}
