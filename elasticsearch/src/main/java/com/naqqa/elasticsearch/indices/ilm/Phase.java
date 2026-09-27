package com.naqqa.elasticsearch.indices.ilm;

import com.naqqa.elasticsearch.common.unit.TimeValue;

import java.util.List;

public final class Phase {

    private final PhaseName name;
    private final TimeValue minAge;
    private final List<LifecycleAction> actions;

    public Phase(PhaseName name, TimeValue minAge, List<LifecycleAction> actions) {
        this.name = name;
        this.minAge = minAge == null ? TimeValue.ZERO : minAge;
        this.actions = List.copyOf(actions);
    }

    public PhaseName getName() {
        return name;
    }

    public TimeValue getMinAge() {
        return minAge;
    }

    public List<LifecycleAction> getActions() {
        return actions;
    }
}
