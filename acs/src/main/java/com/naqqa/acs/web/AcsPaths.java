package com.naqqa.acs.web;

import com.naqqa.acs.events.AcsRequestSigner;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

public final class AcsPaths {

    private AcsPaths() {
    }

    public static String canonical(String raw) {
        if (raw == null) {
            return "/";
        }
        return AcsRequestSigner.normalizePath(decode(raw.replaceAll(";[^/]*", "")));
    }

    private static String decode(String path) {
        try {
            return URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return path;
        }
    }
}
