package com.naqqa.filestorage.entities;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Getter @Setter
@Document(collection = "file_entity")
public class FileEntity {
    @Id
    private Long id;

    @Indexed(unique = true)
    private String fileName; // GCS Object Key

    private String originalFileName;
    private String contentType;
    private Long size;
    private Long ownerId;
    private String status;

    private boolean isLinked = false;

    private LocalDateTime createdAt = LocalDateTime.now();
}
