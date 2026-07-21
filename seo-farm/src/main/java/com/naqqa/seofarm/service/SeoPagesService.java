package com.naqqa.seofarm.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.naqqa.seofarm.entity.SeoSiteStateEntity;
import com.naqqa.seofarm.model.SeoSite;
import com.naqqa.seofarm.repository.SeoSiteStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serves the static "SEO pages" content bundled from the bot's data files (SEO checklist, services,
 * internal site links, topics) plus the hard-coded target-site registry (sites.json). Site
 * definitions are fixed at seed; only the {@code active} toggle is editable (persisted per-site).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SeoPagesService {

    private final SeoSiteStateRepository siteState;
    private final ObjectMapper mapper = new ObjectMapper();

    /** All SEO reference pages keyed by name (seoChecklist, services, siteLinks, topics). */
    public Map<String, Object> pages() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("seoChecklist", readJson("seofarm/pages/seo-checklist.json"));
        out.put("services", readJson("seofarm/pages/services.json"));
        out.put("siteLinks", readJson("seofarm/pages/site-links.json"));
        out.put("topics", readJson("seofarm/pages/topics.json"));
        return out;
    }

    /** ACTIVE target sites only — what the UI shows and what generation runs for. */
    public List<SeoSite> sites() {
        return allSites().stream().filter(SeoSite::active).toList();
    }

    /** Every hard-coded site, with the {@code active} flag overridden by the persisted per-site state. */
    public List<SeoSite> allSites() {
        try {
            JsonNode arr = readJson("seofarm/sites.json");
            List<SeoSite> base = mapper.convertValue(arr, mapper.getTypeFactory()
                    .constructCollectionType(List.class, SeoSite.class));
            Map<String, Boolean> overrides = new LinkedHashMap<>();
            siteState.findAll().forEach(s -> overrides.put(s.getSiteId(), s.isActive()));
            return base.stream().map(s -> overrides.containsKey(s.id()) ? withActive(s, overrides.get(s.id())) : s).toList();
        } catch (Exception e) {
            log.warn("[seofarm] failed to load sites.json: {}", e.getMessage());
            return List.of();
        }
    }

    /** Resolve an ACTIVE site by id (inactive → null, so generation is refused). */
    public SeoSite site(String id) {
        return sites().stream().filter(s -> s.id().equalsIgnoreCase(id)).findFirst().orElse(null);
    }

    /** Toggle a site's active flag (persisted). Returns the updated site, or null if the id is unknown. */
    public SeoSite setActive(String id, boolean active) {
        SeoSite base = allSites().stream().filter(s -> s.id().equalsIgnoreCase(id)).findFirst().orElse(null);
        if (base == null) {
            return null;
        }
        SeoSiteStateEntity state = siteState.findById(id).orElseGet(() -> {
            SeoSiteStateEntity e = new SeoSiteStateEntity();
            e.setSiteId(id);
            return e;
        });
        state.setActive(active);
        siteState.save(state);
        return withActive(base, active);
    }

    private SeoSite withActive(SeoSite s, boolean active) {
        return new SeoSite(s.id(), s.name(), active, s.domain(), s.fromSite(), s.locationCode(),
                s.languageCode(), s.languageName(), s.targetAudience(), s.competitivePosition(), s.rootTerms());
    }

    private JsonNode readJson(String classpath) {
        try {
            return mapper.readTree(new ClassPathResource(classpath).getInputStream());
        } catch (Exception e) {
            log.warn("[seofarm] failed to read {}: {}", classpath, e.getMessage());
            return mapper.createObjectNode();
        }
    }
}
