package com.naqqa.sso.ldap;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.oauth.DirectoryConnector;
import com.naqqa.sso.util.Bytes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class LdapDirectoryConnector implements DirectoryConnector {
    public static final int MAX_USERNAME = 256;
    public static final int MAX_PASSWORD = 1024;
    private static final int MAX_SIZE_LIMIT = 1000;

    private final LdapConfig config;

    public LdapDirectoryConnector(LdapConfig config) {
        if (config == null) {
            throw new SsoException("ldap.config_invalid");
        }
        this.config = config;
    }

    @Override
    public DirectoryUser bind(String username, char[] password) {
        if (password == null || password.length == 0) {
            throw new SsoException("ldap.password_empty");
        }
        if (password.length > MAX_PASSWORD) {
            throw new SsoException("ldap.password_invalid");
        }
        if (username == null || username.isEmpty() || username.length() > MAX_USERNAME || hasControl(username)) {
            throw new SsoException("ldap.username_invalid");
        }
        List<Filter> any = new ArrayList<>();
        for (String attribute : config.loginAttributes()) {
            any.add(Filter.equal(attribute, username));
        }
        Filter filter = any.size() == 1 ? any.get(0) : Filter.or(any);
        List<String> wanted = new ArrayList<>(List.of(config.groupAttribute(), "userPrincipalName", "cn", "displayName", "mail"));
        wanted.addAll(config.loginAttributes());
        LdapConnection.SearchOutcome lookup = serviceSearch(filter, new ArrayList<>(new LinkedHashSet<>(wanted)), 2);
        List<LdapConnection.Entry> found = lookup.entries();
        if (found.size() > 1 || lookup.code() == 4) {
            throw new SsoException("ldap.ambiguous_user");
        }
        if (found.isEmpty()) {
            dummyBind(password);
            throw new SsoException("ldap.invalid_credentials");
        }
        LdapConnection.Entry entry = found.get(0);
        String bindName = bindName(entry, username);
        try (LdapConnection c = LdapConnection.open(config)) {
            LdapConnection.Result r = c.bind(bindName, password);
            if (r.code() == 49 || r.code() == 34 || r.code() == 48) {
                throw new SsoException("ldap.invalid_credentials");
            }
            if (r.code() != 0) {
                throw new SsoException("ldap.bind_failed");
            }
        }
        return toUser(entry);
    }

    @Override
    public List<DirectoryUser> search(SearchQuery query) {
        if (query == null || query.value() == null || query.value().length() > MAX_USERNAME) {
            throw new SsoException("ldap.filter_invalid");
        }
        int limit = Math.min(Math.max(query.sizeLimit(), 1), MAX_SIZE_LIMIT);
        List<String> attrs = query.returnAttributes();
        for (String a : attrs) {
            if (!Filter.ATTRIBUTE.matcher(a).matches() && !a.equals("*") && !a.equals("+")) {
                throw new SsoException("ldap.attribute_invalid");
            }
        }
        Filter filter = Filter.equal(query.attribute(), query.value());
        String base = query.baseDn() == null || query.baseDn().isBlank() ? config.baseDn() : query.baseDn();
        if (!withinBase(base)) {
            throw new SsoException("ldap.base_dn_outside");
        }
        try (LdapConnection c = LdapConnection.open(config)) {
            serviceBind(c);
            LdapConnection.SearchOutcome o = c.search(base, filter, attrs, limit, config.timeLimitSeconds());
            checkSearchCode(o.code());
            List<DirectoryUser> users = new ArrayList<>();
            for (LdapConnection.Entry e : o.entries()) {
                users.add(toUser(e));
            }
            return users;
        }
    }

    private LdapConnection.SearchOutcome serviceSearch(Filter filter, List<String> attributes, int sizeLimit) {
        try (LdapConnection c = LdapConnection.open(config)) {
            serviceBind(c);
            LdapConnection.SearchOutcome o = c.search(config.baseDn(), filter, attributes, sizeLimit, config.timeLimitSeconds());
            checkSearchCode(o.code());
            return o;
        }
    }

    private void dummyBind(char[] password) {
        String dn = "cn=naqqa-sso-absent-" + Bytes.hex(Bytes.random(16)) + "," + config.baseDn();
        try (LdapConnection c = LdapConnection.open(config)) {
            c.bind(dn, password);
        } catch (SsoException e) {
            return;
        }
    }

    private void serviceBind(LdapConnection c) {
        char[] pw = config.servicePassword();
        try {
            LdapConnection.Result r = c.bind(config.serviceDn(), pw);
            if (r.code() != 0) {
                throw new SsoException("ldap.service_bind_failed");
            }
        } finally {
            java.util.Arrays.fill(pw, '\0');
        }
    }

    private boolean withinBase(String base) {
        List<String> root = rdns(config.baseDn());
        List<String> b = rdns(base);
        if (root == null || b == null || b.size() < root.size()) {
            return false;
        }
        return b.subList(b.size() - root.size(), b.size()).equals(root);
    }

    static List<String> rdns(String dn) {
        if (dn == null || dn.indexOf('"') >= 0) {
            return null;
        }
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < dn.length(); i++) {
            char c = dn.charAt(i);
            if (c == '\\') {
                if (i + 1 >= dn.length()) {
                    return null;
                }
                cur.append(c).append(dn.charAt(++i));
            } else if (c == ',' || c == ';') {
                String r = rdn(cur.toString());
                if (r == null) {
                    return null;
                }
                out.add(r);
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        String r = rdn(cur.toString());
        if (r == null) {
            return null;
        }
        out.add(r);
        return out;
    }

    private static String rdn(String raw) {
        int eq = -1;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '=') {
                eq = i;
                break;
            }
        }
        if (eq <= 0) {
            return null;
        }
        String type = raw.substring(0, eq).trim();
        String value = raw.substring(eq + 1).trim();
        if (type.isEmpty() || value.isEmpty() || !type.matches("[A-Za-z][A-Za-z0-9-]*|[0-9]+(\\.[0-9]+)*")) {
            return null;
        }
        return type.toLowerCase(java.util.Locale.ROOT) + "=" + value.toLowerCase(java.util.Locale.ROOT);
    }

    private static void checkSearchCode(int code) {
        if (code != 0 && code != 4 && code != 10) {
            throw new SsoException("ldap.search_failed");
        }
    }

    private String bindName(LdapConnection.Entry entry, String username) {
        if (config.bindMode() == LdapConfig.BindMode.DN) {
            return entry.dn();
        }
        List<String> upn = attribute(entry, "userPrincipalName");
        if (upn != null && upn.size() == 1 && !upn.get(0).isEmpty()) {
            return upn.get(0);
        }
        if (entry.dn() == null || entry.dn().isEmpty()) {
            throw new SsoException("ldap.upn_unavailable");
        }
        return entry.dn();
    }

    private static List<String> attribute(LdapConnection.Entry entry, String name) {
        for (Map.Entry<String, List<String>> e : entry.attributes().entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static DirectoryUser toUser(LdapConnection.Entry e) {
        return new DirectoryUser(e.dn(), new LinkedHashMap<>(e.attributes()));
    }

    public List<String> groups(DirectoryUser user) {
        for (Map.Entry<String, List<String>> e : user.attributes().entrySet()) {
            if (e.getKey().equalsIgnoreCase(config.groupAttribute())) {
                return List.copyOf(e.getValue());
            }
        }
        return List.of();
    }

    private static boolean hasControl(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return true;
            }
        }
        return false;
    }
}
