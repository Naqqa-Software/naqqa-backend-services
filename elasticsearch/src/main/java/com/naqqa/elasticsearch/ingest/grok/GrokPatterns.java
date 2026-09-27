package com.naqqa.elasticsearch.ingest.grok;

import java.util.LinkedHashMap;
import java.util.Map;

public final class GrokPatterns {

    private GrokPatterns() {
    }

    public static final Map<String, String> DEFAULT = build();

    private static Map<String, String> build() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("USERNAME", "[a-zA-Z0-9._-]+");
        p.put("USER", "%{USERNAME}");
        p.put("INT", "(?:[+-]?(?:[0-9]+))");
        p.put("BASE10NUM", "(?:[+-]?(?:[0-9]+(?:\\.[0-9]+)?)|\\.[0-9]+)");
        p.put("NUMBER", "(?:%{BASE10NUM})");
        p.put("BASE16NUM", "(?:0[xX]?[0-9a-fA-F]+)");
        p.put("POSINT", "\\b(?:[1-9][0-9]*)\\b");
        p.put("NONNEGINT", "\\b(?:[0-9]+)\\b");
        p.put("WORD", "\\b\\w+\\b");
        p.put("NOTSPACE", "\\S+");
        p.put("SPACE", "\\s*");
        p.put("DATA", ".*?");
        p.put("GREEDYDATA", ".*");
        p.put("QUOTEDSTRING", "(?:\"(?:\\\\.|[^\\\\\"])*\"|'(?:\\\\.|[^\\\\'])*')");
        p.put("QS", "%{QUOTEDSTRING}");
        p.put("UUID", "[A-Fa-f0-9]{8}-(?:[A-Fa-f0-9]{4}-){3}[A-Fa-f0-9]{12}");
        p.put("COMMONMAC", "(?:[A-Fa-f0-9]{2}:){5}[A-Fa-f0-9]{2}");
        p.put("WINDOWSMAC", "(?:[A-Fa-f0-9]{2}-){5}[A-Fa-f0-9]{2}");
        p.put("CISCOMAC", "(?:[A-Fa-f0-9]{4}\\.){2}[A-Fa-f0-9]{4}");
        p.put("MAC", "(?:%{CISCOMAC}|%{WINDOWSMAC}|%{COMMONMAC})");
        p.put("IPV4", "(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)");
        p.put("IPV6", "(?:[0-9A-Fa-f]{1,4}:){7}[0-9A-Fa-f]{1,4}|::|(?:[0-9A-Fa-f]{1,4}:){1,7}:|(?:[0-9A-Fa-f]{1,4}:){1,6}:[0-9A-Fa-f]{1,4}|(?:[0-9A-Fa-f]{1,4}:){1,5}(?::[0-9A-Fa-f]{1,4}){1,2}|(?:[0-9A-Fa-f]{1,4}:){1,4}(?::[0-9A-Fa-f]{1,4}){1,3}|(?:[0-9A-Fa-f]{1,4}:){1,3}(?::[0-9A-Fa-f]{1,4}){1,4}|(?:[0-9A-Fa-f]{1,4}:){1,2}(?::[0-9A-Fa-f]{1,4}){1,5}|[0-9A-Fa-f]{1,4}:(?:(?::[0-9A-Fa-f]{1,4}){1,6})|:(?:(?::[0-9A-Fa-f]{1,4}){1,7}|:)");
        p.put("IP", "(?:%{IPV6}|%{IPV4})");
        p.put("HOSTNAME", "\\b(?:[0-9A-Za-z](?:[0-9A-Za-z-]{0,62})(?:\\.(?:[0-9A-Za-z](?:[0-9A-Za-z-]{0,62}))*)?\\b)");
        p.put("IPORHOST", "(?:%{IP}|%{HOSTNAME})");
        p.put("HOSTPORT", "%{IPORHOST}:%{POSINT}");
        p.put("UNIXPATH", "(?:/[\\w_%!$@:.,+~-]*)+");
        p.put("WINPATH", "(?:[A-Za-z]+:|\\\\)(?:\\\\[^\\\\?*]*)+");
        p.put("PATH", "(?:%{UNIXPATH}|%{WINPATH})");
        p.put("URIPROTO", "[A-Za-z]+(?:\\+[A-Za-z+]+)?");
        p.put("URIHOST", "%{IPORHOST}(?::%{POSINT})?");
        p.put("URIPATH", "(?:/[A-Za-z0-9$.+!*'(){},~:;=@#%_-]*)+");
        p.put("URIPARAM", "\\?[A-Za-z0-9$.+!*'|(){},~@#%&/=:;_?\\-\\[\\]<>]*");
        p.put("URIPATHPARAM", "%{URIPATH}(?:%{URIPARAM})?");
        p.put("URI", "%{URIPROTO}://(?:%{USER}(?::[^@]*)?@)?(?:%{URIHOST})?(?:%{URIPATHPARAM})?");
        p.put("MONTH", "\\b(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)\\b");
        p.put("MONTHNUM", "(?:0?[1-9]|1[0-2])");
        p.put("MONTHDAY", "(?:(?:0[1-9])|(?:[12][0-9])|(?:3[01])|[1-9])");
        p.put("YEAR", "(?:\\d\\d){1,2}");
        p.put("HOUR", "(?:2[0123]|[01]?[0-9])");
        p.put("MINUTE", "(?:[0-5][0-9])");
        p.put("SECOND", "(?:(?:[0-5][0-9]|60)(?:[:.,][0-9]+)?)");
        p.put("TIME", "%{HOUR}:%{MINUTE}(?::%{SECOND})?");
        p.put("DATE_US", "%{MONTHNUM}[/-]%{MONTHDAY}[/-]%{YEAR}");
        p.put("DATE_EU", "%{MONTHDAY}[./-]%{MONTHNUM}[./-]%{YEAR}");
        p.put("ISO8601_TIMEZONE", "(?:Z|[+-]%{HOUR}(?::?%{MINUTE}))");
        p.put("TIMESTAMP_ISO8601", "%{YEAR}-%{MONTHNUM}-%{MONTHDAY}[T ]%{HOUR}:?%{MINUTE}(?::?%{SECOND})?%{ISO8601_TIMEZONE}?");
        p.put("DATESTAMP", "%{DATE_US}[- ]%{TIME}");
        p.put("TZ", "(?:[PMCE][SD]T|UTC)");
        p.put("HTTPDATE", "%{MONTHDAY}/%{MONTH}/%{YEAR}:%{TIME} %{INT}");
        p.put("SYSLOGTIMESTAMP", "%{MONTH} +%{MONTHDAY} %{TIME}");
        p.put("LOGLEVEL", "([Aa]lert|ALERT|[Tt]race|TRACE|[Dd]ebug|DEBUG|[Nn]otice|NOTICE|[Ii]nfo|INFO|[Ww]arn?(?:ing)?|WARN?(?:ING)?|[Ee]rr?(?:or)?|ERR?(?:OR)?|[Cc]rit?(?:ical)?|CRIT?(?:ICAL)?|[Ff]atal|FATAL|[Ss]evere|SEVERE|EMERG(?:ENCY)?|[Ee]merg(?:ency)?)");
        p.put("COMMONAPACHELOG", "%{IPORHOST:clientip} %{USER:ident} %{USER:auth} \\[%{HTTPDATE:timestamp}\\] \"%{WORD:verb} %{DATA:request} HTTP/%{NUMBER:httpversion}\" %{NUMBER:response} (?:%{NUMBER:bytes}|-)");
        p.put("COMBINEDAPACHELOG", "%{COMMONAPACHELOG} (?:\"(?:%{DATA:referrer})\"|-) (?:\"(?:%{DATA:agent})\"|-)");
        return p;
    }
}
