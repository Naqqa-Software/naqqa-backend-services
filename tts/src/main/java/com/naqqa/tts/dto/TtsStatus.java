package com.naqqa.tts.dto;

import java.util.Set;

public record TtsStatus(boolean enabled, boolean ready, Set<String> languages) {
}
