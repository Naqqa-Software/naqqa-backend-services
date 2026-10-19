package com.naqqa.seofarm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.naqqa.seofarm.config.SeoFarmProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AU-BE-27: the blog body and title come from the model, whose prompt carries scraped competitor pages, so the
 * published HTML must not carry script, handlers or javascript: links, and JSON-LD must not break out of its tag.
 */
class BlogHtmlBuilderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void modelOutputCannotInjectScript() {
        ObjectNode blog = mapper.createObjectNode();
        blog.put("title", "Guide </script><script>alert(1)</script>");
        blog.put("metaDescription", "desc");
        blog.put("body", "## Intro\n\nText <script>alert(2)</script> <img src=x onerror=alert(3)>\n\n"
                + "[click](javascript:alert(4)) and [ok](https://example.com/page) and [anchor](#intro)\n\n"
                + "<iframe src=\"https://evil.example\"></iframe>\n\n| a | b |\n|---|---|\n| 1 | 2 |\n");
        BlogHtmlBuilder.Result r = new BlogHtmlBuilder(new SeoFarmProperties()).build(blog, null, "https://blog.example.com", "guide");

        for (String html : new String[] { r.bodyHtml(), r.fullHtml() }) {
            assertFalse(html.contains("alert(2)</script>"), html);
            assertFalse(html.toLowerCase().contains("onerror"), html);
            assertFalse(html.toLowerCase().contains("javascript:"), html);
            assertFalse(html.contains("<iframe"), html);
        }
        assertFalse(r.fullHtml().contains("</script><script>alert(1)"), r.fullHtml());
        assertTrue(r.bodyHtml().contains("href=\"https://example.com/page\""), r.bodyHtml());
        assertTrue(r.bodyHtml().contains("<h2 id=\"intro\">"), r.bodyHtml());
        assertTrue(r.bodyHtml().contains("<table>"), r.bodyHtml());
        assertTrue(r.bodyHtml().contains("href=\"#intro\""), r.bodyHtml());
    }
}
