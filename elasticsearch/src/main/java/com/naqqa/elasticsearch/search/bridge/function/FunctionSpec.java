package com.naqqa.elasticsearch.search.bridge.function;

import com.naqqa.elasticsearch.script.Script;
import com.naqqa.elasticsearch.script.ScriptContext;
import com.naqqa.elasticsearch.script.ScriptService;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public interface FunctionSpec {

    double evaluate(LeafDocLookup doc, float subScore);

    final class Weight implements FunctionSpec {
        private final float weight;

        public Weight(float weight) {
            this.weight = weight;
        }

        @Override
        public double evaluate(LeafDocLookup doc, float subScore) {
            return weight;
        }
    }

    final class FieldValueFactor implements FunctionSpec {
        private final String field;
        private final float factor;
        private final String modifier;
        private final Double missing;

        public FieldValueFactor(String field, float factor, String modifier, Double missing) {
            this.field = field;
            this.factor = factor;
            this.modifier = modifier == null ? "none" : modifier.toLowerCase(Locale.ROOT);
            this.missing = missing;
        }

        @Override
        public double evaluate(LeafDocLookup doc, float subScore) {
            Double raw = doc.numericValue(field);
            double value;
            if (raw == null) {
                if (missing == null) {
                    throw new IllegalArgumentException("Unable to find a value for field [" + field + "]");
                }
                value = missing;
            } else {
                value = raw;
            }
            value *= factor;
            return switch (modifier) {
                case "log" -> Math.log10(value);
                case "log1p" -> Math.log10(value + 1);
                case "log2p" -> Math.log10(value + 2);
                case "ln" -> Math.log(value);
                case "ln1p" -> Math.log(value + 1);
                case "ln2p" -> Math.log(value + 2);
                case "square" -> value * value;
                case "sqrt" -> Math.sqrt(value);
                case "reciprocal" -> 1.0 / value;
                default -> value;
            };
        }
    }

    final class RandomScore implements FunctionSpec {
        private final long seed;

        public RandomScore(Long seed) {
            this.seed = seed == null ? 0L : seed;
        }

        @Override
        public double evaluate(LeafDocLookup doc, float subScore) {
            long h = seed;
            h ^= doc.docId() * 0x9E3779B97F4A7C15L;
            h ^= (h >>> 33);
            h *= 0xFF51AFD7ED558CCDL;
            h ^= (h >>> 33);
            h *= 0xC4CEB9FE1A85EC53L;
            h ^= (h >>> 33);
            return (double) (h >>> 11) / (double) (1L << 53);
        }
    }

    final class Decay implements FunctionSpec {
        private final String decayType;
        private final String field;
        private final double origin;
        private final double scale;
        private final double offset;
        private final double decay;

        public Decay(String decayType, String field, double origin, double scale, double offset, double decay) {
            this.decayType = decayType.toLowerCase(Locale.ROOT);
            this.field = field;
            this.origin = origin;
            this.scale = Math.abs(scale) < 1e-12 ? 1e-12 : Math.abs(scale);
            this.offset = Math.abs(offset);
            this.decay = decay <= 0 ? 1e-12 : (decay >= 1 ? 1 - 1e-12 : decay);
        }

        @Override
        public double evaluate(LeafDocLookup doc, float subScore) {
            Double raw = doc.numericValue(field);
            if (raw == null) {
                return 0.0;
            }
            double distance = Math.max(0.0, Math.abs(raw - origin) - offset);
            if (distance <= 0) {
                return 1.0;
            }
            double lnDecay = Math.log(decay);
            return switch (decayType) {
                case "exp" -> Math.exp(lnDecay * distance / scale);
                case "linear" -> Math.max(0.0, 1.0 - (1.0 - decay) * distance / scale);
                default -> Math.exp(lnDecay * (distance * distance) / (scale * scale));
            };
        }
    }

    final class ScriptScore implements FunctionSpec {
        private final Script script;
        private final ScriptService scriptService;

        public ScriptScore(Script script, ScriptService scriptService) {
            this.script = script;
            this.scriptService = scriptService;
        }

        @Override
        public double evaluate(LeafDocLookup doc, float subScore) {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("doc", doc);
            vars.put("_score", (double) subScore);
            Object result = scriptService.execute(script, ScriptContext.SCORE, vars);
            if (result instanceof Number n) {
                return n.doubleValue();
            }
            throw new IllegalArgumentException("script_score script must return a number, got [" + result + "]");
        }
    }
}
