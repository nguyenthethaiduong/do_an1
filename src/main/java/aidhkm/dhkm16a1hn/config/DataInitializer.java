package aidhkm.dhkm16a1hn.config;

import aidhkm.dhkm16a1hn.model.Document;
import aidhkm.dhkm16a1hn.model.EmbeddingVector;
import aidhkm.dhkm16a1hn.repository.DocumentRepository;
import aidhkm.dhkm16a1hn.repository.EmbeddingRepository;
import aidhkm.dhkm16a1hn.service.NLPService;
import aidhkm.dhkm16a1hn.service.VectorService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.logging.Logger;

@Component
public class DataInitializer implements ApplicationListener<ContextRefreshedEvent> {

    private static final Logger logger = Logger.getLogger(DataInitializer.class.getName());

    @Autowired
    private DocumentRepository documentRepository;
    
    @Autowired
    private EmbeddingRepository embeddingRepository;
    
    @Autowired
    private NLPService nlpService;
    
    @Autowired
    private VectorService vectorService;

    @PostConstruct
    public void init() {
        try {
            // Kiểm tra xem có tài liệu nào trong database không
            List<Document> existingDocuments = documentRepository.findAll();
            logger.info("Found " + existingDocuments.size() + " documents in database");
            
            // Chỉ xử lý các tài liệu đã có trong database, không tạo mẫu
            if (!existingDocuments.isEmpty()) {
                // Nếu có tài liệu, kiểm tra và tạo vector embedding cho các tài liệu chưa có vector
                for (Document doc : existingDocuments) {
                    List<EmbeddingVector> vectors = embeddingRepository.findByDocumentId(doc.getId());
                    if (vectors.isEmpty()) {
                        // Nếu tài liệu chưa có vector, tạo vector mới
                        logger.info("Creating embedding vectors for document: " + doc.getName() + " (ID: " + doc.getId() + ")");
                        List<String> segments = nlpService.segmentText(doc.getContent());
                        for (String segment : segments) {
                            float[] vector = vectorService.createEmbedding(segment);
                            EmbeddingVector embeddingVector = new EmbeddingVector();
                            embeddingVector.setDocumentId(doc.getId());
                            embeddingVector.setSegment(segment);
                            embeddingVector.setVectorData(vector);
                            embeddingRepository.save(embeddingVector);
                        }
                        logger.info("Created " + segments.size() + " embedding vectors for document: " + doc.getName());
                    } else {
                        logger.info("Document " + doc.getName() + " already has " + vectors.size() + " embedding vectors");
                    }
                }
            }
            
            // Dọn dẹp các vector embedding bị treo
            int cleanedCount = vectorService.cleanupOrphanedEmbeddings();
            if (cleanedCount > 0) {
                logger.info("Cleaned up " + cleanedCount + " orphaned embedding vectors");
            }
            
        } catch (Exception e) {
            logger.severe("Error during data initialization: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        init();
    }

    // Phương thức tạo dữ liệu mẫu đã bị vô hiệu hóa
    /*
    private void createSampleData() {
        // Tạo dữ liệu mẫu về Phở
        Document document = new Document();
        document.setName("Sample Document");
        document.setContent("Phở là một món ăn truyền thống của Việt Nam, cũng có thể xem là món ăn đặc trưng nhất cho ẩm thực Việt Nam. Phở là một món ăn đường phố truyền thống của người dân Việt Nam. Để làm được một món phở ngon thì trước tiên phải chuẩn bị thật tốt ngay từ khâu chọn nguyên liệu. Đầu tiên là chọn bánh phở. Bánh phở ngon phải là loại vừa mềm vừa dai, khi ăn mới có cảm giác ngon, không bị bục hoặc bở. Bánh phở chọn quá nhão cũng làm cho món phở mất ngon. Tiếp đến là công đoạn nấu nước dùng. Nước dùng được làm từ nhiều loại xương như gà, lợn, bò. Nhưng ngon nhất là loại xương được hầm từ xương lợn khiến nước được ngọt và thanh đạm nhất. Xương được rửa sạch và luộc từ 8 – 10 tiếng, sau đó lọc qua rây. Trong quá trình luộc người ta cũng thường xuyên vớt bọt để nước được trong và ngọt hơn. Các gia vị thêm vào nước dùng cần có như bột ngọt, hành lá, mùi tàu làm cho hương vị thêm đậm đà và thơm ngậy. Thành phẩm cho ra là một bát phở đảm bảo nước dùng phải trong và ngọt.");
        document = documentRepository.save(document);
        
        // Tạo vector embedding cho dữ liệu mẫu
        List<String> segments = nlpService.segmentText(document.getContent());
        for (String segment : segments) {
            float[] vector = vectorService.createEmbedding(segment);
            EmbeddingVector embeddingVector = new EmbeddingVector();
            embeddingVector.setDocumentId(document.getId());
            embeddingVector.setSegment(segment);
            embeddingVector.setVectorData(vector);
            embeddingRepository.save(embeddingVector);
        }
        
        logger.info("Created sample data with embedding vectors");
    }
    */
} 