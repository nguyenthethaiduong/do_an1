package aidhkm.dhkm16a1hn.repository;

import aidhkm.dhkm16a1hn.model.Question;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface QuestionRepository extends JpaRepository<Question, Long> {
    
    /**
     * Tìm câu hỏi theo văn bản chứa từ khóa, không phân biệt chữ hoa/thường
     * @param questionText Văn bản cần tìm
     * @return Danh sách câu hỏi phù hợp
     */
    List<Question> findByQuestionTextContainingIgnoreCase(String questionText);
    
    /**
     * Lấy 10 câu hỏi mới nhất theo thời gian tạo
     * @return Danh sách 10 câu hỏi mới nhất
     */
    List<Question> findTop10ByOrderByCreatedAtDesc();
    
    /**
     * Tìm câu hỏi theo ID của tài liệu
     * @param documentId ID của tài liệu
     * @return Danh sách câu hỏi liên quan đến tài liệu
     */
    List<Question> findByDocumentId(Long documentId);
} 