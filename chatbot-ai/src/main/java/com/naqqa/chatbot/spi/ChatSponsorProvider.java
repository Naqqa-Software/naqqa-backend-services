package com.naqqa.chatbot.spi;

import com.naqqa.chatbot.dto.ChatDtos.PageDto;
import com.naqqa.chatbot.dto.ChatDtos.SponsorUpdateRequest;
import com.naqqa.chatbot.dto.ChatDtos.SponsoredItemDto;

import java.util.List;

public interface ChatSponsorProvider {

    List<String> types();

    PageDto<SponsoredItemDto> list(String type, String query, boolean sponsoredOnly, int page, int size);

    SponsoredItemDto update(String type, Long id, SponsorUpdateRequest request);
}
