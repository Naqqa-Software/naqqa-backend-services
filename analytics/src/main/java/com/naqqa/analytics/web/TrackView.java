package com.naqqa.analytics.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Zero-code analytics: annotate any controller method and a page view is recorded automatically
 * (after a successful 2xx response). The interceptor resolves the entity id and property from the
 * request, so integrating a new project/entity is just one annotation.
 *
 * <pre>{@code
 *   @GetMapping("/api/courses/{id}")
 *   @TrackView(entityType = "course", entityIdParam = "id", propertyParam = "site")
 *   public Course get(@PathVariable String id, @RequestParam String site) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TrackView {

    /** Entity kind, e.g. "blog", "course", "page". */
    String entityType() default "page";

    /** Literal property/site. If blank, {@link #propertyParam} is used, else the request host. */
    String property() default "";

    /** Name of a path variable or query param holding the property/site. */
    String propertyParam() default "";

    /** Name of a path variable or query param holding the entity id. Blank = use the request path. */
    String entityIdParam() default "";
}
