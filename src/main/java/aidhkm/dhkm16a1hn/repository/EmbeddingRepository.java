package aidhkm.dhkm16a1hn.repository;

import aidhkm.dhkm16a1hn.model.EmbeddingVector;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface EmbeddingRepository extends JpaRepository<EmbeddingVector, Long> {
    List<EmbeddingVector> findByDocumentId(Long documentId);
    
    List<EmbeddingVector> findBySegment(String segment);
    
    @Modifying
    @Transactional
    @Query("DELETE FROM EmbeddingVector e WHERE e.documentId = :documentId")
    void deleteByDocumentId(@Param("documentId") Long documentId);
    
    // Phương thức tìm kiếm vector gần nhất (cần cấu hình pgvector)
    // @Query(value = "SELECT * FROM embedding_vectors ORDER BY vector_data <-> :vector LIMIT :n", nativeQuery = true)
    // List<EmbeddingVector> findNearestVectors(@Param("vector") float[] vector, @Param("n") int n);
} 