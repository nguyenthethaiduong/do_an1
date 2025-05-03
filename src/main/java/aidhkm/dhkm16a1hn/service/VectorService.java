package aidhkm.dhkm16a1hn.service; // Khai báo package chứa lớp dịch vụ

import aidhkm.dhkm16a1hn.model.Document; // Import model Document để làm việc với dữ liệu tài liệu
import aidhkm.dhkm16a1hn.model.EmbeddingVector; // Import model EmbeddingVector để làm việc với vector nhúng
import aidhkm.dhkm16a1hn.repository.DocumentRepository; // Import repository để thao tác với cơ sở dữ liệu tài liệu
import aidhkm.dhkm16a1hn.repository.EmbeddingRepository; // Import repository để thao tác với cơ sở dữ liệu vector nhúng
import aidhkm.dhkm16a1hn.util.VectorUtil; // Import tiện ích để xử lý vector
import org.springframework.beans.factory.annotation.Autowired; // Import annotation để tiêm phụ thuộc tự động
import org.springframework.data.domain.PageRequest; // Import lớp để phân trang kết quả truy vấn
import org.springframework.data.domain.Pageable; // Import interface để phân trang kết quả truy vấn
import org.springframework.stereotype.Service; // Import annotation để đánh dấu lớp là một dịch vụ
import org.springframework.transaction.annotation.Transactional; // Import annotation để quản lý giao dịch

import java.util.*; // Import các lớp tiện ích của Java
import java.util.concurrent.ConcurrentHashMap; // Import lớp HashMap an toàn với đa luồng
import java.util.logging.Logger; // Import Logger để ghi log
import java.util.stream.Collectors; // Import để làm việc với luồng dữ liệu
import java.time.LocalDateTime; // Import lớp để làm việc với ngày giờ
import java.io.PrintWriter; // Import lớp để ghi lỗi
import java.io.StringWriter; // Import lớp để chuyển đổi stack trace thành chuỗi
import javax.annotation.PostConstruct; // Import annotation để đánh dấu phương thức khởi tạo sau khi bean được tạo

/**
 * Dịch vụ xử lý vector nhúng cho nội dung văn bản
 * Cung cấp các chức năng để tạo, lưu trữ, tìm kiếm và quản lý
 * các vector nhúng để hỗ trợ tìm kiếm ngữ nghĩa
 */
@Service // Đánh dấu lớp này là một dịch vụ Spring để Spring container quản lý
public class VectorService { // Khai báo lớp dịch vụ xử lý vector
    private static final Logger logger = Logger.getLogger(VectorService.class.getName()); // Khởi tạo Logger để ghi log hoạt động của lớp
    private static final int CACHE_SIZE = 1000; // Kích thước tối đa của cache lưu trữ vector nhúng
    private static final float SIMILARITY_THRESHOLD = 0.20f; // Ngưỡng độ tương đồng tối thiểu để lọc kết quả tìm kiếm
    private static final int TOP_K = 3; // Số lượng kết quả tối đa trả về khi tìm kiếm

    // Sử dụng LinkedHashMap cho embeddingCache với access-order để implement LRU cache
    private final Map<String, float[]> embeddingCache = new LinkedHashMap<String, float[]>(CACHE_SIZE, 0.75f, true) { // Cache theo cơ chế LRU (Least Recently Used)
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) { // Ghi đè phương thức để kiểm tra khi nào cần loại bỏ phần tử cũ nhất
            return size() > CACHE_SIZE; // Loại bỏ phần tử cũ nhất khi kích thước vượt quá giới hạn
        }
    };
    private final Map<String, List<EmbeddingVector>> documentVectorsCache = new ConcurrentHashMap<>(); // Cache lưu trữ vector theo ID tài liệu, an toàn với đa luồng
    
    // Cache cho danh sách vectors
    private List<EmbeddingVector> vectorsCache = null; // Cache lưu trữ tất cả vector trong hệ thống
    private LocalDateTime lastVectorsCacheUpdate = null; // Thời điểm cập nhật cache gần nhất
    private static final long VECTORS_CACHE_EXPIRY_SECONDS = 60; // Cache hết hạn sau 1 phút (60 giây)

    @Autowired private EmbeddingRepository embeddingRepository; // Repository để truy vấn và lưu trữ vector nhúng
    @Autowired private DocumentRepository documentRepository; // Repository để truy vấn và lưu trữ tài liệu
    @Autowired private VertexAIService vertexAIService; // Dịch vụ tương tác với Vertex AI để tạo vector nhúng

    /**
     * Lấy tất cả vectors từ database, có cache để tránh gọi database nhiều lần
     * Phương thức này sử dụng cơ chế cache có thời gian hết hạn để tối ưu hiệu suất,
     * giảm thiểu số lần truy vấn cơ sở dữ liệu khi cần danh sách vector nhúng
     * 
     * @return Danh sách tất cả các vector nhúng đã lưu trữ
     */
    private List<EmbeddingVector> getAllVectors() { // Phương thức lấy tất cả vector nhúng
        // Kiểm tra xem cache có tồn tại và chưa hết hạn không
        if (vectorsCache != null && lastVectorsCacheUpdate != null) { // Kiểm tra nếu cache đã được khởi tạo
            LocalDateTime now = LocalDateTime.now(); // Lấy thời gian hiện tại
            long secondsSinceLastUpdate = java.time.Duration.between(lastVectorsCacheUpdate, now).getSeconds(); // Tính thời gian đã trôi qua kể từ lần cập nhật cuối
            
            // Nếu cache chưa hết hạn, sử dụng cache
            if (secondsSinceLastUpdate < VECTORS_CACHE_EXPIRY_SECONDS) { // Kiểm tra nếu cache chưa hết hạn
                logger.info("Using vectors cache (" + vectorsCache.size() + " vectors, updated " + 
                           secondsSinceLastUpdate + " seconds ago)"); // Ghi log thông tin sử dụng cache
                return vectorsCache; // Trả về danh sách vector từ cache
            }
        }
        
        // Nếu cache không tồn tại hoặc đã hết hạn, lấy lại từ database
        List<EmbeddingVector> allVectors = embeddingRepository.findAll(); // Truy vấn tất cả vector từ cơ sở dữ liệu
        vectorsCache = allVectors; // Cập nhật cache với dữ liệu mới
        lastVectorsCacheUpdate = LocalDateTime.now(); // Cập nhật thời gian cập nhật cache
        logger.info("Updated vectors cache with " + allVectors.size() + " vectors from database"); // Ghi log thông tin cập nhật cache
        
        return allVectors; // Trả về danh sách vector đã cập nhật
    }
    
    /**
     * Xóa cache vectors để buộc cập nhật lại từ database trong lần gọi tiếp theo
     * Phương thức này được gọi khi có thay đổi dữ liệu vector (thêm, sửa, xóa)
     * để đảm bảo dữ liệu cache luôn được cập nhật
     */
    public void invalidateVectorsCache() { // Phương thức xóa cache vector
        vectorsCache = null; // Đặt cache thành null để buộc phải tải lại từ cơ sở dữ liệu
        lastVectorsCacheUpdate = null; // Đặt thời gian cập nhật thành null
        logger.info("Vectors cache invalidated"); // Ghi log thông tin xóa cache
    }

    /**
     * Tạo vector nhúng cho văn bản đã cho
     * Phương thức này sẽ xử lý vấn đề kích thước vector và vô hiệu hóa cache
     * nếu phát hiện vector đã lưu trữ không khớp với mô hình hiện tại
     * 
     * @param text Văn bản cần tạo vector nhúng
     * @return Vector nhúng đã được tạo
     */
    public float[] createEmbedding(String text) { // Phương thức tạo vector nhúng cho văn bản
        if (text == null || text.trim().isEmpty()) { // Kiểm tra nếu văn bản rỗng hoặc null
            return new float[0]; // Trả về vector rỗng
        }
        
        // Kiểm tra xem có vector nhúng trong cache không
        String key = text.trim().toLowerCase(); // Chuẩn hóa văn bản làm khóa cache (chữ thường và loại bỏ khoảng trắng thừa)
        if (embeddingCache.containsKey(key)) { // Kiểm tra nếu vector đã có trong cache
            float[] cached = embeddingCache.get(key); // Lấy vector từ cache
            
            // Nếu là kích thước cũ, vô hiệu hóa và tạo lại
            if (cached.length != 768 && vertexAIService.getEmbeddingModelName().contains("text-embedding-005")) { // Kiểm tra nếu kích thước vector không khớp với mô hình
                logger.warning("Vector nhúng trong cache có kích thước không chính xác - dự kiến 768, nhưng có " + 
                              cached.length + ". Đang xóa cache và tạo lại."); // Ghi log cảnh báo
                embeddingCache.remove(key); // Xóa vector không hợp lệ khỏi cache
                vertexAIService.clearEmbeddingCache(); // Xóa cache bên trong dịch vụ Vertex AI
            } else {
                return cached; // Trả về vector đã lưu trong cache
            }
        }
        
        // Lấy vector nhúng từ VertexAIService
        float[] embedding = vertexAIService.createEmbedding(text); // Gọi dịch vụ Vertex AI để tạo vector nhúng
        
        // Lưu kết quả vào cache nếu hợp lệ
        if (embedding != null && embedding.length > 0) { // Kiểm tra nếu vector được tạo thành công
            embeddingCache.put(key, embedding); // Lưu vector vào cache
        }
        
        return embedding; // Trả về vector nhúng đã tạo
    }

    /**
     * Phương pháp dự phòng để tạo vector khi API thất bại
     * Tạo vector nhúng đơn giản dựa trên tần suất từ trong văn bản
     * khi không thể sử dụng dịch vụ Vertex AI
     * 
     * @param text Văn bản cần tạo vector nhúng
     * @return Vector nhúng đơn giản dựa trên tần suất từ
     */
    private float[] createFallbackEmbedding(String text) { // Phương thức tạo vector dự phòng
        try {
            text = normalizeText(text); // Chuẩn hóa văn bản đầu vào
            float[] vector = new float[300]; // Khởi tạo vector với kích thước 300
            Arrays.stream(text.split("\\s+")) // Tách văn bản thành các từ dựa trên khoảng trắng
                .filter(word -> !word.isEmpty()) // Lọc bỏ các từ rỗng
                .forEach(word -> { // Xử lý từng từ
                    int hash = Math.abs(word.hashCode()); // Tính giá trị băm của từ
                    int index = hash % 300; // Tính chỉ số dựa trên giá trị băm (giới hạn trong phạm vi 0-299)
                    vector[index]++; // Tăng giá trị tại chỉ số tương ứng với từ
                });
            return normalizeVector(vector); // Chuẩn hóa vector trước khi trả về
        } catch (Exception e) { // Bắt ngoại lệ nếu có lỗi
            logger.severe("Error creating fallback embedding: " + e.getMessage()); // Ghi log lỗi
            return createRandomVector(300); // Tạo vector ngẫu nhiên nếu xảy ra lỗi
        }
    }

    /**
     * Tạo vector ngẫu nhiên với kích thước cho trước
     * Vector ngẫu nhiên được sử dụng khi không thể tạo vector
     * bằng phương pháp thông thường hoặc dự phòng
     * 
     * @param dimension Kích thước của vector cần tạo
     * @return Vector ngẫu nhiên đã được chuẩn hóa
     */
    private float[] createRandomVector(int dimension) { // Phương thức tạo vector ngẫu nhiên
        float[] vector = new float[dimension]; // Khởi tạo vector với kích thước cho trước
        Random random = new Random(); // Khởi tạo đối tượng Random để tạo số ngẫu nhiên

        for (int i = 0; i < dimension; i++) { // Duyệt qua từng phần tử của vector
            vector[i] = random.nextFloat() * 2 - 1; // Gán giá trị ngẫu nhiên trong khoảng -1 đến 1
        }

        return normalizeVector(vector); // Chuẩn hóa vector trước khi trả về
    }

    /**
     * Chuẩn hóa văn bản đầu vào để cải thiện chất lượng vector
     * Quá trình chuẩn hóa bao gồm: chuyển về chữ thường, loại bỏ
     * dấu câu, ký tự đặc biệt và khoảng trắng thừa
     * 
     * @param text Văn bản cần chuẩn hóa
     * @return Văn bản đã được chuẩn hóa
     */
    private String normalizeText(String text) { // Phương thức chuẩn hóa văn bản
        if (text == null) { // Kiểm tra nếu văn bản là null
            return ""; // Trả về chuỗi rỗng
        }

        // Chuyển về chữ thường
        text = text.toLowerCase(); // Chuyển tất cả ký tự thành chữ thường

        // Loại bỏ dấu câu và ký tự đặc biệt
        text = text.replaceAll("[^a-z0-9\\s]", " "); // Thay thế tất cả ký tự không phải chữ cái, số hoặc khoảng trắng bằng khoảng trắng

        // Loại bỏ khoảng trắng thừa
        text = text.replaceAll("\\s+", " ").trim(); // Thay thế nhiều khoảng trắng liên tiếp bằng một khoảng trắng và loại bỏ khoảng trắng ở đầu/cuối

        return text; // Trả về văn bản đã chuẩn hóa
    }

    /**
     * Chuẩn hóa vector để có độ dài bằng 1 (đơn vị hóa)
     * Quá trình này giúp các vector có thể so sánh với nhau
     * dễ dàng hơn bằng phép đo cosine similarity
     * 
     * @param vector Vector cần chuẩn hóa
     * @return Vector đã được chuẩn hóa
     */
    private float[] normalizeVector(float[] vector) { // Phương thức chuẩn hóa vector
        float magnitude = 0.0f; // Khởi tạo biến lưu độ lớn của vector
        for (float v : vector) { // Duyệt qua từng phần tử của vector
            magnitude += v * v; // Cộng dồn bình phương của mỗi phần tử
        }
        magnitude = (float) Math.sqrt(magnitude); // Tính căn bậc hai để có độ lớn của vector

        if (magnitude > 0) { // Kiểm tra nếu độ lớn khác 0
            for (int i = 0; i < vector.length; i++) { // Duyệt qua từng phần tử của vector
                vector[i] /= magnitude; // Chia mỗi phần tử cho độ lớn để chuẩn hóa
            }
        }
        return vector; // Trả về vector đã chuẩn hóa
    }

    /**
     * Tìm thông tin liên quan nhất dựa trên vector nhúng của câu hỏi
     * Phương thức này tìm kiếm các đoạn văn bản có độ tương đồng cao nhất
     * với vector nhúng của câu hỏi, giúp cung cấp thông tin phù hợp
     * để trả lời câu hỏi của người dùng
     * 
     * @param questionVector Vector nhúng của câu hỏi
     * @return Danh sách các đoạn văn bản liên quan nhất
     */
    public List<String> findMostRelevantInfo(float[] questionVector) { // Phương thức tìm thông tin liên quan nhất
        try {
            logger.info("Tìm kiếm thông tin liên quan nhất cho vector câu hỏi"); // Ghi log thông tin bắt đầu tìm kiếm

            List<EmbeddingVector> allVectors = getAllVectors(); // Lấy tất cả vector nhúng từ cơ sở dữ liệu (hoặc từ cache)
            if (allVectors.isEmpty()) { // Kiểm tra nếu không có vector nào
                logger.info("Không tìm thấy vector nhúng nào trong cơ sở dữ liệu"); // Ghi log thông tin không tìm thấy vector
                return new ArrayList<>(); // Trả về danh sách rỗng
            }

            // Tìm top 5 vector có độ tương đồng cao nhất
            List<ScoredVector> scoredVectors = new ArrayList<>(); // Khởi tạo danh sách để lưu vector kèm điểm số
            for (EmbeddingVector vector : allVectors) { // Duyệt qua tất cả vector
                float similarity = VectorUtil.cosineSimilarity(questionVector, vector.getVectorData()); // Tính độ tương đồng cosine
                if (similarity > SIMILARITY_THRESHOLD) { // Kiểm tra nếu độ tương đồng vượt ngưỡng
                    scoredVectors.add(new ScoredVector(vector, similarity)); // Thêm vector và điểm số vào danh sách
                }
            }

            // Sắp xếp theo độ tương đồng giảm dần
            scoredVectors.sort((v1, v2) -> Float.compare(v2.getScore(), v1.getScore())); // Sắp xếp danh sách vector theo điểm số giảm dần

            // Lấy top 5 kết quả
            List<String> results = new ArrayList<>(); // Khởi tạo danh sách kết quả
            for (int i = 0; i < Math.min(5, scoredVectors.size()); i++) { // Duyệt qua tối đa 5 vector có điểm cao nhất
                EmbeddingVector vector = scoredVectors.get(i).getVector(); // Lấy vector từ đối tượng ScoredVector
                results.add(vector.getDocumentId() + " | " + vector.getSegment()); // Thêm ID tài liệu và đoạn văn bản vào kết quả
            }

            return results; // Trả về danh sách kết quả
        } catch (Exception e) { // Bắt ngoại lệ nếu có lỗi
            logger.severe("Lỗi khi tìm kiếm thông tin liên quan: " + e.getMessage()); // Ghi log lỗi
            return new ArrayList<>(); // Trả về danh sách rỗng trong trường hợp có lỗi
        }
    }

    /**
     * Lớp helper để lưu trữ vector và điểm tương đồng
     * Lớp này giúp ghép cặp một vector nhúng với điểm số tương đồng
     * của nó, thuận tiện cho việc sắp xếp và lựa chọn kết quả
     */
    private static class ScoredVector { // Lớp nội bộ để lưu trữ vector kèm điểm số
        private final EmbeddingVector vector; // Vector nhúng
        private final float score; // Điểm số đánh giá mức độ tương đồng

        /**
         * Khởi tạo đối tượng ScoredVector
         * 
         * @param vector Vector nhúng cần lưu trữ
         * @param score Điểm số tương đồng của vector
         */
        public ScoredVector(EmbeddingVector vector, float score) { // Constructor của lớp ScoredVector
            this.vector = vector; // Gán vector nhúng
            this.score = score; // Gán điểm số
        }

        /**
         * Lấy vector nhúng
         * 
         * @return Vector nhúng đã lưu trữ
         */
        public EmbeddingVector getVector() { // Phương thức getter cho vector
            return vector; // Trả về vector nhúng
        }

        /**
         * Lấy điểm số tương đồng
         * 
         * @return Điểm số tương đồng đã lưu trữ
         */
        public float getScore() { // Phương thức getter cho điểm số
            return score; // Trả về điểm số
        }
    }

    /**
     * Lưu đoạn văn bản và vector embedding vào database
     * Phương thức này lưu trữ một đoạn văn bản và vector nhúng tương ứng
     * vào cơ sở dữ liệu, với kiểm tra để tránh lưu trùng lặp
     * 
     * @param segment Đoạn văn bản cần lưu trữ
     * @param vectorData Vector nhúng tương ứng với đoạn văn bản
     * @param documentId ID của tài liệu chứa đoạn văn bản
     * @return true nếu lưu thành công, false nếu thất bại
     */
    @Transactional
    public boolean saveEmbeddingVector(String segment, float[] vectorData, Long documentId) {
        try {
            if (segment == null || segment.trim().isEmpty() || vectorData == null || vectorData.length == 0) {
                logger.warning("Dữ liệu đầu vào không hợp lệ cho saveEmbeddingVector");
                return false;
            }

            // Giới hạn độ dài đoạn văn bản
            String segmentToStore = segment;
            if (segmentToStore.length() > 5000) {
                segmentToStore = segmentToStore.substring(0, 5000);
            }

            // Kiểm tra xem vector đã tồn tại chưa
            List<EmbeddingVector> existingVectors = embeddingRepository.findBySegment(segmentToStore);
            if (!existingVectors.isEmpty()) {
                logger.info("Vector đã tồn tại cho đoạn văn bản này, bỏ qua việc lưu");
                return true;
            }

            // Tạo entity mới
            EmbeddingVector embeddingVector = new EmbeddingVector();
            embeddingVector.setSegment(segmentToStore);
            embeddingVector.setVectorData(vectorData);
            embeddingVector.setDocumentId(documentId);

            // Lưu vào database
            embeddingRepository.save(embeddingVector);
            
            // Vô hiệu hóa cache khi có sự thay đổi dữ liệu
            invalidateVectorsCache();
            
            logger.info("Đã lưu thành công vector nhúng cho đoạn văn bản: " + segmentToStore.substring(0, Math.min(30, segmentToStore.length())) + "...");

            return true;
        } catch (Exception e) {
            logger.severe("Lỗi khi lưu vector nhúng: " + e.getMessage());
            return false;
        }
    }

    /**
     * Xóa tất cả vector embedding bị treo (không có tài liệu tương ứng)
     * @return Số lượng vector đã xóa
     */
    @Transactional
    public int cleanupOrphanedEmbeddings() {
        try {
            logger.info("Bắt đầu dọn dẹp vector embeddings bị treo...");

            // Lấy danh sách tất cả document ID hiện có
            Set<Long> existingDocumentIds = documentRepository.findAll().stream()
                    .map(Document::getId)
                    .collect(Collectors.toSet());

            // Lấy danh sách các vector embedding trong database
            List<EmbeddingVector> allVectors = getAllVectors();

            // Tìm các vector mồ côi (documentId = null hoặc không tồn tại document tương ứng)
            List<EmbeddingVector> orphanedVectors = allVectors.stream()
                    .filter(vector ->
                        vector.getDocumentId() == null ||
                        !existingDocumentIds.contains(vector.getDocumentId()))
                    .collect(Collectors.toList());

            // In số lượng vector mồ côi tìm thấy
            logger.info("Đã tìm thấy " + orphanedVectors.size() + " vector embeddings bị treo");

            // Nếu có vector mồ côi, tiến hành xóa
            if (!orphanedVectors.isEmpty()) {
                embeddingRepository.deleteAll(orphanedVectors);
                
                // Vô hiệu hóa cache khi có sự thay đổi dữ liệu
                invalidateVectorsCache();
                
                logger.info("Đã xóa thành công " + orphanedVectors.size() + " vector embeddings bị treo");
                return orphanedVectors.size();
            }

            return 0;

        } catch (Exception e) {
            logger.severe("Lỗi khi dọn dẹp vector embeddings bị treo: " + e.getMessage());
            e.printStackTrace();
            return 0;
        }
    }

    /**
     * Trả về thống kê về vector embeddings
     * @return Map chứa các thông tin thống kê
     */
    public Map<String, Object> getEmbeddingStatistics() {
        Map<String, Object> stats = new HashMap<>();

        // Tổng số vector
        long totalCount = embeddingRepository.count();
        stats.put("totalCount", totalCount);

        // Tổng số tài liệu có vector
        Set<Long> uniqueDocumentIds = new HashSet<>();
        getAllVectors().forEach(vec -> uniqueDocumentIds.add(vec.getDocumentId()));
        stats.put("documentCount", uniqueDocumentIds.size());

        // Tỉ lệ trung bình vector/tài liệu
        double avgVectorsPerDocument = uniqueDocumentIds.isEmpty() ? 0 :
            (double) totalCount / uniqueDocumentIds.size();
        stats.put("avgVectorsPerDocument", Math.round(avgVectorsPerDocument * 100) / 100.0);

        return stats;
    }

    /**
     * Tìm kiếm các câu tương tự với câu hỏi
     * Phương thức này so sánh vector nhúng của câu hỏi với các vector
     * trong cơ sở dữ liệu để tìm những đoạn văn bản tương tự nhất
     * 
     * @param question Câu hỏi cần tìm kiếm
     * @param limit Số lượng câu tương tự cần trả về
     * @return Danh sách các câu tương tự
     */
    public List<String> searchSimilarSentences(String question, int limit) {
        long startTime = System.currentTimeMillis();
        try {
            logger.info("Tìm kiếm các câu tương tự với: " + question);

            // Kiểm tra xem câu hỏi có phải là câu ngắn đơn giản không (như "ok", "được rồi", "thank you")
            if (question.length() < 15 && question.split("\\s+").length < 3) {
                logger.info("Phát hiện cụm từ hội thoại ngắn, bỏ qua tìm kiếm vector");
                return new ArrayList<>();
            }

            // Tạo vector embedding cho câu hỏi
            long embedStartTime = System.currentTimeMillis();
            float[] questionVector = vertexAIService.createEmbedding(question);
            logger.info("Thời gian tạo vector nhúng: " + (System.currentTimeMillis() - embedStartTime) + "ms");

            // Kiểm tra nếu vector rỗng (có thể do lỗi API)
            if (questionVector == null || questionVector.length == 0) {
                logger.warning("Không thể tạo vector nhúng cho câu hỏi: " + question);
                // Sử dụng tìm kiếm dựa trên từ khóa khi không thể tạo vector nhúng
                return keywordBasedSearch(question, limit);
            }

            // Thiết lập ngưỡng tương đồng tối thiểu cao hơn
            final float MIN_SIMILARITY = 0.25f;

            // Lấy tất cả vector từ database
            List<EmbeddingVector> allVectors = getAllVectors();
            if (allVectors.isEmpty()) {
                logger.info("Không tìm thấy vector nhúng nào trong cơ sở dữ liệu");
                return new ArrayList<>();
            }

            logger.info("Tìm thấy " + allVectors.size() + " vector trong cơ sở dữ liệu");

            // Tạo danh sách các vector có điểm tương đồng
            List<ScoredVector> scoredVectors = new ArrayList<>();

            // Tính toán điểm tương đồng cho từng vector
            for (EmbeddingVector vector : allVectors) {
                float similarity = VectorUtil.cosineSimilarity(questionVector, vector.getVectorData());

                // In ra log để debug
                logger.info("Vector cho đoạn [" + vector.getSegment().substring(0, Math.min(30, vector.getSegment().length()))
                    + "...] có độ tương đồng: " + similarity);

                // Chỉ thêm vào vector có điểm tương đồng cao hơn ngưỡng
                if (similarity >= MIN_SIMILARITY) {
                    scoredVectors.add(new ScoredVector(vector, similarity));
                }
            }

            // Nếu không tìm thấy câu tương tự nào vượt ngưỡng, thử tìm kiếm dựa trên từ khóa
            if (scoredVectors.isEmpty()) {
                logger.warning("Không tìm thấy câu nào có độ tương đồng trên ngưỡng (" + MIN_SIMILARITY + ") cho: " + question);
                return keywordBasedSearch(question, limit);
            }

            // Sắp xếp theo độ tương đồng giảm dần
            scoredVectors.sort((v1, v2) -> Float.compare(v2.getScore(), v1.getScore()));

            // Lấy top k kết quả
            List<ScoredVector> topResults = scoredVectors.stream()
                    .limit(limit)
                    .collect(Collectors.toList());

            // In ra các điểm tương đồng để debug
            for (int i = 0; i < topResults.size(); i++) {
                ScoredVector sv = topResults.get(i);
                logger.info("Kết quả #" + (i + 1) + ": điểm=" + sv.getScore() + ", văn bản=" +
                           sv.getVector().getSegment().substring(0, Math.min(50, sv.getVector().getSegment().length())) + "...");
            }

            // Trích xuất các câu từ các vector được chọn
            List<String> similarSentences = topResults.stream()
                    .map(sv -> "score=" + sv.getScore() + " | " + sv.getVector().getSegment())
                    .collect(Collectors.toList());

            long totalTime = System.currentTimeMillis() - startTime;
            logger.info("Tổng thời gian tìm kiếm: " + totalTime + "ms, tìm thấy " + similarSentences.size() + " kết quả khớp");
            return similarSentences;
        } catch (Exception e) {
            logger.severe("Lỗi khi tìm kiếm câu tương tự: " + e.getMessage());
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    /**
     * Tìm kiếm dựa trên từ khóa khi không thể sử dụng vector nhúng
     * Phương thức này sử dụng tìm kiếm đơn giản dựa trên sự xuất hiện
     * của các từ khóa trong đoạn văn bản
     * 
     * @param question Câu hỏi cần tìm kiếm
     * @param limit Số lượng kết quả tối đa
     * @return Danh sách các đoạn văn bản tương tự
     */
    private List<String> keywordBasedSearch(String question, int limit) {
        logger.info("Thực hiện tìm kiếm dựa trên từ khóa cho: " + question);

        try {
            // Chuẩn bị câu hỏi
            String normalizedQuestion = normalizeText(question);
            String[] keywords = normalizedQuestion.split("\\s+");

            // Lấy tất cả vector để tìm kiếm văn bản
            List<EmbeddingVector> allVectors = getAllVectors();

            // Xếp hạng các đoạn văn bản dựa trên số lượng từ khóa tìm thấy
            Map<EmbeddingVector, Integer> matchCounts = new HashMap<>();

            for (EmbeddingVector vector : allVectors) {
                String normalizedSegment = normalizeText(vector.getSegment());
                int matchCount = 0;

                for (String keyword : keywords) {
                    if (keyword.length() >= 3 && normalizedSegment.contains(keyword)) {
                        matchCount++;
                    }
                }

                if (matchCount > 0) {
                    matchCounts.put(vector, matchCount);
                }
            }

            // Sắp xếp theo số lượng từ khóa khớp, giảm dần
            List<Map.Entry<EmbeddingVector, Integer>> sortedEntries = matchCounts.entrySet()
                    .stream()
                    .sorted(Map.Entry.<EmbeddingVector, Integer>comparingByValue().reversed())
                    .limit(limit)
                    .collect(Collectors.toList());

            // Trích xuất các đoạn văn bản kết quả
            List<String> results = new ArrayList<>();
            for (Map.Entry<EmbeddingVector, Integer> entry : sortedEntries) {
                // Thêm điểm số chuẩn hóa (khoảng 0.3 - 0.5) dựa trên số lượng từ khóa khớp
                float normalizedScore = 0.3f + Math.min(0.2f, (float)entry.getValue() / 10.0f);
                results.add("score=" + normalizedScore + " | " + entry.getKey().getSegment());
                logger.info("Kết quả từ khóa: [" + entry.getValue() + " từ khóa, điểm=" + normalizedScore + "] " +
                          entry.getKey().getSegment().substring(0, Math.min(100, entry.getKey().getSegment().length())) + "...");
            }

            return results;

        } catch (Exception e) {
            logger.severe("Lỗi trong tìm kiếm từ khóa: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Lưu nhiều embedding vector cùng lúc
     * Phương thức này tối ưu hóa việc lưu nhiều vector cùng một lúc
     * bằng cách sử dụng giao dịch và thao tác hàng loạt
     * 
     * @param vectors Danh sách vector cần lưu
     * @return true nếu thành công, false nếu thất bại
     */
    @Transactional
    public boolean saveEmbeddingVectors(List<EmbeddingVector> vectors) {
        try {
            if (vectors == null || vectors.isEmpty()) {
                logger.warning("Không có vector nào để lưu");
                return false;
            }

            // Lưu tất cả vectors cùng một lúc
            embeddingRepository.saveAll(vectors);
            
            // Vô hiệu hóa cache khi có sự thay đổi dữ liệu
            invalidateVectorsCache();
            
            logger.info("Đã lưu thành công " + vectors.size() + " vector nhúng theo lô");
            return true;
        } catch (Exception e) {
            logger.severe("Lỗi khi lưu các vector nhúng theo lô: " + e.getMessage());
            return false;
        }
    }

    /**
     * Xóa tất cả bộ nhớ đệm trong VectorService
     * Điều này hữu ích khi kích thước vector thay đổi hoặc khi các vector nhúng được tạo lại
     */
    public void clearAllCaches() {
        int cacheSize = embeddingCache.size();
        embeddingCache.clear();
        
        int docCacheSize = documentVectorsCache.size();
        documentVectorsCache.clear();
        
        vectorsCache = null;
        lastVectorsCacheUpdate = null;
        
        logger.info("Đã xóa tất cả bộ nhớ đệm VectorService: " + 
                   cacheSize + " embeddings, " + 
                   docCacheSize + " document vectors, và cache danh sách vector");
    }
    
    @PostConstruct
    public void init() {
        // Xóa cache khi khởi động để tránh sự không khớp về kích thước
        clearAllCaches();
        logger.info("VectorService đã được khởi tạo và cache đã được xóa");
    }

    /**
     * Tái tạo lại tất cả các vector nhúng trong cơ sở dữ liệu để đảm bảo kích thước nhất quán
     * Phương thức này sẽ:
     * 1. Lấy tất cả vector từ cơ sở dữ liệu
     * 2. Đối với mỗi vector, tạo lại vector nhúng sử dụng mô hình hiện tại
     * 3. Cập nhật cơ sở dữ liệu với các vector mới
     * 4. Xóa tất cả bộ nhớ đệm
     * 
     * @return Số lượng vector đã được tái tạo
     */
    @Transactional
    public int regenerateAllVectors() {
        logger.info("Bắt đầu tái tạo tất cả vector để đảm bảo kích thước nhất quán");
        
        try {
            // Lấy tất cả vector từ cơ sở dữ liệu (bỏ qua cache)
            List<EmbeddingVector> allVectors = embeddingRepository.findAll();
            
            if (allVectors.isEmpty()) {
                logger.info("Không tìm thấy vector nào trong cơ sở dữ liệu để tái tạo");
                return 0;
            }
            
            logger.info("Đã tìm thấy " + allVectors.size() + " vector cần tái tạo");
            int regeneratedCount = 0;
            int errorCount = 0;
            
            // Kiểm tra kích thước dự kiến của mô hình hiện tại
            String currentModel = vertexAIService.getEmbeddingModelName();
            int expectedDimension = currentModel.contains("text-embedding-005") ? 768 : 128;
            logger.info("Mô hình vector nhúng hiện tại: " + currentModel + " với kích thước dự kiến: " + expectedDimension);
            
            // Xóa bộ nhớ đệm trước khi tái tạo
            clearAllCaches();
            vertexAIService.clearEmbeddingCache();
            
            // Xử lý vector theo lô để tránh vấn đề bộ nhớ
            int batchSize = 100;
            List<EmbeddingVector> currentBatch = new ArrayList<>(batchSize);
            
            for (EmbeddingVector vector : allVectors) {
                try {
                    // Lấy văn bản gốc
                    String originalText = vector.getSegment();
                    
                    // Bỏ qua nếu văn bản gốc bị thiếu
                    if (originalText == null || originalText.trim().isEmpty()) {
                        logger.warning("Vector ID " + vector.getId() + " có văn bản trống, bỏ qua việc tái tạo");
                        continue;
                    }
                    
                    // Kiểm tra xem vector đã có kích thước đúng chưa
                    if (vector.getVectorData() != null && vector.getVectorData().length == expectedDimension) {
                        logger.info("Vector ID " + vector.getId() + " đã có kích thước đúng (" + 
                                   expectedDimension + "), bỏ qua việc tái tạo");
                        continue;
                    }
                    
                    // Tạo lại vector nhúng sử dụng mô hình hiện tại
                    float[] newVector = vertexAIService.createEmbedding(originalText);
                    
                    // Kiểm tra xem việc tái tạo có thành công không
                    if (newVector == null || newVector.length == 0) {
                        logger.warning("Không thể tái tạo vector cho văn bản: " + 
                                     originalText.substring(0, Math.min(50, originalText.length())) + "...");
                        errorCount++;
                        continue;
                    }
                    
                    // Cập nhật vector trong đối tượng
                    vector.setVectorData(newVector);
                    
                    // Thêm vào lô hiện tại
                    currentBatch.add(vector);
                    regeneratedCount++;
                    
                    // Xử lý lô nếu đã đầy
                    if (currentBatch.size() >= batchSize) {
                        embeddingRepository.saveAll(currentBatch);
                        logger.info("Đã lưu lô gồm " + currentBatch.size() + " vector đã tái tạo");
                        currentBatch.clear();
                    }
                    
                    // Ghi log tiến trình định kỳ
                    if (regeneratedCount % 500 == 0) {
                        logger.info("Đã tái tạo " + regeneratedCount + " vector đến thời điểm hiện tại");
                    }
                    
                } catch (Exception e) {
                    logger.severe("Lỗi khi tái tạo vector ID " + vector.getId() + ": " + e.getMessage());
                    errorCount++;
                }
            }
            
            // Lưu các vector còn lại trong lô
            if (!currentBatch.isEmpty()) {
                embeddingRepository.saveAll(currentBatch);
                logger.info("Đã lưu lô cuối cùng gồm " + currentBatch.size() + " vector đã tái tạo");
            }
            
            // Xóa bộ nhớ đệm sau khi tái tạo
            clearAllCaches();
            
            logger.info("Hoàn tất tái tạo vector: " + 
                       regeneratedCount + " vector đã được tái tạo, " + 
                       errorCount + " lỗi đã xảy ra");
            
            return regeneratedCount;
            
        } catch (Exception e) {
            logger.severe("Lỗi trong quá trình tái tạo vector: " + e.getMessage());
            e.printStackTrace();
            return 0;
        }
    }
}
