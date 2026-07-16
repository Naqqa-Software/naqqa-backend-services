package com.naqqa.outreach.service;

import com.naqqa.outreach.config.OutreachProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Company-context scraper — replaces the MostLogin anti-detect browser with plain HTTP + jsoup.
 * Fetches the homepage + up to 2 relevant sub-pages and extracts title, meta description,
 * headings and about/mission text (the same signals extractCompanyInfo.js pulled), capped at
 * 5 snippets of ≤100 words each.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CompanyScraper {

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0 Safari/537.36";
    private static final String[] SUBPATHS = {"about", "about-us", "company", "team", "impressum", "mission", "vision"};

    private final OutreachProperties props;

    public List<String> scrape(String website) {
        List<String> snippets = new ArrayList<>();
        String base = normalize(website);
        if (base == null) {
            return snippets;
        }
        Document home = fetch(base);
        if (home == null) {
            return snippets;
        }
        collect(home, snippets);

        int extra = 0;
        Set<String> visited = new LinkedHashSet<>();
        for (Element a : home.select("a[href]")) {
            if (extra >= 2 || snippets.size() >= 5) {
                break;
            }
            String href = a.absUrl("href").toLowerCase();
            if (href.isBlank()) {
                continue;
            }
            for (String p : SUBPATHS) {
                if (href.contains("/" + p) && visited.add(href)) {
                    Document sub = fetch(a.absUrl("href"));
                    if (sub != null) {
                        collect(sub, snippets);
                        extra++;
                    }
                    break;
                }
            }
        }
        return snippets.stream().distinct().limit(5).toList();
    }

    private void collect(Document doc, List<String> out) {
        add(out, doc.title());
        Element meta = doc.selectFirst("meta[name=description]");
        if (meta != null) {
            add(out, meta.attr("content"));
        }
        for (Element h : doc.select("h1, h2, h3")) {
            add(out, h.text());
        }
        for (Element s : doc.select("section, div")) {
            String cls = (s.className() + " " + s.id()).toLowerCase();
            if (cls.contains("about") || cls.contains("mission") || cls.contains("vision") || cls.contains("company")) {
                add(out, s.text());
            }
        }
    }

    private void add(List<String> out, String text) {
        if (text == null) {
            return;
        }
        String t = text.trim().replaceAll("\\s+", " ");
        if (t.length() < 40 || out.size() >= 5) {
            return;
        }
        String[] words = t.split(" ");
        if (words.length > 100) {
            t = String.join(" ", java.util.Arrays.copyOfRange(words, 0, 100));
        }
        if (!out.contains(t)) {
            out.add(t);
        }
    }

    private Document fetch(String url) {
        try {
            org.jsoup.Connection conn = Jsoup.connect(url)
                    .userAgent(UA)
                    .timeout(20000)
                    .followRedirects(true)
                    .ignoreHttpErrors(true)
                    .ignoreContentType(false);
            if (props.getProxyHost() != null && props.getProxyPort() != null) {
                conn.proxy(new Proxy(Proxy.Type.HTTP,
                        new InetSocketAddress(props.getProxyHost(), props.getProxyPort())));
            }
            return conn.get();
        } catch (Exception e) {
            log.debug("scrape failed for {}: {}", url, e.getMessage());
            return null;
        }
    }

    private String normalize(String website) {
        if (website == null || website.isBlank()) {
            return null;
        }
        String w = website.trim();
        if (!w.startsWith("http://") && !w.startsWith("https://")) {
            w = "https://" + w;
        }
        return w;
    }
}
