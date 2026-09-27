package com.naqqa.elasticsearch.indices.ilm;

import com.naqqa.elasticsearch.indices.rollover.RolloverConditions;

import java.util.Map;

public sealed interface LifecycleAction {

    String name();

    record RolloverAction(RolloverConditions conditions) implements LifecycleAction {
        @Override
        public String name() {
            return "rollover";
        }
    }

    record ShrinkAction(int numberOfShards) implements LifecycleAction {
        @Override
        public String name() {
            return "shrink";
        }
    }

    record ForceMergeAction(int maxNumSegments) implements LifecycleAction {
        @Override
        public String name() {
            return "forcemerge";
        }
    }

    record AllocateAction(Integer numberOfReplicas, Map<String, String> include, Map<String, String> exclude,
                           Map<String, String> require) implements LifecycleAction {
        public AllocateAction(Integer numberOfReplicas, Map<String, String> include, Map<String, String> exclude,
                               Map<String, String> require) {
            this.numberOfReplicas = numberOfReplicas;
            this.include = include == null ? Map.of() : Map.copyOf(include);
            this.exclude = exclude == null ? Map.of() : Map.copyOf(exclude);
            this.require = require == null ? Map.of() : Map.copyOf(require);
        }

        @Override
        public String name() {
            return "allocate";
        }
    }

    record MigrateAction(boolean enabled) implements LifecycleAction {
        @Override
        public String name() {
            return "migrate";
        }
    }

    record ReadOnlyAction() implements LifecycleAction {
        @Override
        public String name() {
            return "readonly";
        }
    }

    record DeleteAction(boolean deleteSearchableSnapshot) implements LifecycleAction {
        @Override
        public String name() {
            return "delete";
        }
    }

    record SetPriorityAction(Integer priority) implements LifecycleAction {
        @Override
        public String name() {
            return "set_priority";
        }
    }
}
