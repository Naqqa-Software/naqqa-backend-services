package com.naqqa.seofarm.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.naqqa.seofarm.config.SeoFarmProperties;
import com.naqqa.seofarm.model.SeoBlogImage;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the blog HTML — port of blogPublisher.js. Markdown→HTML (flexmark, with tables), internal
 * [anchor] links resolved against the allowlist, H2/H3 anchor ids, an auto Table of Contents,
 * schema.org JSON-LD (Article + BreadcrumbList + FAQPage) and a full standalone HTML document with
 * OG/Twitter meta, embedded CSS and image attribution. Returns {bodyHtml, fullHtml}.
 */
@Service
@RequiredArgsConstructor
public class BlogHtmlBuilder {

    private final SeoFarmProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final Pattern H_TAG = Pattern.compile("<(h2|h3)>(.*?)</\\1>", Pattern.DOTALL);
    private static final Pattern BRACKET_LINK = Pattern.compile("\\[([^\\]\\[]+)]");

    public record Result(String bodyHtml, String fullHtml) {
    }

    public Result build(JsonNode blog, SeoBlogImage image, String fromSite, String slug) {
        String title = blog.path("title").asText("");
        String description = blog.path("metaDescription").asText("");
        String markdown = blog.path("body").asText("");

        // 1. Resolve bare [anchor] internal links to markdown links, then render.
        markdown = resolveInternalLinks(markdown, blog.path("internalLinks"));
        String rendered = renderMarkdown(markdown);
        // 2. Add id anchors to H2/H3 for TOC navigation.
        rendered = addHeadingIds(rendered);
        String toc = buildToc(rendered);
        String bodyHtml = toc + rendered;

        String base = fromSite == null ? "" : fromSite.replaceAll("/+$", "");
        String canonical = base + "/blog/" + slug;
        String fullHtml = buildFullHtml(blog, image, title, description, bodyHtml, canonical);
        return new Result(bodyHtml, fullHtml);
    }

    // ── Markdown ────────────────────────────────────────────────────────────────
    private String renderMarkdown(String markdown) {
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, List.of(TablesExtension.create()));
        Parser parser = Parser.builder(options).build();
        HtmlRenderer renderer = HtmlRenderer.builder(options).build();
        // Strip any stray H1 the model may have added (template owns the H1).
        String md = markdown.replaceAll("(?m)^#\\s+.*$", "").replaceAll("(?m)^.*\\n=+\\s*$", "");
        return renderer.render(parser.parse(md));
    }

    private String resolveInternalLinks(String markdown, JsonNode internalLinks) {
        if (internalLinks == null || !internalLinks.isArray()) {
            return markdown;
        }
        String out = markdown;
        for (JsonNode link : internalLinks) {
            String anchor = link.path("anchor").asText("");
            String url = link.path("url").asText("");
            if (anchor.isBlank() || url.isBlank()) {
                continue;
            }
            // [anchor] (not already [anchor](...)) → [anchor](url)
            out = out.replaceAll("\\[" + Pattern.quote(anchor) + "](?!\\()",
                    Matcher.quoteReplacement("[" + anchor + "](" + url + ")"));
        }
        return out;
    }

    private String addHeadingIds(String html) {
        Matcher m = H_TAG.matcher(html);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String tag = m.group(1);
            String inner = m.group(2);
            String id = anchor(inner.replaceAll("<[^>]+>", ""));
            m.appendReplacement(sb, Matcher.quoteReplacement("<" + tag + " id=\"" + id + "\">" + inner + "</" + tag + ">"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String buildToc(String html) {
        Matcher m = Pattern.compile("<h2 id=\"([^\"]+)\">(.*?)</h2>", Pattern.DOTALL).matcher(html);
        StringBuilder items = new StringBuilder();
        while (m.find()) {
            String text = m.group(2).replaceAll("<[^>]+>", "").trim();
            if (text.equalsIgnoreCase("table of contents")) {
                continue;
            }
            items.append("<li><a href=\"#").append(m.group(1)).append("\">").append(escape(text)).append("</a></li>");
        }
        if (items.length() == 0) {
            return "";
        }
        return "<nav class=\"table-of-contents\"><strong>On this page</strong><ul>" + items + "</ul></nav>";
    }

    // ── Full document ───────────────────────────────────────────────────────────
    private String buildFullHtml(JsonNode blog, SeoBlogImage image, String title, String description,
                                 String bodyHtml, String canonical) {
        String imgUrl = image != null ? image.getUrl() : "";
        String imgAlt = image != null && image.getAlt() != null ? image.getAlt() : title;
        String keywords = joinArray(blog.path("tags"));

        StringBuilder h = new StringBuilder();
        h.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n")
                .append("<meta charset=\"UTF-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n")
                .append("<title>").append(escape(title)).append("</title>\n")
                .append("<link rel=\"canonical\" href=\"").append(escape(canonical)).append("\">\n")
                .append(meta("description", description))
                .append(meta("keywords", keywords))
                .append(meta("author", "Naqqa Team"))
                .append(og("og:type", "article")).append(og("og:title", title))
                .append(og("og:description", description)).append(og("og:image", imgUrl))
                .append(og("og:url", canonical))
                .append(meta("twitter:card", "summary_large_image"))
                .append(meta("twitter:title", title)).append(meta("twitter:description", description))
                .append(meta("twitter:image", imgUrl))
                .append(jsonLd(articleSchema(title, description, imgUrl, canonical, keywords)))
                .append(jsonLd(breadcrumbSchema(title, canonical)));
        String faq = faqSchema(blog.path("faqQuestions"));
        if (faq != null) {
            h.append(jsonLd(faq));
        }
        h.append("<style>").append(css()).append("</style>\n</head>\n<body>\n")
                .append("<article itemscope itemtype=\"https://schema.org/Article\">\n")
                .append("<h1 itemprop=\"headline\">").append(escape(title)).append("</h1>\n");
        if (!imgUrl.isBlank()) {
            h.append("<img itemprop=\"image\" src=\"").append(escape(imgUrl)).append("\" alt=\"").append(escape(imgAlt)).append("\" />\n");
            if (props.isShowImageAttribution() && image != null && image.getPhotographer() != null) {
                h.append("<p class=\"image-attribution\">Photo by ").append(escape(image.getPhotographer()))
                        .append(" from <a href=\"").append(escape(image.getPexelsUrl())).append("\" target=\"_blank\" rel=\"noopener\">Pexels</a></p>\n");
            }
        }
        h.append("<div itemprop=\"articleBody\">\n").append(bodyHtml).append("\n</div>\n");
        String tags = joinArray(blog.path("tags"));
        if (!tags.isBlank()) {
            h.append("<footer class=\"tags\">");
            for (JsonNode t : blog.path("tags")) {
                h.append("<span class=\"tag\">").append(escape(t.asText())).append("</span>");
            }
            h.append("</footer>\n");
        }
        h.append("</article>\n");
        h.append(analyticsBeacon(canonical));
        h.append("</body>\n</html>");
        return h.toString();
    }

    // ── schema.org ──────────────────────────────────────────────────────────────
    private String articleSchema(String title, String desc, String img, String url, String keywords) {
        ObjectNode a = mapper.createObjectNode();
        a.put("@context", "https://schema.org");
        a.put("@type", "Article");
        a.put("headline", title);
        a.put("description", desc);
        a.put("image", img);
        a.put("datePublished", Instant.now().toString());
        a.put("dateModified", Instant.now().toString());
        ObjectNode author = a.putObject("author");
        author.put("@type", "Person");
        author.put("name", "Naqqa Team");
        ObjectNode pub = a.putObject("publisher");
        pub.put("@type", "Organization");
        pub.put("name", "Naqqa Software");
        a.put("keywords", keywords);
        return a.toString();
    }

    private String breadcrumbSchema(String title, String url) {
        ObjectNode b = mapper.createObjectNode();
        b.put("@context", "https://schema.org");
        b.put("@type", "BreadcrumbList");
        ArrayNode items = b.putArray("itemListElement");
        items.add(listItem(1, "Home", url.replaceAll("/blog/.*$", "")));
        items.add(listItem(2, "Blog", url.replaceAll("/blog/.*$", "/blog")));
        items.add(listItem(3, title, url));
        return b.toString();
    }

    private ObjectNode listItem(int pos, String name, String item) {
        ObjectNode n = mapper.createObjectNode();
        n.put("@type", "ListItem");
        n.put("position", pos);
        n.put("name", name);
        n.put("item", item);
        return n;
    }

    private String faqSchema(JsonNode faqs) {
        if (faqs == null || !faqs.isArray() || faqs.isEmpty()) {
            return null;
        }
        ObjectNode f = mapper.createObjectNode();
        f.put("@context", "https://schema.org");
        f.put("@type", "FAQPage");
        ArrayNode main = f.putArray("mainEntity");
        for (JsonNode q : faqs) {
            ObjectNode qn = mapper.createObjectNode();
            qn.put("@type", "Question");
            qn.put("name", q.path("question").asText());
            ObjectNode ans = qn.putObject("acceptedAnswer");
            ans.put("@type", "Answer");
            ans.put("text", q.path("answer").asText());
            main.add(qn);
        }
        return f.toString();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────
    private String jsonLd(String json) {
        return "<script type=\"application/ld+json\">" + json + "</script>\n";
    }

    private String meta(String name, String content) {
        return "<meta name=\"" + name + "\" content=\"" + escape(content) + "\">\n";
    }

    private String og(String property, String content) {
        return "<meta property=\"" + property + "\" content=\"" + escape(content) + "\">\n";
    }

    private String joinArray(JsonNode arr) {
        if (arr == null || !arr.isArray()) {
            return "";
        }
        List<String> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.asText()));
        return String.join(", ", out);
    }

    private String anchor(String text) {
        return text.toLowerCase().replaceAll("[^a-z0-9\\s-]", "").trim().replaceAll("\\s+", "-");
    }

    private String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** naqqa-analytics tracker snippet for the blog page (empty when no analytics base URL is set). */
    private String analyticsBeacon(String canonical) {
        String base = props.getAnalyticsBaseUrl();
        if (base == null || base.isBlank()) {
            return "";
        }
        String host = "";
        String slug = "";
        try {
            java.net.URI u = java.net.URI.create(canonical);
            host = u.getHost() == null ? "" : u.getHost();
            String path = u.getPath() == null ? "" : u.getPath();
            slug = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        } catch (Exception ignored) {
            // best-effort; still emit the tag with what we have
        }
        return "<script defer src=\"" + escape(base.replaceAll("/+$", "")) + "/api/public/analytics/tracker.js\""
                + " data-property=\"" + escape(host) + "\" data-entity-type=\"blog\""
                + " data-entity-id=\"" + escape(slug) + "\"></script>\n";
    }

    private String css() {
        return "body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;line-height:1.7;color:#1f2937;max-width:800px;margin:0 auto;padding:2rem 1rem}"
                + "h1{font-size:2rem;line-height:1.2}h2{margin-top:2rem;border-bottom:1px solid #e5e7eb;padding-bottom:.3rem}"
                + "img{max-width:100%;height:auto;border-radius:8px}"
                + "table{border-collapse:collapse;width:100%;margin:1rem 0}th,td{border:1px solid #e5e7eb;padding:.5rem .75rem;text-align:left}"
                + "blockquote{border-left:4px solid #009b7e;background:#f0fdfa;margin:1rem 0;padding:.75rem 1rem;border-radius:0 8px 8px 0}"
                + ".table-of-contents{background:#f9fafb;border:1px solid #e5e7eb;border-radius:8px;padding:1rem 1.25rem;margin:1.5rem 0}"
                + ".table-of-contents ul{margin:.5rem 0 0;padding-left:1.2rem}.table-of-contents a{color:#009b7e;text-decoration:none}"
                + ".image-attribution{font-size:.8rem;color:#6b7280}"
                + ".tags{margin-top:2rem;display:flex;flex-wrap:wrap;gap:.4rem}.tag{background:#e6f7f3;color:#037a63;padding:.2rem .6rem;border-radius:999px;font-size:.8rem}";
    }
}
