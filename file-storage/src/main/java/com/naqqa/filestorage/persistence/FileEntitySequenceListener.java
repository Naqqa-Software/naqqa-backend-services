package com.naqqa.filestorage.persistence;

import com.naqqa.filestorage.entities.FileEntity;
import org.springframework.data.mongodb.core.mapping.event.AbstractMongoEventListener;
import org.springframework.data.mongodb.core.mapping.event.BeforeConvertEvent;

/**
 * Assigns an auto-increment Long id to {@link FileEntity} documents before they are persisted,
 * preserving the JPA identity-generation semantics after the MongoDB migration.
 */
public class FileEntitySequenceListener extends AbstractMongoEventListener<FileEntity> {

    public static final String SEQ_NAME = "file_entity_seq";

    private final SequenceGenerator sequenceGenerator;

    public FileEntitySequenceListener(SequenceGenerator sequenceGenerator) {
        this.sequenceGenerator = sequenceGenerator;
    }

    @Override
    public void onBeforeConvert(BeforeConvertEvent<FileEntity> event) {
        FileEntity entity = event.getSource();
        if (entity.getId() == null) {
            entity.setId(sequenceGenerator.generateSequence(SEQ_NAME));
        }
    }
}
