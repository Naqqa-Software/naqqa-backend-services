package com.naqqa.seofarm.service;

import com.naqqa.seofarm.entity.SeoBlogEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.naqqa.seofarm.model.PagedResponse;
import com.naqqa.seofarm.model.SaveSeoBlogRequest;
import com.naqqa.seofarm.repository.SeoBlogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Blog CRUD over this app's own MongoDB ({@code seo_blogs}) — the source of truth after migrating
 * out of naqqa-server. Powers the admin grid, the public read API, the sitemap, and the generator.
 */
@Service
@RequiredArgsConstructor
public class SeoBlogService {

    private static final java.util.Set<String> SORTABLE =
            java.util.Set.of("createdAt", "publishedAt", "slug", "fromSite");

    private final SeoBlogRepository repo;
    private final MongoTemplate mongo;

    /** Backwards-compatible overload (site filter only). */
    public Map<String, Object> list(String fromSite, int page, int size, String sortBy, String sortDir) {
        return list(fromSite, null, null, page, size, sortBy, sortDir);
    }

    /** Paged list filtered by site / slug (contains) / keyword (in title, description or keywords). */
    public Map<String, Object> list(String fromSite, String slug, String keyword,
                                    int page, int size, String sortBy, String sortDir) {
        String key = SORTABLE.contains(sortBy) ? sortBy : "createdAt";
        Sort.Direction dir = "asc".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 100);

        List<Criteria> parts = new ArrayList<>();
        if (fromSite != null && !fromSite.isBlank()) {
            parts.add(Criteria.where("fromSite").is(fromSite.trim()));
        }
        if (slug != null && !slug.isBlank()) {
            parts.add(Criteria.where("slug").regex(Pattern.quote(slug.trim()), "i"));
        }
        if (keyword != null && !keyword.isBlank()) {
            String rx = Pattern.quote(keyword.trim());
            parts.add(new Criteria().orOperator(
                    Criteria.where("meta.keywords").regex(rx, "i"),
                    Criteria.where("meta.tags").regex(rx, "i"),
                    Criteria.where("meta.title").regex(rx, "i"),
                    Criteria.where("meta.description").regex(rx, "i")));
        }
        Criteria criteria = parts.isEmpty() ? new Criteria() : new Criteria().andOperator(parts.toArray(new Criteria[0]));

        long total = mongo.count(new Query(criteria), SeoBlogEntity.class);
        Query query = new Query(criteria).with(Sort.by(dir, key)).with(PageRequest.of(safePage, safeSize));
        List<SeoBlogEntity> content = mongo.find(query, SeoBlogEntity.class);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", content);
        out.put("totalItems", total);
        out.put("totalPages", (int) Math.ceil(total / (double) safeSize));
        out.put("currentPage", safePage);
        out.put("pageSize", safeSize);
        return out;
    }

    /** Public paged list (naqqa-server-compatible envelope) — what the SEO site consumes. */
    public PagedResponse<SeoBlogEntity> getAll(String fromSite, int page, int size, String sortBy, String sortDir) {
        String key = SORTABLE.contains(sortBy) ? sortBy : "createdAt";
        Sort sort = "asc".equalsIgnoreCase(sortDir) ? Sort.by(key).ascending() : Sort.by(key).descending();
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100), sort);
        Page<SeoBlogEntity> result = (fromSite != null && !fromSite.isBlank())
                ? repo.findByFromSite(fromSite.trim(), pageable)
                : repo.findAll(pageable);
        return PagedResponse.<SeoBlogEntity>builder()
                .content(result.getContent())
                .pagination(PagedResponse.Pagination.builder()
                        .pageNumber(result.getNumber())
                        .pageSize(result.getSize())
                        .totalElements(result.getTotalElements())
                        .totalPages(result.getTotalPages())
                        .last(result.isLast())
                        .build())
                .build();
    }

    public Optional<SeoBlogEntity> findById(String id) {
        return repo.findById(id);
    }

    public Optional<SeoBlogEntity> findBySlug(String slug) {
        return repo.findBySlug(slug);
    }

    public SeoBlogEntity create(SaveSeoBlogRequest req) {
        if (req.slug() == null || req.slug().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "slug is required");
        }
        if (repo.existsBySlug(req.slug())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A blog with slug '" + req.slug() + "' already exists");
        }
        SeoBlogEntity e = new SeoBlogEntity();
        apply(e, req);
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        if (e.getPublishedAt() == null) {
            e.setPublishedAt(Instant.now());
        }
        return repo.save(e);
    }

    public SeoBlogEntity update(String id, SaveSeoBlogRequest req) {
        SeoBlogEntity e = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Blog not found: " + id));
        // Slug uniqueness (allow keeping its own slug).
        if (req.slug() != null && !req.slug().equals(e.getSlug()) && repo.existsBySlug(req.slug())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A blog with slug '" + req.slug() + "' already exists");
        }
        apply(e, req);
        e.setUpdatedAt(Instant.now());
        return repo.save(e);
    }

    public void delete(String id) {
        repo.deleteById(id);
    }

    private void apply(SeoBlogEntity e, SaveSeoBlogRequest req) {
        if (req.slug() != null) e.setSlug(req.slug());
        if (req.fromSite() != null) e.setFromSite(req.fromSite());
        if (req.content() != null) e.setContent(req.content());
        if (req.meta() != null) e.setMeta(req.meta());
        if (req.images() != null) e.setImages(req.images());
        if (req.fullHtml() != null) e.setFullHtml(req.fullHtml());
        if (req.publishedAt() != null) e.setPublishedAt(req.publishedAt());
    }

    public List<String> slugsByFromSite(String fromSite) {
        return repo.findByFromSiteOrderByCreatedAtDesc(fromSite).stream()
                .map(SeoBlogEntity::getSlug).toList();
    }

    public long countByFromSite(String fromSite) {
        return repo.countByFromSite(fromSite);
    }

    /** True if a blog for this site actually exists since {@code from} (today). Deleting it makes this false. */
    public boolean hasBlogSince(String fromSite, Instant from) {
        return repo.existsByFromSiteAndCreatedAtGreaterThanEqual(fromSite, from);
    }

    public boolean existsBySlug(String slug) {
        return repo.existsBySlug(slug);
    }

    /** Direct save used by the generator (already-built entity). */
    public SeoBlogEntity save(SeoBlogEntity e) {
        return repo.save(e);
    }
}
