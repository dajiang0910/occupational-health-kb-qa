package com.ohkb.core.knowledge;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * 导入文档领域实体。
 */
@Table("imported_documents")
public record ImportedDocument(
        @Id Long id,
        String filename,
        String fileType,
        Long originalSize,
        String status,
        Integer chunkCount,
        Integer failedChunkCount,
        String errorDetails,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt
) {
    public static ImportedDocument create(String filename, String fileType, long size) {
        return new ImportedDocument(null, filename, fileType, size,
                "uploading", 0, 0, null, null, Instant.now(), Instant.now());
    }

    public ImportedDocument withStatus(String status) {
        return new ImportedDocument(id, filename, fileType, originalSize, status,
                chunkCount, failedChunkCount, errorDetails, errorMessage, createdAt, Instant.now());
    }

    public ImportedDocument withChunkCounts(int total, int failed) {
        return new ImportedDocument(id, filename, fileType, originalSize, status,
                total, failed, errorDetails, errorMessage, createdAt, Instant.now());
    }

    public ImportedDocument withError(String errorMessage, String errorDetails) {
        return new ImportedDocument(id, filename, fileType, originalSize, "failed",
                chunkCount, failedChunkCount, errorDetails, errorMessage, createdAt, Instant.now());
    }
}
