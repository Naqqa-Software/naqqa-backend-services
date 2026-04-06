package com.naqqa.filestorage.entities;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter @Setter @Entity @Table(name = "file_entity")
public class FileEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String fileName; // GCS Object Key

    private String originalFileName;
    private String contentType;
    private Long size;
    private Long ownerId;
    private String status;

    @Column(nullable = false)
    private boolean isLinked = false;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
