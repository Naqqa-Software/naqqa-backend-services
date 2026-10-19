package com.naqqa.tts.dto;

import java.util.Set;

/**
 * Response contract for {@code GET {naqqa.tts.path}/status}. It mirrors the previous inline map
 * {@code {enabled, ready, languages}} exactly, so the wire shape is unchanged; it only gives the
 * endpoint an explicit dto record in place of an untyped {@code Map}.
 */
public record TtsStatus(boolean enabled, boolean ready, Set<String> languages) {
}
