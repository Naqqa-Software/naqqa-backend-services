package com.naqqa.elasticsearch.analysis.lang.b;

import java.util.Arrays;

abstract class SnowballProgram {

    protected char[] current;
    protected int cursor;
    protected int length;
    protected int limit;
    protected int limit_backward;
    protected int bra;
    protected int ket;

    protected SnowballProgram() {
        cursor = 0;
        length = limit = 0;
        limit_backward = 0;
        bra = cursor;
        ket = limit;
    }

    public abstract boolean stem();

    public void setCurrent(String value) {
        setCurrent(value.toCharArray(), value.length());
    }

    public String getCurrent() {
        return new String(current, 0, length);
    }

    public void setCurrent(char[] text, int length) {
        current = text;
        cursor = 0;
        this.length = limit = length;
        limit_backward = 0;
        bra = cursor;
        ket = limit;
    }

    protected boolean in_grouping(char[] s, int min, int max) {
        if (cursor >= limit) return false;
        int ch = current[cursor];
        if (ch > max || ch < min) return false;
        ch -= min;
        if ((s[ch >> 3] & (0X1 << (ch & 0X7))) == 0) return false;
        cursor++;
        return true;
    }

    protected boolean go_in_grouping(char[] s, int min, int max) {
        while (cursor < limit) {
            int ch = current[cursor];
            if (ch > max || ch < min) return true;
            ch -= min;
            if ((s[ch >> 3] & (0X1 << (ch & 0X7))) == 0) return true;
            cursor++;
        }
        return false;
    }

    protected boolean in_grouping_b(char[] s, int min, int max) {
        if (cursor <= limit_backward) return false;
        int ch = current[cursor - 1];
        if (ch > max || ch < min) return false;
        ch -= min;
        if ((s[ch >> 3] & (0X1 << (ch & 0X7))) == 0) return false;
        cursor--;
        return true;
    }

    protected boolean go_in_grouping_b(char[] s, int min, int max) {
        while (cursor > limit_backward) {
            int ch = current[cursor - 1];
            if (ch > max || ch < min) return true;
            ch -= min;
            if ((s[ch >> 3] & (0X1 << (ch & 0X7))) == 0) return true;
            cursor--;
        }
        return false;
    }

    protected boolean out_grouping(char[] s, int min, int max) {
        if (cursor >= limit) return false;
        int ch = current[cursor];
        if (ch > max || ch < min) {
            cursor++;
            return true;
        }
        ch -= min;
        if ((s[ch >> 3] & (0X1 << (ch & 0X7))) == 0) {
            cursor++;
            return true;
        }
        return false;
    }

    protected boolean go_out_grouping(char[] s, int min, int max) {
        while (cursor < limit) {
            int ch = current[cursor];
            if (ch <= max && ch >= min) {
                ch -= min;
                if ((s[ch >> 3] & (0X1 << (ch & 0X7))) != 0) {
                    return true;
                }
            }
            cursor++;
        }
        return false;
    }

    protected boolean out_grouping_b(char[] s, int min, int max) {
        if (cursor <= limit_backward) return false;
        int ch = current[cursor - 1];
        if (ch > max || ch < min) {
            cursor--;
            return true;
        }
        ch -= min;
        if ((s[ch >> 3] & (0X1 << (ch & 0X7))) == 0) {
            cursor--;
            return true;
        }
        return false;
    }

    protected boolean go_out_grouping_b(char[] s, int min, int max) {
        while (cursor > limit_backward) {
            int ch = current[cursor - 1];
            if (ch <= max && ch >= min) {
                ch -= min;
                if ((s[ch >> 3] & (0X1 << (ch & 0X7))) != 0) {
                    return true;
                }
            }
            cursor--;
        }
        return false;
    }

    protected boolean eq_s(CharSequence s) {
        if (limit - cursor < s.length()) return false;
        for (int i = 0; i != s.length(); i++) {
            if (current[cursor + i] != s.charAt(i)) return false;
        }
        cursor += s.length();
        return true;
    }

    protected boolean eq_s_b(CharSequence s) {
        if (cursor - limit_backward < s.length()) return false;
        for (int i = 0; i != s.length(); i++) {
            if (current[cursor - s.length() + i] != s.charAt(i)) return false;
        }
        cursor -= s.length();
        return true;
    }

    protected int find_among(SnowballAmong[] v) {
        int i = 0;
        int j = v.length;
        int c = cursor;
        int l = limit;
        int common_i = 0;
        int common_j = 0;
        boolean first_key_inspected = false;
        while (true) {
            int k = i + ((j - i) >> 1);
            int diff = 0;
            int common = common_i < common_j ? common_i : common_j;
            SnowballAmong w = v[k];
            int i2;
            for (i2 = common; i2 < w.s.length; i2++) {
                if (c + common == l) {
                    diff = -1;
                    break;
                }
                diff = current[c + common] - w.s[i2];
                if (diff != 0) break;
                common++;
            }
            if (diff < 0) {
                j = k;
                common_j = common;
            } else {
                i = k;
                common_i = common;
            }
            if (j - i <= 1) {
                if (i > 0) break;
                if (j == i) break;
                if (first_key_inspected) break;
                first_key_inspected = true;
            }
        }
        while (true) {
            SnowballAmong w = v[i];
            if (common_i >= w.s.length) {
                cursor = c + w.s.length;
                return w.result;
            }
            i = w.substring_i;
            if (i < 0) return 0;
        }
    }

    protected int find_among_b(SnowballAmong[] v) {
        int i = 0;
        int j = v.length;
        int c = cursor;
        int lb = limit_backward;
        int common_i = 0;
        int common_j = 0;
        boolean first_key_inspected = false;
        while (true) {
            int k = i + ((j - i) >> 1);
            int diff = 0;
            int common = common_i < common_j ? common_i : common_j;
            SnowballAmong w = v[k];
            int i2;
            for (i2 = w.s.length - 1 - common; i2 >= 0; i2--) {
                if (c - common == lb) {
                    diff = -1;
                    break;
                }
                diff = current[c - 1 - common] - w.s[i2];
                if (diff != 0) break;
                common++;
            }
            if (diff < 0) {
                j = k;
                common_j = common;
            } else {
                i = k;
                common_i = common;
            }
            if (j - i <= 1) {
                if (i > 0) break;
                if (j == i) break;
                if (first_key_inspected) break;
                first_key_inspected = true;
            }
        }
        while (true) {
            SnowballAmong w = v[i];
            if (common_i >= w.s.length) {
                cursor = c - w.s.length;
                return w.result;
            }
            i = w.substring_i;
            if (i < 0) return 0;
        }
    }

    protected int replace_s(int c_bra, int c_ket, CharSequence s) {
        final int adjustment = s.length() - (c_ket - c_bra);
        final int newLength = length + adjustment;
        if (newLength > current.length) {
            current = Arrays.copyOf(current, newLength);
        }
        if (adjustment != 0 && c_ket < length) {
            System.arraycopy(current, c_ket, current, c_bra + s.length(), length - c_ket);
        }
        for (int i = 0; i < s.length(); i++) current[c_bra + i] = s.charAt(i);
        length += adjustment;
        limit += adjustment;
        if (cursor >= c_ket) cursor += adjustment;
        else if (cursor > c_bra) cursor = c_bra;
        return adjustment;
    }

    protected void slice_from(CharSequence s) {
        replace_s(bra, ket, s);
        ket = bra + s.length();
    }

    protected void slice_del() {
        slice_from("");
    }

    protected void insert(int c_bra, int c_ket, CharSequence s) {
        int adjustment = replace_s(c_bra, c_ket, s);
        if (c_bra <= bra) bra += adjustment;
        if (c_bra <= ket) ket += adjustment;
    }
}
