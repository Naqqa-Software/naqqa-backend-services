package com.naqqa.elasticsearch.indices.ilm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LifecyclePolicy {

    private final String name;
    private final Map<PhaseName, Phase> phases;

    public LifecyclePolicy(String name, Map<PhaseName, Phase> phases) {
        this.name = name;
        this.phases = new LinkedHashMap<>(phases);
    }

    public String getName() {
        return name;
    }

    public Phase getPhase(PhaseName name) {
        return phases.get(name);
    }

    public Map<PhaseName, Phase> getPhases() {
        return phases;
    }

    public List<PhaseName> orderedPhaseNames() {
        List<PhaseName> ordered = new ArrayList<>(phases.keySet());
        ordered.sort(Comparator.comparingInt(Enum::ordinal));
        return ordered;
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;
        private final Map<PhaseName, Phase> phases = new LinkedHashMap<>();

        private Builder(String name) {
            this.name = name;
        }

        public Builder phase(Phase phase) {
            phases.put(phase.getName(), phase);
            return this;
        }

        public LifecyclePolicy build() {
            return new LifecyclePolicy(name, phases);
        }
    }
}
