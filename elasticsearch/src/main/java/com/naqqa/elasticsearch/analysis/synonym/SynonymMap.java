package com.naqqa.elasticsearch.analysis.synonym;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SynonymMap {

    static final class Node {
        Map<String, Node> children;
        List<List<String>> outputs;

        Node child(String word, boolean create) {
            if (children == null) {
                if (!create) {
                    return null;
                }
                children = new LinkedHashMap<>();
            }
            Node n = children.get(word);
            if (n == null && create) {
                n = new Node();
                children.put(word, n);
            }
            return n;
        }
    }

    final Node root = new Node();

    private SynonymMap() {
    }

    public static SynonymMap build(List<String> rules, boolean expand, boolean lenient, boolean wordnet) {
        SynonymMap map = new SynonymMap();
        if (rules == null) {
            return map;
        }
        if (wordnet) {
            map.buildWordnet(rules, expand, lenient);
        } else {
            for (String rawLine : rules) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                try {
                    map.addSolrRule(line, expand);
                } catch (RuntimeException e) {
                    if (!lenient) {
                        throw new IllegalArgumentException("Invalid synonym rule [" + rawLine + "]: " + e.getMessage(), e);
                    }
                }
            }
        }
        return map;
    }

    private void addSolrRule(String line, boolean expand) {
        int arrow = line.indexOf("=>");
        if (arrow >= 0) {
            List<List<String>> lhs = parsePhrases(line.substring(0, arrow));
            List<List<String>> rhs = parsePhrases(line.substring(arrow + 2));
            if (lhs.isEmpty() || rhs.isEmpty()) {
                throw new IllegalArgumentException("empty side in mapping rule");
            }
            for (List<String> in : lhs) {
                addRule(in, rhs);
            }
        } else {
            List<List<String>> group = parsePhrases(line);
            if (group.size() < 2) {
                return;
            }
            if (expand) {
                for (List<String> in : group) {
                    addRule(in, group);
                }
            } else {
                List<List<String>> canonical = List.of(group.get(0));
                for (List<String> in : group) {
                    addRule(in, canonical);
                }
            }
        }
    }

    private static final Pattern WORDNET_LINE = Pattern.compile("s\\((\\d+),\\d+,'([^']+)'");

    private void buildWordnet(List<String> rules, boolean expand, boolean lenient) {
        Map<String, List<List<String>>> synsets = new LinkedHashMap<>();
        for (String rawLine : rules) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            Matcher m = WORDNET_LINE.matcher(line);
            if (!m.find()) {
                if (!lenient) {
                    throw new IllegalArgumentException("Invalid wordnet synonym rule [" + rawLine + "]");
                }
                continue;
            }
            String id = m.group(1);
            String word = m.group(2).replace('_', ' ');
            List<String> phrase = splitWords(word);
            synsets.computeIfAbsent(id, k -> new ArrayList<>()).add(phrase);
        }
        for (List<List<String>> group : synsets.values()) {
            if (group.size() < 2) {
                continue;
            }
            if (expand) {
                for (List<String> in : group) {
                    addRule(in, group);
                }
            } else {
                List<List<String>> canonical = List.of(group.get(0));
                for (List<String> in : group) {
                    addRule(in, canonical);
                }
            }
        }
    }

    private void addRule(List<String> input, List<List<String>> outputs) {
        if (input.isEmpty()) {
            return;
        }
        Node node = root;
        for (String word : input) {
            node = node.child(word, true);
        }
        if (node.outputs == null) {
            node.outputs = new ArrayList<>();
        }
        for (List<String> out : outputs) {
            if (!node.outputs.contains(out)) {
                node.outputs.add(out);
            }
        }
    }

    private static List<List<String>> parsePhrases(String side) {
        List<List<String>> out = new ArrayList<>();
        for (String part : side.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            out.add(splitWords(trimmed));
        }
        return out;
    }

    private static List<String> splitWords(String phrase) {
        List<String> words = new ArrayList<>();
        for (String w : phrase.trim().split("\\s+")) {
            if (!w.isEmpty()) {
                words.add(w);
            }
        }
        return words;
    }
}
