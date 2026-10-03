package com.naqqa.chatbot.i18n;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class ChatResources {

    public static final String ROOT = "naqqa-chatbot/";
    private static final String MARKER = "library.properties";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PathMatchingResourcePatternResolver resolver;
    private final String libraryLocation;

    public ChatResources(ClassLoader classLoader) {
        this.resolver = new PathMatchingResourcePatternResolver(classLoader == null ? ChatResources.class.getClassLoader() : classLoader);
        this.libraryLocation = libraryLocation();
    }

    public static ChatResources defaults() {
        return new ChatResources(ChatResources.class.getClassLoader());
    }

    public List<Resource> layered(String path) {
        List<Resource> library = new ArrayList<>();
        List<Resource> project = new ArrayList<>();
        try {
            for (Resource resource : resolver.getResources("classpath*:" + path)) {
                if (!resource.exists()) {
                    continue;
                }
                if (isLibrary(resource)) {
                    library.add(resource);
                } else {
                    project.add(resource);
                }
            }
        } catch (Exception ignored) {
        }
        List<Resource> out = new ArrayList<>(library);
        out.addAll(project);
        return out;
    }

    public JsonNode json(String path) {
        ObjectNode merged = MAPPER.createObjectNode();
        for (Resource resource : layered(path)) {
            try (InputStream in = resource.getInputStream()) {
                JsonNode node = MAPPER.readTree(in);
                if (node != null && node.isObject()) {
                    merge(merged, (ObjectNode) node);
                }
            } catch (Exception e) {
                throw new IllegalStateException("Invalid chatbot resource " + resource.getDescription() + ": " + e.getMessage(), e);
            }
        }
        return merged;
    }

    public String text(String path) {
        String value = null;
        for (Resource resource : layered(path)) {
            try (InputStream in = resource.getInputStream()) {
                value = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }
        }
        return value;
    }

    public List<Resource> all(String pattern) {
        try {
            return List.of(resolver.getResources("classpath*:" + pattern));
        } catch (Exception e) {
            return List.of();
        }
    }

    public static void merge(ObjectNode target, ObjectNode source) {
        Iterator<Map.Entry<String, JsonNode>> fields = source.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> e = fields.next();
            JsonNode existing = target.get(e.getKey());
            if (existing != null && existing.isObject() && e.getValue().isObject()) {
                merge((ObjectNode) existing, (ObjectNode) e.getValue());
            } else {
                target.set(e.getKey(), e.getValue().deepCopy());
            }
        }
    }

    private boolean isLibrary(Resource resource) {
        if (libraryLocation == null) {
            return false;
        }
        try {
            return resource.getURL().toString().startsWith(libraryLocation);
        } catch (Exception e) {
            return false;
        }
    }

    private String libraryLocation() {
        try {
            URL marker = ChatResources.class.getResource("/" + ROOT + MARKER);
            if (marker == null) {
                return null;
            }
            String value = marker.toString();
            return value.substring(0, value.length() - (ROOT + MARKER).length());
        } catch (Exception e) {
            return null;
        }
    }
}
