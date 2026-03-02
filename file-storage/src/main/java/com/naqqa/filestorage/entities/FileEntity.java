package com.naqqa.filestorage.entities;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "file_entity")
public class FileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_name", nullable = false)
    private String fileName; // Object key in GCS (e.g., public/categories/123.svg)

    @Column(name = "original_file_name", nullable = false)
    private String originalFileName; // Original uploaded file name

    @Column(name = "content_type")
    private String contentType; // MIME type

    @Column(name = "size_bytes")
    private Long size; // File size in bytes

    @Column(name = "owner_id")
    private Long ownerId;
}
