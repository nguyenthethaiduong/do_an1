package aidhkm.dhkm16a1hn.repository;

import aidhkm.dhkm16a1hn.model.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

@Repository
public interface DocumentRepository extends JpaRepository<Document, Long> {
    // Tìm kiếm tài liệu theo nội dung đã xử lý
    List<Document> findByProcessedContentContainingIgnoreCase(String keyword);

    Document findByName(String name);
    
    @Query("SELECT d FROM Document d ORDER BY d.createdAt DESC")
    List<Document> findAllOrderByCreatedAtDesc();
}
