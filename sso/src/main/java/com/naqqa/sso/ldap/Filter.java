package com.naqqa.sso.ldap;

import com.naqqa.sso.SsoException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

public sealed interface Filter permits Filter.Equal, Filter.Present, Filter.And, Filter.Or {
    Pattern ATTRIBUTE = Pattern.compile("[A-Za-z][A-Za-z0-9-]{0,63}");

    byte[] encode();

    String toRfc4515();

    private static String checkAttribute(String attribute) {
        if (attribute == null || !ATTRIBUTE.matcher(attribute).matches()) {
            throw new SsoException("ldap.attribute_invalid");
        }
        return attribute;
    }

    static String escape(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        for (byte b : raw) {
            int c = b & 0xff;
            if (c == '*' || c == '(' || c == ')' || c == '\\' || c == 0 || c >= 0x80) {
                sb.append('\\').append(Character.forDigit(c >>> 4, 16)).append(Character.forDigit(c & 0xf, 16));
            } else {
                sb.append((char) c);
            }
        }
        return sb.toString();
    }

    static Filter equal(String attribute, String value) {
        return new Equal(attribute, value);
    }

    static Filter present(String attribute) {
        return new Present(attribute);
    }

    static Filter and(List<Filter> parts) {
        return new And(parts);
    }

    static Filter or(List<Filter> parts) {
        return new Or(parts);
    }

    record Equal(String attribute, String value) implements Filter {
        public Equal {
            checkAttribute(attribute);
            if (value == null) {
                throw new SsoException("ldap.filter_invalid");
            }
        }

        @Override
        public byte[] encode() {
            return Ber.tlv(0xa3, Ber.octets(attribute), Ber.octets(value));
        }

        @Override
        public String toRfc4515() {
            return "(" + attribute + "=" + escape(value) + ")";
        }
    }

    record Present(String attribute) implements Filter {
        public Present {
            checkAttribute(attribute);
        }

        @Override
        public byte[] encode() {
            return Ber.tlv(0x87, attribute.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String toRfc4515() {
            return "(" + attribute + "=*)";
        }
    }

    record And(List<Filter> parts) implements Filter {
        public And {
            parts = copy(parts);
        }

        @Override
        public byte[] encode() {
            return Ber.tlv(0xa0, parts.stream().map(Filter::encode).toArray(byte[][]::new));
        }

        @Override
        public String toRfc4515() {
            return "(&" + String.join("", parts.stream().map(Filter::toRfc4515).toList()) + ")";
        }
    }

    record Or(List<Filter> parts) implements Filter {
        public Or {
            parts = copy(parts);
        }

        @Override
        public byte[] encode() {
            return Ber.tlv(0xa1, parts.stream().map(Filter::encode).toArray(byte[][]::new));
        }

        @Override
        public String toRfc4515() {
            return "(|" + String.join("", parts.stream().map(Filter::toRfc4515).toList()) + ")";
        }
    }

    private static List<Filter> copy(List<Filter> parts) {
        if (parts == null || parts.isEmpty() || parts.size() > 16) {
            throw new SsoException("ldap.filter_invalid");
        }
        return List.copyOf(parts);
    }
}
