package com.naqqa.elasticsearch.analysis.charfilter.html;

import com.naqqa.elasticsearch.analysis.FilteredText;

import java.util.Locale;
import java.util.Set;

final class HtmlStripScanner {

    private final CharSequence input;
    private final FilteredText output;
    private final Set<String> escapedTags;
    private final boolean escapeBr;
    private final boolean escapeScript;
    private final boolean escapeStyle;
    private final int len;
    private int pos;
    private int cumulativeDiff;

    HtmlStripScanner(CharSequence input, FilteredText output, Set<String> escapedTags, boolean escapeBr, boolean escapeScript, boolean escapeStyle) {
        this.input = input;
        this.output = output;
        this.escapedTags = escapedTags;
        this.escapeBr = escapeBr;
        this.escapeScript = escapeScript;
        this.escapeStyle = escapeStyle;
        this.len = input.length();
    }

    void run() {
        while (pos < len) {
            char c = input.charAt(pos);
            if (c == '<' && handleTagLike()) {
                continue;
            }
            if (c == '&' && handleEntity()) {
                continue;
            }
            output.text().append(c);
            pos++;
        }
    }

    private void advance(int consumed, int appended) {
        cumulativeDiff += consumed - appended;
        output.addOffCorrectMap(output.text().length(), cumulativeDiff);
        pos += consumed;
    }

    private boolean handleEntity() {
        int start = pos + 1;
        if (start < len && input.charAt(start) == '#') {
            int p = start + 1;
            boolean hex = p < len && (input.charAt(p) == 'x' || input.charAt(p) == 'X');
            if (hex) {
                p++;
            }
            int digitsStart = p;
            while (p < len && (hex ? isHexDigit(input.charAt(p)) : Character.isDigit(input.charAt(p)))) {
                p++;
            }
            if (p > digitsStart && p < len && input.charAt(p) == ';') {
                int codePoint;
                try {
                    codePoint = Integer.parseInt(input.subSequence(digitsStart, p).toString(), hex ? 16 : 10);
                } catch (NumberFormatException e) {
                    return false;
                }
                if (codePoint < 0 || codePoint > 0x10FFFF) {
                    return false;
                }
                int before = output.text().length();
                output.text().appendCodePoint(codePoint);
                int appended = output.text().length() - before;
                advance(p + 1 - pos, appended);
                return true;
            }
            return false;
        }
        int k = HtmlCharacterEntities.longestMatch(input, start, len);
        if (k > 0 && start + k < len && input.charAt(start + k) == ';') {
            Character ch = HtmlCharacterEntities.get(input.subSequence(start, start + k).toString());
            if (ch != null) {
                output.text().append(ch.charValue());
                advance(k + 2, 1);
                return true;
            }
        }
        return false;
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private boolean handleTagLike() {
        if (pos + 1 >= len) {
            return false;
        }
        char next = input.charAt(pos + 1);
        if (next == '!') {
            return handleBang();
        }
        if (next == '?') {
            int end = indexOf("?>", pos + 2);
            int consumed = end >= 0 ? end + 2 - pos : len - pos;
            advance(consumed, 0);
            return true;
        }
        return handleTag();
    }

    private boolean handleBang() {
        if (startsWith("<!--", pos)) {
            int end = indexOf("-->", pos + 4);
            int consumed = end >= 0 ? end + 3 - pos : len - pos;
            advance(consumed, 0);
            return true;
        }
        if (startsWith("<![CDATA[", pos)) {
            int contentStart = pos + 9;
            int end = indexOf("]]>", contentStart);
            int contentEnd = end >= 0 ? end : len;
            int before = output.text().length();
            output.text().append(input, contentStart, contentEnd);
            int appended = output.text().length() - before;
            int consumed = (end >= 0 ? end + 3 : len) - pos;
            advance(consumed, appended);
            return true;
        }
        int tagEnd = findTagEnd(pos);
        int consumed = tagEnd >= 0 ? tagEnd + 1 - pos : len - pos;
        advance(consumed, 0);
        return true;
    }

    private boolean handleTag() {
        int i = pos + 1;
        boolean closing = false;
        if (i < len && input.charAt(i) == '/') {
            closing = true;
            i++;
        }
        int nameStart = i;
        while (i < len && (Character.isLetterOrDigit(input.charAt(i)) || input.charAt(i) == '-' || input.charAt(i) == ':')) {
            i++;
        }
        if (i == nameStart) {
            return false;
        }
        String name = input.subSequence(nameStart, i).toString().toLowerCase(Locale.ROOT);
        int tagEnd = findTagEnd(pos);
        if (tagEnd < 0) {
            return false;
        }
        boolean escaped = isEscaped(name);
        if (escaped) {
            int before = output.text().length();
            output.text().append(input, pos, tagEnd + 1);
            int appended = output.text().length() - before;
            advance(tagEnd + 1 - pos, appended);
            return true;
        }
        if (!closing && (name.equals("script") || name.equals("style"))) {
            String closeTag = "</" + name;
            int searchFrom = tagEnd + 1;
            int closeStart = indexOfIgnoreCase(closeTag, searchFrom);
            int consumed;
            if (closeStart < 0) {
                consumed = len - pos;
            } else {
                int closeTagEnd = findTagEnd(closeStart);
                consumed = (closeTagEnd >= 0 ? closeTagEnd + 1 : len) - pos;
            }
            advance(consumed, 0);
            return true;
        }
        advance(tagEnd + 1 - pos, 0);
        return true;
    }

    private boolean isEscaped(String name) {
        if (name.equals("script")) {
            return escapeScript;
        }
        if (name.equals("style")) {
            return escapeStyle;
        }
        if (name.equals("br")) {
            return escapeBr;
        }
        return escapedTags.contains(name);
    }

    private int findTagEnd(int start) {
        int i = start + 1;
        boolean inSingle = false;
        boolean inDouble = false;
        while (i < len) {
            char c = input.charAt(i);
            if (inSingle) {
                if (c == '\'') {
                    inSingle = false;
                }
            } else if (inDouble) {
                if (c == '"') {
                    inDouble = false;
                }
            } else if (c == '\'') {
                inSingle = true;
            } else if (c == '"') {
                inDouble = true;
            } else if (c == '>') {
                return i;
            }
            i++;
        }
        return -1;
    }

    private boolean startsWith(String s, int at) {
        int n = s.length();
        if (at + n > len) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            if (input.charAt(at + i) != s.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private int indexOf(String s, int from) {
        int n = s.length();
        int max = len - n;
        outer:
        for (int i = Math.max(from, 0); i <= max; i++) {
            for (int j = 0; j < n; j++) {
                if (input.charAt(i + j) != s.charAt(j)) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private int indexOfIgnoreCase(String s, int from) {
        int n = s.length();
        int max = len - n;
        outer:
        for (int i = Math.max(from, 0); i <= max; i++) {
            for (int j = 0; j < n; j++) {
                if (Character.toLowerCase(input.charAt(i + j)) != Character.toLowerCase(s.charAt(j))) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
