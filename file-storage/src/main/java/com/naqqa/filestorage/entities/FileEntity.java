package com.naqqa.filestorage.entities;

import jakarta.persistence.*;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Data
@Table(name = "file_entity")
public class FileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_name", nullable = false)
    private String fileName; // Stored name in GCS

    @Column(name = "original_file_name", nullable = false)
    private String originalFileName; // Original uploaded file name

    @Column(name = "file_url", nullable = false)
    private String fileUrl; // Public URL or signed URL

    @Column(name = "content_type")
    private String contentType; // MIME type (image/jpeg, application/pdf, etc.)

    @Column(name = "size_bytes")
    private Long size; // File size in bytes
}
