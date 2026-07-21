package com.naqqa.seofarm.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates a sitemap.xml for a site from THIS backend (ported from naqqa-server): the internal
 * pages (from the bundled site-links.json) + every blog slug for that {@code fromSite}.
 */
@Service
@RequiredArgsConstructor
public class SeoSitemapService {

    private static final List<String> FALLBACK_PAGES = List.of(
            "/", "/services", "/blog", "/about", "/contact");

    private final SeoPagesService pages;
    private final SeoBlogService blogs;

    public String buildSitemap(String fromSite) {
        String base = fromSite == null ? "" : fromSite.replaceAll("/+$", "");
        String today = LocalDate.now().toString();

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");

        for (String page : internalPages()) {
            xml.append("  <url>\n")
                    .append("    <loc>").append(base).append(page).append("</loc>\n")
                    .append("    <lastmod>").append(today).append("</lastmod>\n")
                    .append("    <changefreq>weekly</changefreq>\n")
                    .append("    <priority>").append(page.equals("/") ? "1.0" : "0.8").append("</priority>\n")
                    .append("  </url>\n");
        }
        for (String slug : blogs.slugsByFromSite(fromSite)) {
            xml.append("  <url>\n")
                    .append("    <loc>").append(base).append("/blog/").append(slug).append("</loc>\n")
                    .append("    <lastmod>").append(today).append("</lastmod>\n")
                    .append("    <changefreq>monthly</changefreq>\n")
                    .append("    <priority>0.6</priority>\n")
                    .append("  </url>\n");
        }
        xml.append("</urlset>");
        return xml.toString();
    }

    /** Internal page paths from the bundled site-links.json (falls back to a minimal set). */
    private List<String> internalPages() {
        try {
            JsonNode links = (JsonNode) pages.pages().get("siteLinks");
            List<String> out = new ArrayList<>();
            for (JsonNode p : links.path("internalPages")) {
                String url = p.path("url").asText("");
                // Skip in-page anchors (#…) — they're not distinct sitemap URLs.
                if (!url.isBlank() && !url.contains("#") && !out.contains(url)) {
                    out.add(url);
                }
            }
            return out.isEmpty() ? FALLBACK_PAGES : out;
        } catch (Exception e) {
            return FALLBACK_PAGES;
        }
    }
}
