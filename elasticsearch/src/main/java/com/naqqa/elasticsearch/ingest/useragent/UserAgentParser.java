package com.naqqa.elasticsearch.ingest.useragent;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UserAgentParser {

    private UserAgentParser() {
    }

    private record BrowserRule(Pattern pattern, String name) {
    }

    private record OsRule(Pattern pattern, String name) {
    }

    private static final List<BrowserRule> BROWSER_RULES = List.of(
        new BrowserRule(Pattern.compile("Edg(?:e|A|iOS)?/(\\d+(?:\\.\\d+)*)"), "Edge"),
        new BrowserRule(Pattern.compile("OPR/(\\d+(?:\\.\\d+)*)"), "Opera"),
        new BrowserRule(Pattern.compile("SamsungBrowser/(\\d+(?:\\.\\d+)*)"), "Samsung Internet"),
        new BrowserRule(Pattern.compile("CriOS/(\\d+(?:\\.\\d+)*)"), "Chrome Mobile iOS"),
        new BrowserRule(Pattern.compile("Chrome/(\\d+(?:\\.\\d+)*)"), "Chrome"),
        new BrowserRule(Pattern.compile("FxiOS/(\\d+(?:\\.\\d+)*)"), "Firefox iOS"),
        new BrowserRule(Pattern.compile("Firefox/(\\d+(?:\\.\\d+)*)"), "Firefox"),
        new BrowserRule(Pattern.compile("Version/(\\d+(?:\\.\\d+)*).*Safari/"), "Safari"),
        new BrowserRule(Pattern.compile("MSIE (\\d+(?:\\.\\d+)*)"), "Internet Explorer"),
        new BrowserRule(Pattern.compile("Trident/.*rv:(\\d+(?:\\.\\d+)*)"), "Internet Explorer")
    );

    private static final List<OsRule> OS_RULES = List.of(
        new OsRule(Pattern.compile("Windows NT 10\\.0"), "Windows 10"),
        new OsRule(Pattern.compile("Windows NT 6\\.3"), "Windows 8.1"),
        new OsRule(Pattern.compile("Windows NT 6\\.2"), "Windows 8"),
        new OsRule(Pattern.compile("Windows NT 6\\.1"), "Windows 7"),
        new OsRule(Pattern.compile("Windows NT (\\d+\\.\\d+)"), "Windows"),
        new OsRule(Pattern.compile("iPhone OS (\\d+[_.]\\d+(?:[_.]\\d+)?)"), "iOS"),
        new OsRule(Pattern.compile("CPU OS (\\d+[_.]\\d+(?:[_.]\\d+)?) like Mac OS X"), "iOS"),
        new OsRule(Pattern.compile("Mac OS X (\\d+[_.]\\d+(?:[_.]\\d+)?)"), "Mac OS X"),
        new OsRule(Pattern.compile("Android (\\d+(?:\\.\\d+)*)"), "Android"),
        new OsRule(Pattern.compile("Linux"), "Linux"),
        new OsRule(Pattern.compile("CrOS"), "Chrome OS")
    );

    public static UserAgentInfo parse(String ua) {
        String browserName = "Other";
        String browserVersion = null;
        for (BrowserRule rule : BROWSER_RULES) {
            Matcher m = rule.pattern().matcher(ua);
            if (m.find()) {
                browserName = rule.name();
                browserVersion = m.groupCount() > 0 ? m.group(1) : null;
                break;
            }
        }

        String osName = "Other";
        String osVersion = null;
        for (OsRule rule : OS_RULES) {
            Matcher m = rule.pattern().matcher(ua);
            if (m.find()) {
                osName = rule.name();
                osVersion = m.groupCount() > 0 ? normalizeVersion(m.group(1)) : null;
                break;
            }
        }
        String osFull = osVersion != null ? osName + " " + osVersion : osName;

        boolean mobile = ua.contains("Mobile") || ua.contains("Android") || ua.contains("iPhone");
        String deviceName;
        if (ua.contains("iPad")) {
            deviceName = "iPad";
        } else if (ua.contains("iPhone")) {
            deviceName = "iPhone";
        } else if (ua.contains("Android") && ua.contains("Mobile")) {
            deviceName = "Smartphone";
        } else if (ua.contains("Android")) {
            deviceName = "Tablet";
        } else {
            deviceName = "Other";
        }

        return new UserAgentInfo(browserName, browserVersion, osName, osVersion, osFull, deviceName, mobile);
    }

    private static String normalizeVersion(String version) {
        return version.replace('_', '.');
    }
}
