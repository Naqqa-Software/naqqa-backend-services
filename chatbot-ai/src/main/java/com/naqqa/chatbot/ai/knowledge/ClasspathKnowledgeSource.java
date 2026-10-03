package com.naqqa.chatbot.ai.knowledge;

import com.naqqa.chatbot.spi.ChatKnowledgeSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class ClasspathKnowledgeSource implements ChatKnowledgeSource {

    private final String location;
    private final PathMatchingResourcePatternResolver resolver;

    public ClasspathKnowledgeSource(String location) {
        this(location, ClasspathKnowledgeSource.class.getClassLoader());
    }

    public ClasspathKnowledgeSource(String location, ClassLoader classLoader) {
        String l = location == null || location.isBlank() ? "naqqa-chatbot/knowledge" : location.trim();
        while (l.endsWith("/")) {
            l = l.substring(0, l.length() - 1);
        }
        this.location = l;
        this.resolver = new PathMatchingResourcePatternResolver(classLoader);
    }

    @Override
    public List<KnowledgeDocument> documents(List<String> languages) {
        List<KnowledgeDocument> out = new ArrayList<>();
        for (String lang : languages) {
            Resource[] resources;
            try {
                resources = resolver.getResources("classpath*:" + location + "/" + lang + "/*.md");
            } catch (Exception e) {
                continue;
            }
            for (Resource resource : resources) {
                String name = resource.getFilename();
                if (name == null) {
                    continue;
                }
                String key = name.replaceFirst("\\.md$", "");
                try (InputStream in = resource.getInputStream()) {
                    String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    KnowledgeChunker.CuratedDoc doc = KnowledgeChunker.parseCurated(content, key);
                    out.add(new KnowledgeDocument(doc.type(), key, lang, doc.title(), doc.path(), doc.sections(), false));
                } catch (Exception e) {
                    log.warn("[chatbot] cannot read knowledge file {}: {}", name, e.getMessage());
                }
            }
        }
        return out;
    }
}
