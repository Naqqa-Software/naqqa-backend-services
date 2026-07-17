package com.naqqa.outreach.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight mapping over the shared {@code it_companies} collection (the outreach lead source).
 * The full document is owned by naqqa-os-be; this view exposes only what the engine needs and
 * lets it advance {@code status} + attach {@code emails}. Status flow: DEFAULT → ENRICHED → USED
 * (contacted) / BLOCKED.
 */
@Document("it_companies")
@Getter
@Setter
public class LeadEntity {

    @Id
    private String id;

    private String name;
    private String website;
    private String industry;
    private String size;
    private String city;
    private String state;
    private String countryCode;

    private List<String> emails = new ArrayList<>();

    /** Company context scraped from the website (persisted for the edit drawer + AI generation). */
    private String websiteInfo;

    /** DEFAULT | ENRICHED | USED | BLOCKED. */
    private String status;
}
