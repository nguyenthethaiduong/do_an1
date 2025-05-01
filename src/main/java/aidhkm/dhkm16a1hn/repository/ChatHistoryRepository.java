package aidhkm.dhkm16a1hn.repository;


import aidhkm.dhkm16a1hn.model.ChatHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatHistoryRepository extends JpaRepository<ChatHistory, Long> {
    List<ChatHistory> question(String question);
    List<ChatHistory> findByDocumentId(Long documentId);
}
