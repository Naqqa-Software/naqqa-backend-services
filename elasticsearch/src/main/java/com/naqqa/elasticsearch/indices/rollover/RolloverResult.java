package com.naqqa.elasticsearch.indices.rollover;

import java.util.List;

public record RolloverResult(boolean shouldRollover, List<String> matchedConditions) {
}
