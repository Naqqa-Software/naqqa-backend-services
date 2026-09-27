package com.naqqa.elasticsearch.common.settings;

import java.util.Set;

public final class IndexScopedSettings extends AbstractScopedSettings {

    public IndexScopedSettings(Settings settings, Set<Setting<?>> settingsSet) {
        super(settings, settingsSet, Setting.Property.INDEX_SCOPE);
    }
}
