package aidhkm.dhkm16a1hn.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "chat_history")
@Data
public class ChatHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(columnDefinition = "TEXT")
    private String question;
    
    @Column(columnDefinition = "TEXT")
    private String answer;
    
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    @Column(name = "document_id")
    private Long documentId;
    
    public ChatHistory() {
    }
    
    public ChatHistory(String question, String answer) {
        this.question = question;
        this.answer = answer;
        this.createdAt = LocalDateTime.now();
    }
}
