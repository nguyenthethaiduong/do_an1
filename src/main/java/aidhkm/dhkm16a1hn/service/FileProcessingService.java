package aidhkm.dhkm16a1hn.service;

import aidhkm.dhkm16a1hn.model.Document;
import aidhkm.dhkm16a1hn.repository.DocumentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.logging.Logger;

/**
 * Service dùng chung cho việc xử lý nội dung file
 * Được sử dụng bởi cả TextService và PDFService
 */
@Service
public class FileProcessingService {
    private static final Logger logger = Logger.getLogger(FileProcessingService.class.getName());
    private static final int MAX_SEGMENTS_PER_DOCUMENT = 1000; // Giới hạn số segment tối đa cho mỗi tài liệu

    @Autowired private DocumentRepository documentRepository;
    @Autowired private VectorService vectorService;
    @Autowired private NLPService nlpService;

    /**
     * Xử lý nội dung văn bản và tạo embedding
     * @param content Nội dung văn bản
     * @param documentName Tên tài liệu
     * @return Số lượng đoạn văn bản đã xử lý
     */
    public int processTextContent(String content, String documentName) {
        try {
            // Phân đoạn văn bản
            List<String> segments = nlpService.segmentText(content);
            logger.info("Split text into " + segments.size() + " segments");
            
            if (segments.isEmpty()) {
                return 0;
            }
            
            // Giới hạn số lượng segment để tránh quá tải khi file lớn
            if (segments.size() > MAX_SEGMENTS_PER_DOCUMENT) {
                logger.warning("Document has too many segments (" + segments.size() + 
                    "), limiting to " + MAX_SEGMENTS_PER_DOCUMENT);
                segments = segments.subList(0, MAX_SEGMENTS_PER_DOCUMENT);
            }
            
            int segmentCount = 0;
            
            // Lưu document vào database trước
            Document document = new Document();
            document.setName(documentName);
            document.setContent(content);
            documentRepository.save(document);
            Long docId = document.getId();
            
            logger.info("Saved document to database with ID: " + docId);
            
            // Tạo danh sách embedding vectors để lưu hàng loạt
            List<aidhkm.dhkm16a1hn.model.EmbeddingVector> batchVectors = new java.util.ArrayList<>();
            
            // Tạo embedding cho từng đoạn
            for (String segment : segments) {
                if (segment.trim().isEmpty()) {
                    continue;
                }
                
                // Tạo embedding vector
                float[] embedding = vectorService.createEmbedding(segment);
                if (embedding != null) {
                    // Chuẩn bị entity để lưu batch
                    aidhkm.dhkm16a1hn.model.EmbeddingVector embeddingVector = new aidhkm.dhkm16a1hn.model.EmbeddingVector();
                    embeddingVector.setDocumentId(docId);
                    embeddingVector.setSegment(segment);
                    embeddingVector.setVectorData(embedding);
                    batchVectors.add(embeddingVector);
                    segmentCount++;
                }
            }
            
            // Lưu tất cả vector cùng một lúc
            if (!batchVectors.isEmpty()) {
                vectorService.saveEmbeddingVectors(batchVectors);
                vectorService.invalidateVectorsCache(); // 👈 Thêm dòng này
                logger.info("Saved " + batchVectors.size() + " vectors for document ID: " + docId);
            }

            logger.info("Successfully processed content. Created " + segmentCount + " embeddings");
            return segmentCount;
            
        } catch (Exception e) {
            logger.severe("Error processing content: " + e.getMessage());
            e.printStackTrace();
            return 0;
        }
    }
} 