package com.naqqa.elasticsearch.analysis.hyphenation;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

public final class HyphenationTree {

    private static final class Node {
        private Map<Character, Node> children;
        private byte[] values;

        Node child(char c) {
            return children == null ? null : children.get(c);
        }

        Node getOrCreate(char c) {
            if (children == null) {
                children = new HashMap<>();
            }
            return children.computeIfAbsent(c, k -> new Node());
        }
    }

    private final Node root = new Node();
    private final Map<Character, Character> classmap = new HashMap<>();
    private final Map<String, List<Object>> stoplist = new HashMap<>();
    private int patternCount;

    public HyphenationTree() {
    }

    public static HyphenationTree load(Path xmlFile) throws IOException {
        try (InputStream in = Files.newInputStream(xmlFile)) {
            return load(in);
        }
    }

    public static HyphenationTree load(InputStream in) throws IOException {
        HyphenationTree tree = new HyphenationTree();
        tree.loadPatterns(in);
        return tree;
    }

    public void loadPatterns(InputStream in) throws IOException {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setValidating(false);
            factory.setXIncludeAware(false);
            setFeature(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
            setFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
            setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            setFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            SAXParser parser = factory.newSAXParser();
            XMLReader reader = parser.getXMLReader();
            PatternHandler handler = new PatternHandler(this);
            reader.setContentHandler(handler);
            reader.setErrorHandler(handler);
            reader.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            reader.parse(new InputSource(new BufferedInputStream(in)));
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        } catch (SAXException e) {
            throw new IOException("Unable to parse hyphenation patterns: " + e.getMessage(), e);
        }
    }

    private static void setFeature(SAXParserFactory factory, String name, boolean value) {
        try {
            factory.setFeature(name, value);
        } catch (ParserConfigurationException | SAXException e) {
            return;
        }
    }

    public void addClass(String chars) {
        if (chars == null || chars.isEmpty()) {
            return;
        }
        char equiv = chars.charAt(0);
        for (int i = 0; i < chars.length(); i++) {
            classmap.put(chars.charAt(i), equiv);
        }
    }

    public void addException(String word, List<Object> hyphenatedParts) {
        stoplist.put(word, new ArrayList<>(hyphenatedParts));
    }

    public void addPattern(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return;
        }
        StringBuilder letters = new StringBuilder();
        List<Byte> values = new ArrayList<>();
        int pending = 0;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (Character.isDigit(c)) {
                pending = Character.digit(c, 10);
            } else {
                values.add((byte) pending);
                pending = 0;
                letters.append(c);
            }
        }
        values.add((byte) pending);
        if (letters.length() == 0) {
            return;
        }
        Node node = root;
        for (int i = 0; i < letters.length(); i++) {
            node = node.getOrCreate(letters.charAt(i));
        }
        byte[] v = new byte[values.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = values.get(i);
        }
        if (node.values == null) {
            patternCount++;
        }
        node.values = v;
    }

    public int patternCount() {
        return patternCount;
    }

    public String findPattern(String letters) {
        Node node = root;
        for (int i = 0; i < letters.length() && node != null; i++) {
            node = node.child(letters.charAt(i));
        }
        if (node == null || node.values == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : node.values) {
            sb.append((char) ('0' + b));
        }
        return sb.toString();
    }

    private int classOf(char c) {
        if (classmap.isEmpty()) {
            return Character.isLetter(c) ? Character.toLowerCase(c) : -1;
        }
        Character mapped = classmap.get(c);
        return mapped == null ? -1 : mapped;
    }

    private void searchPatterns(char[] word, int index, byte[] il) {
        Node node = root;
        for (int p = index; p < word.length && word[p] != 0; p++) {
            node = node.child(word[p]);
            if (node == null) {
                return;
            }
            byte[] values = node.values;
            if (values != null) {
                int j = index;
                for (int k = 0; k < values.length; k++) {
                    if (j < il.length && values[k] > il[j]) {
                        il[j] = values[k];
                    }
                    j++;
                }
            }
        }
    }

    public Hyphenation hyphenate(String word, int remainCharCount, int pushCharCount) {
        char[] w = word.toCharArray();
        return hyphenate(w, 0, w.length, remainCharCount, pushCharCount);
    }

    public Hyphenation hyphenate(char[] w, int offset, int len, int remainCharCount, int pushCharCount) {
        char[] word = new char[len + 3];
        int ignoreAtBeginning = 0;
        int length = len;
        boolean endOfLetters = false;
        for (int i = 1; i <= len; i++) {
            int nc = classOf(w[offset + i - 1]);
            if (nc < 0) {
                if (i == 1 + ignoreAtBeginning) {
                    ignoreAtBeginning++;
                } else {
                    endOfLetters = true;
                }
                length--;
            } else {
                if (!endOfLetters) {
                    word[i - ignoreAtBeginning] = (char) nc;
                } else {
                    return null;
                }
            }
        }
        len = length;
        if (len < remainCharCount + pushCharCount) {
            return null;
        }
        int[] result = new int[len + 1];
        int k = 0;
        String sw = new String(word, 1, len);
        List<Object> hw = stoplist.get(sw);
        if (hw != null) {
            int j = 0;
            for (Object o : hw) {
                if (o instanceof String s) {
                    j += s.length();
                    if (j >= remainCharCount && j < len - pushCharCount) {
                        result[k++] = j + ignoreAtBeginning;
                    }
                }
            }
        } else {
            word[0] = '.';
            word[len + 1] = '.';
            word[len + 2] = 0;
            byte[] il = new byte[len + 3];
            for (int i = 0; i < len + 1; i++) {
                searchPatterns(word, i, il);
            }
            for (int i = 0; i < len; i++) {
                if ((il[i + 1] & 1) == 1 && i >= remainCharCount && i <= len - pushCharCount) {
                    result[k++] = i + ignoreAtBeginning;
                }
            }
        }
        if (k == 0) {
            return null;
        }
        int[] res = new int[k + 2];
        System.arraycopy(result, 0, res, 1, k);
        res[0] = 0;
        res[k + 1] = len;
        return new Hyphenation(res);
    }

    private static final class PatternHandler extends DefaultHandler {
        private static final int NONE = 0;
        private static final int CLASSES = 1;
        private static final int EXCEPTIONS = 2;
        private static final int PATTERNS = 3;

        private final HyphenationTree tree;
        private final StringBuilder text = new StringBuilder();
        private final List<Object> exception = new ArrayList<>();
        private String hyphenChar = "-";
        private int state = NONE;

        PatternHandler(HyphenationTree tree) {
            this.tree = tree;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attrs) {
            String name = qName;
            switch (name) {
                case "hyphen-char" -> {
                    String v = attrs.getValue("value");
                    if (v != null && v.length() == 1) {
                        hyphenChar = v;
                    }
                }
                case "classes" -> {
                    state = CLASSES;
                    text.setLength(0);
                }
                case "patterns" -> {
                    state = PATTERNS;
                    text.setLength(0);
                }
                case "exceptions" -> {
                    state = EXCEPTIONS;
                    text.setLength(0);
                    exception.clear();
                }
                case "hyphen" -> {
                    if (state == EXCEPTIONS) {
                        if (text.length() > 0) {
                            exception.add(text.toString());
                            text.setLength(0);
                        }
                        exception.add(new Hyphen(attrs.getValue("pre"), attrs.getValue("no"), attrs.getValue("post")));
                    }
                }
                default -> {
                }
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            switch (qName) {
                case "classes" -> {
                    for (String token : tokens(text)) {
                        tree.addClass(token);
                    }
                    text.setLength(0);
                    state = NONE;
                }
                case "patterns" -> {
                    for (String token : tokens(text)) {
                        tree.addPattern(token);
                    }
                    text.setLength(0);
                    state = NONE;
                }
                case "exceptions" -> {
                    finishException();
                    state = NONE;
                }
                default -> {
                }
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (state == CLASSES || state == PATTERNS) {
                text.append(ch, start, length);
            } else if (state == EXCEPTIONS) {
                for (int i = start; i < start + length; i++) {
                    char c = ch[i];
                    if (Character.isWhitespace(c)) {
                        finishException();
                    } else {
                        text.append(c);
                    }
                }
            }
        }

        @Override
        public void error(org.xml.sax.SAXParseException e) {
        }

        @Override
        public void fatalError(org.xml.sax.SAXParseException e) throws SAXException {
            throw e;
        }

        private void finishException() {
            if (text.length() > 0) {
                exception.add(text.toString());
                text.setLength(0);
            }
            if (exception.isEmpty()) {
                return;
            }
            List<Object> normalized = normalizeException(exception);
            String word = exceptionWord(normalized);
            if (!word.isEmpty()) {
                tree.addException(word, normalized);
            }
            exception.clear();
        }

        private List<Object> normalizeException(List<Object> ex) {
            List<Object> res = new ArrayList<>();
            char hc = hyphenChar.charAt(0);
            for (Object item : ex) {
                if (item instanceof String str) {
                    StringBuilder buf = new StringBuilder();
                    for (int i = 0; i < str.length(); i++) {
                        char c = str.charAt(i);
                        if (c != hc) {
                            buf.append(c);
                        } else {
                            res.add(buf.toString());
                            buf.setLength(0);
                            res.add(new Hyphen(hyphenChar));
                        }
                    }
                    if (buf.length() > 0) {
                        res.add(buf.toString());
                    }
                } else {
                    res.add(item);
                }
            }
            return res;
        }

        private static String exceptionWord(List<Object> ex) {
            StringBuilder res = new StringBuilder();
            for (Object item : ex) {
                if (item instanceof String s) {
                    res.append(s);
                } else if (item instanceof Hyphen h && h.noBreak() != null) {
                    res.append(h.noBreak());
                }
            }
            return res.toString();
        }

        private static List<String> tokens(CharSequence cs) {
            List<String> out = new ArrayList<>();
            String s = cs.toString().trim();
            if (s.isEmpty()) {
                return out;
            }
            for (String t : s.split("\\s+")) {
                if (!t.isEmpty()) {
                    out.add(t);
                }
            }
            return out;
        }
    }
}
