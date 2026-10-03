package com.naqqa.analytics.registry;

import java.util.Map;

public record EventSpec(String name, String group, boolean serverOnly, Map<String, PropSpec> props) {
}
