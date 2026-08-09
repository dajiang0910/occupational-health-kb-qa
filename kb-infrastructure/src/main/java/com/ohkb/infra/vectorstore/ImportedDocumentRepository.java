package com.ohkb.infra.vectorstore;

import com.ohkb.core.knowledge.ImportedDocument;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 导入文档 Repository。
 */
@Repository
public interface ImportedDocumentRepository extends CrudRepository<ImportedDocument, Long> {

    List<ImportedDocument> findByStatus(String status);

    @Query("SELECT * FROM imported_documents ORDER BY created_at DESC LIMIT :limit")
    List<ImportedDocument> findRecent(@Param("limit") int limit);

    @Modifying
    @Query("UPDATE imported_documents SET status = :status, updated_at = NOW() WHERE id = :id")
    void updateStatus(@Param("id") Long id, @Param("status") String status);

    @Modifying
    @Query("""
        UPDATE imported_documents
        SET status = :status, chunk_count = :chunkCount,
            failed_chunk_count = :failedChunkCount, updated_at = NOW()
        WHERE id = :id
    """)
    void updateProcessingResult(@Param("id") Long id, @Param("status") String status,
                                @Param("chunkCount") int chunkCount,
                                @Param("failedChunkCount") int failedChunkCount);

    @Modifying
    @Query("""
        UPDATE imported_documents
        SET status = 'failed', error_message = :errorMessage,
            error_details = :errorDetails, updated_at = NOW()
        WHERE id = :id
    """)
    void updateError(@Param("id") Long id, @Param("errorMessage") String errorMessage,
                     @Param("errorDetails") String errorDetails);

    @Query("SELECT COUNT(*) FROM imported_documents WHERE status = :status")
    long countByStatus(@Param("status") String status);
}
