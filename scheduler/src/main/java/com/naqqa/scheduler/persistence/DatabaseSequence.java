package com.naqqa.scheduler.persistence;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Backing document for MongoDB-based auto-increment Long ids.
 * Replaces JPA's {@code @GeneratedValue(strategy = IDENTITY)}.
 */
@Getter
@Setter
@Document(collection = "database_sequences")
public class DatabaseSequence {

    @Id
    private String id;

    private long seq;
}
