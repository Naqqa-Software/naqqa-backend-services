package com.naqqa.chatbot.spi;

import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;

public interface ChatTokenCodec {

    JwtEncoder encoder();

    JwtDecoder decoder();
}
