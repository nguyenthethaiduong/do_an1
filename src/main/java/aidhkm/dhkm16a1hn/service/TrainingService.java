package aidhkm.dhkm16a1hn.service; // Khai báo package chứa lớp dịch vụ

import aidhkm.dhkm16a1hn.model.Document; // Import model Document để làm việc với dữ liệu tài liệu
import aidhkm.dhkm16a1hn.model.EmbeddingVector; // Import model EmbeddingVector để làm việc với vector nhúng
import aidhkm.dhkm16a1hn.model.Question; // Import model Question để làm việc với dữ liệu câu hỏi
import aidhkm.dhkm16a1hn.model.ChatHistory; // Import model ChatHistory để làm việc với lịch sử trò chuyện
import aidhkm.dhkm16a1hn.repository.DocumentRepository; // Import repository để thao tác với cơ sở dữ liệu tài liệu
import aidhkm.dhkm16a1hn.repository.EmbeddingRepository; // Import repository để thao tác với cơ sở dữ liệu vector nhúng
import aidhkm.dhkm16a1hn.repository.QuestionRepository; // Import repository để thao tác với cơ sở dữ liệu câu hỏi
import aidhkm.dhkm16a1hn.repository.ChatHistoryRepository; // Import repository để thao tác với cơ sở dữ liệu lịch sử trò chuyện
import org.springframework.beans.factory.annotation.Autowired; // Import annotation để tiêm phụ thuộc tự động
import org.springframework.stereotype.Service; // Import annotation để đánh dấu lớp là một dịch vụ
import org.springframework.transaction.annotation.Transactional; // Import annotation để quản lý giao dịch

import java.util.*; // Import các lớp tiện ích của Java
import java.time.LocalDateTime; // Import lớp để làm việc với ngày giờ
import java.util.logging.Logger; // Import Logger để ghi log
import java.util.stream.Collectors; // Import để làm việc với luồng dữ liệu

/**
 * Dịch vụ huấn luyện và quản lý dữ liệu
 * Cung cấp các chức năng để lưu trữ, xử lý và quản lý tài liệu,
 * tạo vector nhúng cho văn bản và quản lý dữ liệu liên quan
 */
@Service // Đánh dấu lớp này là một dịch vụ Spring để Spring container quản lý
public class TrainingService { // Khai báo lớp dịch vụ huấn luyện
    private static final Logger logger = Logger.getLogger(TrainingService.class.getName()); // Khởi tạo Logger để ghi log hoạt động của lớp
    private static final int BATCH_SIZE = 10; // Kích thước batch khi xử lý vector nhúng, giới hạn số lượng vector xử lý cùng lúc
    private static final int MAX_SEGMENTS_PER_DOCUMENT = 1000; // Giới hạn số segment tối đa cho mỗi tài liệu để tránh quá tải hệ thống

    @Autowired private DocumentRepository documentRepository; // Repository để truy vấn và lưu trữ tài liệu
    @Autowired private EmbeddingRepository embeddingRepository; // Repository để truy vấn và lưu trữ vector nhúng
    @Autowired private QuestionRepository questionRepository; // Repository để truy vấn và lưu trữ câu hỏi
    @Autowired private ChatHistoryRepository chatHistoryRepository; // Repository để truy vấn và lưu trữ lịch sử trò chuyện
    @Autowired private VectorService vectorService; // Dịch vụ xử lý vector nhúng
    @Autowired private VertexAIService vertexAIService; // Dịch vụ tương tác với Vertex AI để tạo văn bản

    /**
     * Lưu tài liệu mới và tạo vector nhúng cho nội dung của tài liệu
     * Phương thức này thực hiện các bước:
     * 1. Tạo và lưu thông tin tài liệu vào cơ sở dữ liệu
     * 2. Phân tách nội dung thành các đoạn văn bản nhỏ hơn
     * 3. Kiểm tra và giới hạn số lượng đoạn để tránh quá tải
     * 4. Tạo vector nhúng cho các đoạn văn bản theo lô
     * 
     * @param name Tên tài liệu 
     * @param content Nội dung văn bản của tài liệu
     * @return Đối tượng Document đã được lưu với ID từ cơ sở dữ liệu
     * @throws RuntimeException Nếu có lỗi trong quá trình lưu tài liệu
     */
    @Transactional // Đảm bảo tính toàn vẹn của giao dịch, nếu có lỗi sẽ rollback toàn bộ
    public Document saveDocument(String name, String content) { // Phương thức lưu tài liệu và tạo vector nhúng
        try {
            logger.info("Starting to save document: " + name + " (content length: " + content.length() + " characters)");
            
            // Tạo đối tượng Document mới
            Document document = new Document(); // Khởi tạo đối tượng Document mới
            document.setName(name); // Đặt tên tài liệu
            document.setContent(content); // Đặt nội dung tài liệu
            document.setCreatedAt(LocalDateTime.now()); // Đặt thời gian tạo là thời điểm hiện tại
            
            logger.info("Saving document to database: " + name);
            document = documentRepository.save(document); // Lưu tài liệu vào cơ sở dữ liệu và cập nhật đối tượng với ID đã tạo
            logger.info("Document saved with ID: " + document.getId());

            // Phân đoạn văn bản và tạo vector
            logger.info("Starting content splitting for document: " + name);
            List<String> segments;
            try {
                segments = splitContent(content); // Phân tách nội dung thành các đoạn nhỏ hơn
                logger.info("Content split into " + segments.size() + " segments");
            } catch (Exception e) {
                logger.severe("Error splitting content: " + e.getMessage());
                e.printStackTrace();
                throw new RuntimeException("Failed to split document content", e);
            }
            
            // Giới hạn số lượng segment để tránh quá tải khi file lớn
            if (segments.size() > MAX_SEGMENTS_PER_DOCUMENT) { // Kiểm tra nếu số đoạn vượt quá giới hạn
                logger.warning("Document '" + name + "' has too many segments (" + segments.size() + 
                    "), limiting to " + MAX_SEGMENTS_PER_DOCUMENT); // Ghi log cảnh báo
                segments = segments.subList(0, MAX_SEGMENTS_PER_DOCUMENT); // Cắt bớt danh sách chỉ giữ lại số đoạn trong giới hạn
            }
            
            // Tạo vector nhúng cho các đoạn văn bản theo lô
            logger.info("Starting to create vectors for document: " + name + " (ID: " + document.getId() + ")");
            try {
                createVectorsInBatches(document.getId(), segments); // Gọi phương thức tạo vector theo lô với ID tài liệu và danh sách đoạn
                logger.info("Vectors created successfully for document: " + name);
            } catch (Exception e) {
                logger.severe("Error creating vectors: " + e.getMessage());
                e.printStackTrace();
                throw new RuntimeException("Failed to create vectors for document content", e);
            }

            return document; // Trả về đối tượng tài liệu đã được lưu với ID
        } catch (Exception e) { // Bắt tất cả các ngoại lệ có thể xảy ra
            logger.severe("Error saving document " + name + ": " + e.getMessage()); // Ghi log lỗi nghiêm trọng
            e.printStackTrace();
            throw new RuntimeException("Failed to save document", e); // Ném ngoại lệ RuntimeException với thông báo lỗi
        }
    }

    /**
     * Phân tách nội dung văn bản thành các đoạn nhỏ hơn
     * Phương thức này sử dụng VertexAI để phân tách văn bản thành các câu riêng biệt,
     * giúp tạo ra các đoạn có ý nghĩa cho việc tạo vector nhúng hiệu quả
     * 
     * @param content Nội dung văn bản cần phân tách
     * @return Danh sách các đoạn văn bản đã được phân tách
     */
    private List<String> splitContent(String content) { // Phương thức phân tách nội dung văn bản thành các đoạn
        logger.info("Splitting content with length: " + content.length() + " characters");
        
        // Nếu nội dung quá dài, phân tách theo dòng hoặc câu đơn giản
        if (content.length() > 5000) {
            logger.info("Content too large for AI splitting, using simple method");
            return splitContentSimple(content);
        }
        
        // Sử dụng Vertex AI để phân đoạn văn bản với timeout
        try {
            String prompt = "Hãy phân đoạn văn bản sau thành các câu ngắn, mỗi câu trên một dòng:\n" + content; // Tạo prompt yêu cầu AI phân đoạn văn bản
            logger.info("Calling Vertex AI to split content");
            
            // Đặt timeout 20 giây để tránh treo quá lâu
            String response = vertexAIService.generateTextWithTimeout(prompt, 20); // Gọi dịch vụ AI để sinh văn bản phân đoạn
            
            if (response == null || response.trim().isEmpty()) {
                logger.warning("Empty response from Vertex AI, using simple splitting");
                return splitContentSimple(content);
            }
            
            List<String> segments = Arrays.stream(response.split("\n")) // Phân tách kết quả thành các dòng dựa trên ký tự xuống dòng
                    .filter(s -> !s.trim().isEmpty()) // Lọc bỏ các dòng rỗng
                    .collect(Collectors.toList()); // Chuyển đổi luồng dữ liệu thành danh sách
            
            logger.info("Vertex AI returned " + segments.size() + " segments");
            return segments;
        } catch (Exception e) {
            logger.warning("Error using Vertex AI for content splitting: " + e.getMessage() + ". Falling back to simple method.");
            e.printStackTrace();
            return splitContentSimple(content);
        }
    }
    
    /**
     * Phân tách nội dung theo phương pháp đơn giản (fallback)
     * Phương thức này được sử dụng khi Vertex AI không khả dụng 
     * hoặc khi nội dung quá lớn
     * 
     * @param content Nội dung văn bản cần phân tách
     * @return Danh sách các đoạn văn bản đã được phân tách
     */
    private List<String> splitContentSimple(String content) {
        logger.info("Using simple content splitting method");
        List<String> segments = new ArrayList<>();
        
        // Phân tách theo dòng trước
        String[] lines = content.split("\n");
        
        for (String line : lines) {
            if (line.trim().isEmpty()) {
                continue;
            }
            
            // Nếu dòng quá dài, phân tách thành các đoạn nhỏ hơn
            if (line.length() > 200) {
                // Phân tách theo dấu chấm hoặc dấu chấm phẩy
                String[] sentences = line.split("[.;!?]+");
                for (String sentence : sentences) {
                    if (!sentence.trim().isEmpty()) {
                        segments.add(sentence.trim());
                    }
                }
            } else {
                segments.add(line.trim());
            }
        }
        
        logger.info("Simple splitting returned " + segments.size() + " segments");
        return segments;
    }

    /**
     * Tạo vector nhúng cho danh sách đoạn văn bản theo lô
     * Phương thức này chia nhỏ danh sách đoạn thành các lô có kích thước cố định
     * để xử lý hiệu quả và tránh quá tải hệ thống khi tác vụ lớn
     * 
     * @param documentId ID của tài liệu đang xử lý
     * @param segments Danh sách các đoạn văn bản cần tạo vector nhúng
     */
    private void createVectorsInBatches(Long documentId, List<String> segments) { // Phương thức tạo vector nhúng theo lô
        logger.info("Processing " + segments.size() + " segments for document ID: " + documentId); // Ghi log thông tin bắt đầu xử lý
        
        for (int i = 0; i < segments.size(); i += BATCH_SIZE) { // Duyệt qua danh sách đoạn với bước nhảy bằng kích thước lô
            List<String> batch = segments.subList(i, Math.min(i + BATCH_SIZE, segments.size())); // Tạo lô con từ danh sách đoạn
            createVectorsForBatch(documentId, batch); // Gọi phương thức tạo vector cho lô con
            
            // Log tiến trình định kỳ
            if ((i / BATCH_SIZE) % 5 == 0) { // Kiểm tra để ghi log định kỳ (mỗi 5 lô)
                logger.info("Processed " + Math.min(i + BATCH_SIZE, segments.size()) + 
                           " of " + segments.size() + " segments for document ID: " + documentId); // Ghi log tiến trình
            }
        }
        
        logger.info("Completed processing all " + segments.size() + " segments for document ID: " + documentId); // Ghi log hoàn thành
    }

    /**
     * Tạo vector nhúng cho một lô đoạn văn bản
     * Phương thức này xử lý và lưu trữ vector nhúng cho một lô đoạn văn bản cùng một lúc,
     * giúp tối ưu hóa việc lưu trữ dữ liệu vào cơ sở dữ liệu
     * 
     * @param documentId ID của tài liệu đang xử lý
     * @param segments Danh sách các đoạn văn bản trong lô
     */
    private void createVectorsForBatch(Long documentId, List<String> segments) { // Phương thức tạo vector cho một lô đoạn
        List<EmbeddingVector> batchVectors = new ArrayList<>(); // Khởi tạo danh sách để lưu các vector nhúng trong lô
        
        for (String segment : segments) { // Duyệt qua từng đoạn văn bản trong lô
            float[] vector = vectorService.createEmbedding(segment); // Tạo vector nhúng cho đoạn văn bản
            EmbeddingVector embeddingVector = new EmbeddingVector(); // Khởi tạo đối tượng lưu trữ vector nhúng
            embeddingVector.setDocumentId(documentId); // Đặt ID tài liệu cho vector nhúng
            embeddingVector.setSegment(segment); // Đặt nội dung đoạn văn bản
            embeddingVector.setVectorData(vector); // Đặt dữ liệu vector nhúng
            batchVectors.add(embeddingVector); // Thêm vector nhúng vào danh sách
        }
        
        // Lưu tất cả vector cùng một lúc thay vì từng cái một
        if (!batchVectors.isEmpty()) { // Kiểm tra nếu danh sách vector không rỗng
            embeddingRepository.saveAll(batchVectors); // Lưu tất cả vector vào cơ sở dữ liệu
            logger.info("Saved batch of " + batchVectors.size() + " vectors for document ID: " + documentId); // Ghi log thành công
        }
        
        // Vô hiệu hóa cache sau khi thêm các vector mới
        vectorService.invalidateVectorsCache(); // Xóa cache vector để đảm bảo dữ liệu mới nhất được sử dụng
        logger.info("Vector cache invalidated after creating batch vectors"); // Ghi log thông tin xóa cache
    }

    /**
     * Lấy tất cả tài liệu từ cơ sở dữ liệu
     * Phương thức này truy vấn và trả về danh sách tất cả tài liệu đã lưu trữ,
     * được sắp xếp theo thời gian tạo giảm dần (mới nhất đầu tiên)
     * 
     * @return Danh sách các tài liệu đã lưu trữ
     */
    public List<Document> getAllDocuments() { // Phương thức lấy tất cả tài liệu
        return documentRepository.findAllOrderByCreatedAtDesc(); // Gọi phương thức repository để lấy tài liệu theo thứ tự thời gian giảm dần
    }

    /**
     * Xóa tài liệu và tất cả dữ liệu liên quan
     * Phương thức này xóa tài liệu cùng với tất cả vector nhúng, câu hỏi và
     * lịch sử trò chuyện liên quan đến tài liệu đó
     * 
     * @param documentId ID của tài liệu cần xóa
     * @throws RuntimeException Nếu không tìm thấy tài liệu hoặc có lỗi trong quá trình xóa
     */
    @Transactional // Đảm bảo tính toàn vẹn của giao dịch, nếu có lỗi sẽ rollback toàn bộ
    public void deleteDocument(Long documentId) { // Phương thức xóa tài liệu và dữ liệu liên quan
        try {
            if (!documentRepository.existsById(documentId)) { // Kiểm tra xem tài liệu có tồn tại không
                throw new RuntimeException("Document not found"); // Ném ngoại lệ nếu không tìm thấy tài liệu
            }

            // Xóa các vector embedding
            List<EmbeddingVector> vectors = embeddingRepository.findByDocumentId(documentId); // Lấy danh sách vector liên quan đến tài liệu
            embeddingRepository.deleteAll(vectors); // Xóa tất cả vector nhúng
            
            // Vô hiệu hóa cache sau khi xóa các vector
            vectorService.invalidateVectorsCache(); // Xóa cache vector để đảm bảo dữ liệu mới nhất được sử dụng
            logger.info("Vector cache invalidated after deleting document vectors"); // Ghi log thông tin xóa cache

            // Xóa các câu hỏi
            List<Question> questions = questionRepository.findByDocumentId(documentId); // Lấy danh sách câu hỏi liên quan đến tài liệu
            questionRepository.deleteAll(questions); // Xóa tất cả câu hỏi

            // Xóa lịch sử chat
            List<ChatHistory> chats = chatHistoryRepository.findByDocumentId(documentId); // Lấy danh sách lịch sử trò chuyện liên quan đến tài liệu
            chatHistoryRepository.deleteAll(chats); // Xóa tất cả lịch sử trò chuyện

            // Xóa tài liệu
            documentRepository.deleteById(documentId); // Xóa tài liệu từ cơ sở dữ liệu
        } catch (Exception e) { // Bắt tất cả các ngoại lệ có thể xảy ra
            logger.severe("Error deleting document: " + e.getMessage()); // Ghi log lỗi nghiêm trọng
            throw new RuntimeException("Failed to delete document", e); // Ném ngoại lệ RuntimeException với thông báo lỗi
        }
    }
}
