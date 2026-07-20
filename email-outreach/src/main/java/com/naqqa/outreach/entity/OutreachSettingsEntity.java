package com.naqqa.outreach.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Global outreach runtime switches, toggled from the admin panel (singleton document, id
 * {@link #SINGLETON}). Separate from {@link OutreachProperties} (deploy-time) and profiles
 * (per-sender): {@code sendingActive} lets the admin arm/disarm all sending at will, while
 * extraction keeps filling the enriched pool regardless.
 */
@Document("outreach_settings")
@Getter
@Setter
public class OutreachSettingsEntity {

    public static final String SINGLETON = "singleton";

    @Id
    private String id = SINGLETON;

    /** When false, no emails are sent (initial or follow-up) even at the 9am kickoff. */
    private boolean sendingActive = false;

    /** When false, the Apollo extraction loop is paused (stops the "infinite" enrichment). */
    private boolean extractionActive = true;

    /** Auto-pause: Apollo enrichment is suspended until this time (set when credits run out). Null = not paused. */
    private Instant apolloPausedUntil;

    private Instant updatedAt;
}
