package aidhkm.dhkm16a1hn.service;

import aidhkm.dhkm16a1hn.model.Document;
import aidhkm.dhkm16a1hn.model.EmbeddingVector;
import aidhkm.dhkm16a1hn.model.Question;
import aidhkm.dhkm16a1hn.model.ChatHistory;
import aidhkm.dhkm16a1hn.repository.DocumentRepository;
import aidhkm.dhkm16a1hn.repository.EmbeddingRepository;
import aidhkm.dhkm16a1hn.repository.QuestionRepository;
import aidhkm.dhkm16a1hn.repository.ChatHistoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.time.LocalDateTime;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@Service
public class TrainingService {
    private static final Logger logger = Logger.getLogger(TrainingService.class.getName());
    private static final int BATCH_SIZE = 10;
    private static final int MAX_SEGMENTS_PER_DOCUMENT = 1000; // Giới hạn số segment tối đa cho mỗi tài liệu

    @Autowired private DocumentRepository documentRepository;
    @Autowired private EmbeddingRepository embeddingRepository;
    @Autowired private QuestionRepository questionRepository;
    @Autowired private ChatHistoryRepository chatHistoryRepository;
    @Autowired private VectorService vectorService;
    @Autowired private VertexAIService vertexAIService;

    @Transactional
    public Document saveDocument(String content, String fileName) {
        try {
            Document document = new Document();
            document.setName(fileName);
            document.setContent(content);
            document.setCreatedAt(LocalDateTime.now());
            document = documentRepository.save(document);

            // Phân đoạn văn bản và tạo vector
            List<String> segments = splitContent(content);
            
            // Giới hạn số lượng segment để tránh quá tải khi file lớn
            if (segments.size() > MAX_SEGMENTS_PER_DOCUMENT) {
                logger.warning("Document '" + fileName + "' has too many segments (" + segments.size() + 
                    "), limiting to " + MAX_SEGMENTS_PER_DOCUMENT);
                segments = segments.subList(0, MAX_SEGMENTS_PER_DOCUMENT);
            }
            
            createVectorsInBatches(document.getId(), segments);

            return document;
        } catch (Exception e) {
            logger.severe("Error saving document: " + e.getMessage());
            throw new RuntimeException("Failed to save document", e);
        }
    }

    private List<String> splitContent(String content) {
        // Sử dụng Vertex AI để phân đoạn văn bản
        String prompt = "Hãy phân đoạn văn bản sau thành các câu ngắn, mỗi câu trên một dòng:\n" + content;
        String response = vertexAIService.generateText(prompt);
        return Arrays.stream(response.split("\n"))
                .filter(s -> !s.trim().isEmpty())
                .collect(Collectors.toList());
    }

    private void createVectorsInBatches(Long documentId, List<String> segments) {
        logger.info("Processing " + segments.size() + " segments for document ID: " + documentId);
        
        for (int i = 0; i < segments.size(); i += BATCH_SIZE) {
            List<String> batch = segments.subList(i, Math.min(i + BATCH_SIZE, segments.size()));
            createVectorsForBatch(documentId, batch);
            
            // Log tiến trình định kỳ
            if ((i / BATCH_SIZE) % 5 == 0) {
                logger.info("Processed " + Math.min(i + BATCH_SIZE, segments.size()) + 
                           " of " + segments.size() + " segments for document ID: " + documentId);
            }
        }
        
        logger.info("Completed processing all " + segments.size() + " segments for document ID: " + documentId);
    }

    private void createVectorsForBatch(Long documentId, List<String> segments) {
        List<EmbeddingVector> batchVectors = new ArrayList<>();
        
        for (String segment : segments) {
            float[] vector = vectorService.createEmbedding(segment);
            EmbeddingVector embeddingVector = new EmbeddingVector();
            embeddingVector.setDocumentId(documentId);
            embeddingVector.setSegment(segment);
            embeddingVector.setVectorData(vector);
            batchVectors.add(embeddingVector);
        }
        
        // Lưu tất cả vector cùng một lúc thay vì từng cái một
        if (!batchVectors.isEmpty()) {
            embeddingRepository.saveAll(batchVectors);
            logger.info("Saved batch of " + batchVectors.size() + " vectors for document ID: " + documentId);
        }
        
        // Vô hiệu hóa cache sau khi thêm các vector mới
        vectorService.invalidateVectorsCache();
        logger.info("Vector cache invalidated after creating batch vectors");
    }

    public List<Document> getAllDocuments() {
        return documentRepository.findAllOrderByCreatedAtDesc();
    }

    @Transactional
    public void deleteDocument(Long documentId) {
        try {
            if (!documentRepository.existsById(documentId)) {
                throw new RuntimeException("Document not found");
            }

            // Xóa các vector embedding
            List<EmbeddingVector> vectors = embeddingRepository.findByDocumentId(documentId);
            embeddingRepository.deleteAll(vectors);
            
            // Vô hiệu hóa cache sau khi xóa các vector
            vectorService.invalidateVectorsCache();
            logger.info("Vector cache invalidated after deleting document vectors");

            // Xóa các câu hỏi
            List<Question> questions = questionRepository.findByDocumentId(documentId);
            questionRepository.deleteAll(questions);

            // Xóa lịch sử chat
            List<ChatHistory> chats = chatHistoryRepository.findByDocumentId(documentId);
            chatHistoryRepository.deleteAll(chats);

            // Xóa tài liệu
            documentRepository.deleteById(documentId);
        } catch (Exception e) {
            logger.severe("Error deleting document: " + e.getMessage());
            throw new RuntimeException("Failed to delete document", e);
        }
    }
}
