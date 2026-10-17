package com.naqqa.sso.ldap;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class LdapMessages {
    private LdapMessages() {
    }

    static byte[] bindRequest(String name, char[] password) {
        byte[] pw = new String(password).getBytes(StandardCharsets.UTF_8);
        return Ber.tlv(LdapConnection.APP_BIND_REQUEST, Ber.integer(3), Ber.octets(name), Ber.tlv(0x80, pw));
    }

    static byte[] startTlsRequest() {
        return Ber.tlv(LdapConnection.APP_EXTENDED_REQUEST, Ber.tlv(0x80, LdapConnection.START_TLS_OID.getBytes(StandardCharsets.US_ASCII)));
    }

    static byte[] searchRequest(String baseDn, Filter filter, List<String> attributes, int sizeLimit, int timeLimit) {
        byte[][] attrs = attributes.stream().map(Ber::octets).toArray(byte[][]::new);
        return Ber.tlv(LdapConnection.APP_SEARCH_REQUEST,
                Ber.octets(baseDn), Ber.enumerated(2), Ber.enumerated(0), Ber.integer(sizeLimit), Ber.integer(timeLimit),
                Ber.bool(false), filter.encode(), Ber.tlv(Ber.SEQUENCE, attrs));
    }

    static LdapConnection.Result parseResult(BerReader body) {
        long code = body.integer(Ber.ENUMERATED);
        if (code < 0 || code > 4096) {
            throw BerReader.protocol();
        }
        body.octets(Ber.OCTET_STRING);
        body.octets(Ber.OCTET_STRING);
        while (body.hasMore()) {
            body.skip();
        }
        return new LdapConnection.Result((int) code);
    }

    static LdapConnection.Entry parseEntry(BerReader body) {
        String dn = body.string(Ber.OCTET_STRING);
        if (dn.isEmpty() || dn.length() > 8192) {
            throw BerReader.protocol();
        }
        BerReader attrs = body.enter(Ber.SEQUENCE);
        body.finish();
        Map<String, List<String>> map = new LinkedHashMap<>();
        int count = 0;
        while (attrs.hasMore()) {
            if (++count > LdapConnection.MAX_ATTRIBUTES) {
                throw BerReader.protocol();
            }
            BerReader attr = attrs.enter(Ber.SEQUENCE);
            String type = attr.string(Ber.OCTET_STRING);
            if (type.isEmpty() || type.length() > 128) {
                throw BerReader.protocol();
            }
            BerReader vals = attr.enter(Ber.SET);
            attr.finish();
            List<String> values = new ArrayList<>();
            while (vals.hasMore()) {
                if (values.size() >= LdapConnection.MAX_VALUES) {
                    throw BerReader.protocol();
                }
                values.add(vals.string(Ber.OCTET_STRING));
            }
            map.computeIfAbsent(type, k -> new ArrayList<>()).addAll(values);
        }
        return new LdapConnection.Entry(dn, map);
    }
}
