package com.naqqa.sso.otp;

import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class DevLogTransport implements Transport {
    private static final Logger LOG = Logger.getLogger(DevLogTransport.class.getName());

    @Override
    public void send(Channel channel, String destination, String templateId, Map<String, String> vars) {
        LOG.log(Level.INFO, "dev transport channel={0} to={1} template={2} vars={3}",
                new Object[]{channel, mask(destination), templateId, new TreeMap<>(vars)});
    }

    public static String mask(String destination) {
        if (destination == null || destination.isEmpty()) {
            return "***";
        }
        int at = destination.indexOf('@');
        if (at > 0) {
            String local = destination.substring(0, at);
            String domain = destination.substring(at + 1);
            int dot = domain.lastIndexOf('.');
            String tld = dot >= 0 ? domain.substring(dot) : "";
            return local.charAt(0) + "***@" + (domain.isEmpty() ? "" : domain.charAt(0)) + "***" + tld;
        }
        int keep = Math.min(2, destination.length() / 2);
        return "***" + destination.substring(destination.length() - keep);
    }
}
