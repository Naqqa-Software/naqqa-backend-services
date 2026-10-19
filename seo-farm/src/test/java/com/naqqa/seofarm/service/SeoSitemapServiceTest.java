package com.naqqa.seofarm.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** AU-BE-41: the public sitemap reflects fromSite; markup in it must never reach the XML as markup. */
class SeoSitemapServiceTest {

    private SeoSitemapService service(List<String> slugs) {
        SeoPagesService pages = mock(SeoPagesService.class);
        when(pages.pages()).thenReturn(Map.of());
        SeoBlogService blogs = mock(SeoBlogService.class);
        when(blogs.slugsByFromSite(any())).thenReturn(slugs);
        return new SeoSitemapService(pages, blogs);
    }

    @Test
    void reflectedSiteCannotInjectMarkup() {
        String attack = "</loc><x:script xmlns:x=\"http://www.w3.org/1999/xhtml\">alert(document.domain)</x:script><loc>";
        String xml = service(List.of("a\"<b>&c")).buildSitemap(attack);
        assertFalse(xml.contains("<x:script"), xml);
        assertFalse(xml.contains("<b>"), xml);
        assertTrue(xml.contains("&lt;"), xml);
    }

    @Test
    void normalSiteIsListed() {
        String xml = service(List.of("my-post")).buildSitemap("https://blog.example.com/");
        assertTrue(xml.contains("<loc>https://blog.example.com/blog/my-post</loc>"), xml);
        assertTrue(xml.contains("<loc>https://blog.example.com/</loc>"), xml);
    }
}
