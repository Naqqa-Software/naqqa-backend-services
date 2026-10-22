package com.naqqa.chat.spi;

import java.util.List;

public interface ChatSupportProvider {

    String displayName(Long userId);

    String avatarUrl(Long userId);

    String courseAvatarUrl(Long courseId);

    List<SearchableContact> searchableContacts(Long callerId);
}
