package com.naqqa.filestorage.video;

import org.springframework.security.core.Authentication;

public interface HlsAccessPolicy {

    boolean canStream(String groupId, Authentication authentication);
}
