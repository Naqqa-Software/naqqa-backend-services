package com.naqqa.elasticsearch.analysis.lang.b;

import com.naqqa.elasticsearch.analysis.TokenFilter;
import com.naqqa.elasticsearch.analysis.TokenStream;

public final class SerbianNormalizationFilter extends TokenFilter {

    public SerbianNormalizationFilter(TokenStream input) {
        super(input);
    }

    @Override
    public boolean incrementToken() {
        if (!input.incrementToken()) {
            return false;
        }
        char[] buffer = token.buffer();
        int length = token.length();
        for (int i = 0; i < length; i++) {
            final char c = buffer[i];
            switch (c) {
                case 'а' -> buffer[i] = 'a';
                case 'б' -> buffer[i] = 'b';
                case 'в' -> buffer[i] = 'v';
                case 'г' -> buffer[i] = 'g';
                case 'д' -> buffer[i] = 'd';
                case 'ђ', 'đ' -> {
                    buffer = token.resizeBuffer(1 + length);
                    if (i < length) {
                        System.arraycopy(buffer, i, buffer, i + 1, length - i);
                    }
                    buffer[i] = 'd';
                    buffer[++i] = 'j';
                    length++;
                }
                case 'е' -> buffer[i] = 'e';
                case 'ж', 'з', 'ž' -> buffer[i] = 'z';
                case 'и' -> buffer[i] = 'i';
                case 'ј' -> buffer[i] = 'j';
                case 'к' -> buffer[i] = 'k';
                case 'л' -> buffer[i] = 'l';
                case 'љ' -> {
                    buffer = token.resizeBuffer(1 + length);
                    if (i < length) {
                        System.arraycopy(buffer, i, buffer, i + 1, length - i);
                    }
                    buffer[i] = 'l';
                    buffer[++i] = 'j';
                    length++;
                }
                case 'м' -> buffer[i] = 'm';
                case 'н' -> buffer[i] = 'n';
                case 'њ' -> {
                    buffer = token.resizeBuffer(1 + length);
                    if (i < length) {
                        System.arraycopy(buffer, i, buffer, i + 1, length - i);
                    }
                    buffer[i] = 'n';
                    buffer[++i] = 'j';
                    length++;
                }
                case 'о' -> buffer[i] = 'o';
                case 'п' -> buffer[i] = 'p';
                case 'р' -> buffer[i] = 'r';
                case 'с' -> buffer[i] = 's';
                case 'т' -> buffer[i] = 't';
                case 'ћ', 'ц', 'ч', 'č', 'ć' -> buffer[i] = 'c';
                case 'у' -> buffer[i] = 'u';
                case 'ф' -> buffer[i] = 'f';
                case 'х' -> buffer[i] = 'h';
                case 'џ' -> {
                    buffer = token.resizeBuffer(1 + length);
                    if (i < length) {
                        System.arraycopy(buffer, i, buffer, i + 1, length - i);
                    }
                    buffer[i] = 'd';
                    buffer[++i] = 'z';
                    length++;
                }
                case 'ш', 'š' -> buffer[i] = 's';
                default -> {
                }
            }
        }
        token.setLength(length);
        return true;
    }
}
