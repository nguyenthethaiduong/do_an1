package aidhkm.dhkm16a1hn.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.Type;
import org.hibernate.usertype.UserType;
import java.time.LocalDateTime;

@Entity
@Data
@Table(name = "embedding_vectors")
public class EmbeddingVector {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "document_id")
    private Long documentId;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", insertable = false, updatable = false)
    private Document document;
    
    @Column(columnDefinition = "TEXT")
    private String segment;
    
    @Column(name = "vector_data", columnDefinition = "float[]")
    private float[] vectorData;
    
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
} 