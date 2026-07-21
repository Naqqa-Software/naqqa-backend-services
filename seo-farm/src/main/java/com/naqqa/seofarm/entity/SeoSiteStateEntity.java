package com.naqqa.seofarm.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The only editable per-site setting: the {@code active} toggle. Site definitions are hard-coded at
 * seed (sites.json); this just overrides whether each is active. Keyed by the site id.
 */
@Document("seo_site_state")
@Getter
@Setter
public class SeoSiteStateEntity {

    @Id
    private String siteId;
    private boolean active;
}
