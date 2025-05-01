package aidhkm.dhkm16a1hn.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "questions")
@Data
public class Question {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "question_text", length = 1000)
    private String questionText;
    
    @Column(name = "answer_text", columnDefinition = "TEXT")
    private String answerText;
    
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    @Column(name = "document_id")
    private Long documentId;
    
    public Question() {
    }
    
    public Question(String questionText, String answerText) {
        this.questionText = questionText;
        this.answerText = answerText;
        this.createdAt = LocalDateTime.now();
    }
} 