package aidhkm.dhkm16a1hn.service;

import aidhkm.dhkm16a1hn.model.Document;
import aidhkm.dhkm16a1hn.model.EmbeddingVector;
import aidhkm.dhkm16a1hn.repository.DocumentRepository;
import aidhkm.dhkm16a1hn.repository.EmbeddingRepository;
import aidhkm.dhkm16a1hn.util.VectorUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.time.LocalDateTime;
import java.io.PrintWriter;
import java.io.StringWriter;
import javax.annotation.PostConstruct;

@Service
public class VectorService {
    private static final Logger logger = Logger.getLogger(VectorService.class.getName());
    private static final int CACHE_SIZE = 1000;
    private static final float SIMILARITY_THRESHOLD = 0.20f;
    private static final int TOP_K = 3;

    // Sử dụng LinkedHashMap cho embeddingCache với access-order để implement LRU cache
    private final Map<String, float[]> embeddingCache = new LinkedHashMap<String, float[]>(CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
            return size() > CACHE_SIZE;
        }
    };
    private final Map<String, List<EmbeddingVector>> documentVectorsCache = new ConcurrentHashMap<>();
    
    // Cache cho danh sách vectors
    private List<EmbeddingVector> vectorsCache = null;
    private LocalDateTime lastVectorsCacheUpdate = null;
    private static final long VECTORS_CACHE_EXPIRY_SECONDS = 60; // Cache hết hạn sau 1 phút

    @Autowired private EmbeddingRepository embeddingRepository;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private VertexAIService vertexAIService;

    /**
     * Lấy tất cả vectors từ database, có cache để tránh gọi database nhiều lần
     */
    private List<EmbeddingVector> getAllVectors() {
        // Kiểm tra xem cache có tồn tại và chưa hết hạn không
        if (vectorsCache != null && lastVectorsCacheUpdate != null) {
            LocalDateTime now = LocalDateTime.now();
            long secondsSinceLastUpdate = java.time.Duration.between(lastVectorsCacheUpdate, now).getSeconds();
            
            // Nếu cache chưa hết hạn, sử dụng cache
            if (secondsSinceLastUpdate < VECTORS_CACHE_EXPIRY_SECONDS) {
                logger.info("Using vectors cache (" + vectorsCache.size() + " vectors, updated " + 
                           secondsSinceLastUpdate + " seconds ago)");
                return vectorsCache;
            }
        }
        
        // Nếu cache không tồn tại hoặc đã hết hạn, lấy lại từ database
        List<EmbeddingVector> allVectors = embeddingRepository.findAll();
        vectorsCache = allVectors;
        lastVectorsCacheUpdate = LocalDateTime.now();
        logger.info("Updated vectors cache with " + allVectors.size() + " vectors from database");
        
        return allVectors;
    }
    
    /**
     * Xóa cache vectors để buộc cập nhật lại từ database trong lần gọi tiếp theo
     */
    public void invalidateVectorsCache() {
        vectorsCache = null;
        lastVectorsCacheUpdate = null;
        logger.info("Vectors cache invalidated");
    }

    /**
     * Tạo vector nhúng cho văn bản đã cho
     * Phương thức này sẽ xử lý vấn đề kích thước vector và vô hiệu hóa cache
     */
    public float[] createEmbedding(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new float[0];
        }
        
        // Kiểm tra xem có vector nhúng trong cache không
        String key = text.trim().toLowerCase();
        if (embeddingCache.containsKey(key)) {
            float[] cached = embeddingCache.get(key);
            
            // Nếu là kích thước cũ, vô hiệu hóa và tạo lại
            if (cached.length != 768 && vertexAIService.getEmbeddingModelName().contains("text-embedding-005")) {
                logger.warning("Vector nhúng trong cache có kích thước không chính xác - dự kiến 768, nhưng có " + 
                              cached.length + ". Đang xóa cache và tạo lại.");
                embeddingCache.remove(key);
                vertexAIService.clearEmbeddingCache();
            } else {
                return cached;
            }
        }
        
        // Lấy vector nhúng từ VertexAIService
        float[] embedding = vertexAIService.createEmbedding(text);
        
        // Lưu kết quả vào cache nếu hợp lệ
        if (embedding != null && embedding.length > 0) {
            embeddingCache.put(key, embedding);
        }
        
        return embedding;
    }

    /**
     * Phương pháp dự phòng để tạo vector khi API thất bại
     */
    private float[] createFallbackEmbedding(String text) {
        try {
            text = normalizeText(text);
            float[] vector = new float[300];
            Arrays.stream(text.split("\\s+"))
                .filter(word -> !word.isEmpty())
                .forEach(word -> {
                    int hash = Math.abs(word.hashCode());
                    int index = hash % 300;
                    vector[index]++;
                });
            return normalizeVector(vector);
        } catch (Exception e) {
            logger.severe("Error creating fallback embedding: " + e.getMessage());
            return createRandomVector(300);
        }
    }

    /**
     * Tạo vector ngẫu nhiên với kích thước cho trước
     */
    private float[] createRandomVector(int dimension) {
        float[] vector = new float[dimension];
        Random random = new Random();

        for (int i = 0; i < dimension; i++) {
            vector[i] = random.nextFloat() * 2 - 1; // Giá trị từ -1 đến 1
        }

        return normalizeVector(vector);
    }

    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }

        // Chuyển về chữ thường
        text = text.toLowerCase();

        // Loại bỏ dấu câu và ký tự đặc biệt
        text = text.replaceAll("[^a-z0-9\\s]", " ");

        // Loại bỏ khoảng trắng thừa
        text = text.replaceAll("\\s+", " ").trim();

        return text;
    }

    private float[] normalizeVector(float[] vector) {
        float magnitude = 0.0f;
        for (float v : vector) {
            magnitude += v * v;
        }
        magnitude = (float) Math.sqrt(magnitude);

        if (magnitude > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= magnitude;
            }
        }
        return vector;
    }

    /**
     * Tìm thông tin liên quan nhất dựa trên vector nhúng của câu hỏi
     * @param questionVector vector nhúng của câu hỏi
     * @return danh sách các đoạn văn bản liên quan
     */
    public List<String> findMostRelevantInfo(float[] questionVector) {
        try {
            logger.info("Finding most relevant information for the question vector");

            List<EmbeddingVector> allVectors = getAllVectors();
            if (allVectors.isEmpty()) {
                logger.info("No embedding vectors found in database");
                return new ArrayList<>();
            }

            // Tìm top 5 vector có độ tương đồng cao nhất
            List<ScoredVector> scoredVectors = new ArrayList<>();
            for (EmbeddingVector vector : allVectors) {
                float similarity = VectorUtil.cosineSimilarity(questionVector, vector.getVectorData());
                if (similarity > SIMILARITY_THRESHOLD) { // Sử dụng ngưỡng từ hằng số
                    scoredVectors.add(new ScoredVector(vector, similarity));
                }
            }

            // Sắp xếp theo độ tương đồng giảm dần
            scoredVectors.sort((v1, v2) -> Float.compare(v2.getScore(), v1.getScore()));

            // Lấy top 5 kết quả
            List<String> results = new ArrayList<>();
            for (int i = 0; i < Math.min(5, scoredVectors.size()); i++) {
                EmbeddingVector vector = scoredVectors.get(i).getVector();
                results.add(vector.getDocumentId() + " | " + vector.getSegment());
            }

            return results;
        } catch (Exception e) {
            logger.severe("Error finding relevant information: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Lớp helper để lưu trữ vector và điểm tương đồng
     */
    private static class ScoredVector {
        private final EmbeddingVector vector;
        private final float score;

        public ScoredVector(EmbeddingVector vector, float score) {
            this.vector = vector;
            this.score = score;
        }

        public EmbeddingVector getVector() {
            return vector;
        }

        public float getScore() {
            return score;
        }
    }

    /**
     * Lưu đoạn văn bản và vector embedding vào database
     */
    @Transactional
    public boolean saveEmbeddingVector(String segment, float[] vectorData, Long documentId) {
        try {
            if (segment == null || segment.trim().isEmpty() || vectorData == null || vectorData.length == 0) {
                logger.warning("Invalid input for saveEmbeddingVector");
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
                logger.info("Vector already exists for segment, skipping save");
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
            
            logger.info("Successfully saved embedding vector for segment: " + segmentToStore.substring(0, Math.min(30, segmentToStore.length())) + "...");

            return true;
        } catch (Exception e) {
            logger.severe("Error saving embedding vector: " + e.getMessage());
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
     * @param question Câu hỏi cần tìm kiếm
     * @param limit Số lượng câu tương tự cần trả về
     * @return Danh sách các câu tương tự
     */
    public List<String> searchSimilarSentences(String question, int limit) {
        long startTime = System.currentTimeMillis();
        try {
            logger.info("Searching for similar sentences to: " + question);

            // Kiểm tra xem câu hỏi có phải là câu ngắn đơn giản không (như "ok", "được rồi", "thank you")
            if (question.length() < 15 && question.split("\\s+").length < 3) {
                logger.info("Short conversational phrase detected, skipping vector search");
                return new ArrayList<>();
            }

            // Tạo vector embedding cho câu hỏi
            long embedStartTime = System.currentTimeMillis();
            float[] questionVector = vertexAIService.createEmbedding(question);
            logger.info("Time to create embedding: " + (System.currentTimeMillis() - embedStartTime) + "ms");

            // Kiểm tra nếu vector rỗng (có thể do lỗi API)
            if (questionVector == null || questionVector.length == 0) {
                logger.warning("Failed to create embedding for question: " + question);
                // Sử dụng tìm kiếm dựa trên từ khóa khi không thể tạo vector nhúng
                return keywordBasedSearch(question, limit);
            }

            // Thiết lập ngưỡng tương đồng tối thiểu cao hơn
            final float MIN_SIMILARITY = 0.25f;

            // Lấy tất cả vector từ database
            List<EmbeddingVector> allVectors = getAllVectors();
            if (allVectors.isEmpty()) {
                logger.info("No embedding vectors found in database");
                return new ArrayList<>();
            }

            logger.info("Found " + allVectors.size() + " vectors in database");

            // Tạo danh sách các vector có điểm tương đồng
            List<ScoredVector> scoredVectors = new ArrayList<>();

            // Tính toán điểm tương đồng cho từng vector
            for (EmbeddingVector vector : allVectors) {
                float similarity = VectorUtil.cosineSimilarity(questionVector, vector.getVectorData());

                // In ra log để debug
                logger.info("Vector for segment [" + vector.getSegment().substring(0, Math.min(30, vector.getSegment().length()))
                    + "...] has similarity: " + similarity);

                // Chỉ thêm vào vector có điểm tương đồng cao hơn ngưỡng
                if (similarity >= MIN_SIMILARITY) {
                    scoredVectors.add(new ScoredVector(vector, similarity));
                }
            }

            // Nếu không tìm thấy câu tương tự nào vượt ngưỡng, thử tìm kiếm dựa trên từ khóa
            if (scoredVectors.isEmpty()) {
                logger.warning("No sentences with similarity above threshold (" + MIN_SIMILARITY + ") found for: " + question);
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
                logger.info("Match #" + (i + 1) + ": score=" + sv.getScore() + ", text=" +
                           sv.getVector().getSegment().substring(0, Math.min(50, sv.getVector().getSegment().length())) + "...");
            }

            // Trích xuất các câu từ các vector được chọn
            List<String> similarSentences = topResults.stream()
                    .map(sv -> "score=" + sv.getScore() + " | " + sv.getVector().getSegment())
                    .collect(Collectors.toList());

            long totalTime = System.currentTimeMillis() - startTime;
            logger.info("Total search time: " + totalTime + "ms, found " + similarSentences.size() + " matches");
            return similarSentences;
        } catch (Exception e) {
            logger.severe("Error searching similar sentences: " + e.getMessage());
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    /**
     * Tìm kiếm dựa trên từ khóa khi không thể sử dụng vector nhúng
     * @param question Câu hỏi cần tìm kiếm
     * @param limit Số lượng kết quả tối đa
     * @return Danh sách các đoạn văn bản tương tự
     */
    private List<String> keywordBasedSearch(String question, int limit) {
        logger.info("Performing keyword-based search for: " + question);

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
                // Add a normalized score (0.3 - 0.5 range) based on keyword match count
                float normalizedScore = 0.3f + Math.min(0.2f, (float)entry.getValue() / 10.0f);
                results.add("score=" + normalizedScore + " | " + entry.getKey().getSegment());
                logger.info("Keyword match: [" + entry.getValue() + " keywords, score=" + normalizedScore + "] " +
                          entry.getKey().getSegment().substring(0, Math.min(100, entry.getKey().getSegment().length())) + "...");
            }

            return results;

        } catch (Exception e) {
            logger.severe("Error in keyword search: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Lưu nhiều embedding vector cùng lúc
     * @param vectors Danh sách vector cần lưu
     * @return true nếu thành công, false nếu thất bại
     */
    @Transactional
    public boolean saveEmbeddingVectors(List<EmbeddingVector> vectors) {
        try {
            if (vectors == null || vectors.isEmpty()) {
                logger.warning("No vectors to save");
                return false;
            }

            // Lưu tất cả vectors cùng một lúc
            embeddingRepository.saveAll(vectors);
            
            // Vô hiệu hóa cache khi có sự thay đổi dữ liệu
            invalidateVectorsCache();
            
            logger.info("Successfully saved " + vectors.size() + " embedding vectors in batch");
            return true;
        } catch (Exception e) {
            logger.severe("Error saving embedding vectors in batch: " + e.getMessage());
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
        // Clear caches on startup to avoid dimension mismatches
        clearAllCaches();
        logger.info("VectorService initialized and caches cleared");
    }

    /**
     * Regenerate all embedding vectors in the database to ensure consistent dimensions
     * This method will:
     * 1. Fetch all vectors from the database
     * 2. For each vector, regenerate its embedding using the current model
     * 3. Update the database with the new vectors
     * 4. Clear all caches
     * 
     * @return Number of vectors regenerated
     */
    @Transactional
    public int regenerateAllVectors() {
        logger.info("Starting regeneration of all vectors to ensure consistent dimensions");
        
        try {
            // Get all vectors from database (bypassing cache)
            List<EmbeddingVector> allVectors = embeddingRepository.findAll();
            
            if (allVectors.isEmpty()) {
                logger.info("No vectors found in database to regenerate");
                return 0;
            }
            
            logger.info("Found " + allVectors.size() + " vectors to regenerate");
            int regeneratedCount = 0;
            int errorCount = 0;
            
            // Check current model's expected dimension
            String currentModel = vertexAIService.getEmbeddingModelName();
            int expectedDimension = currentModel.contains("text-embedding-005") ? 768 : 128;
            logger.info("Current embedding model: " + currentModel + " with expected dimension: " + expectedDimension);
            
            // Clear caches before regeneration
            clearAllCaches();
            vertexAIService.clearEmbeddingCache();
            
            // Process vectors in batches to avoid memory issues
            int batchSize = 100;
            List<EmbeddingVector> currentBatch = new ArrayList<>(batchSize);
            
            for (EmbeddingVector vector : allVectors) {
                try {
                    // Get original text
                    String originalText = vector.getSegment();
                    
                    // Skip if original text is missing
                    if (originalText == null || originalText.trim().isEmpty()) {
                        logger.warning("Vector ID " + vector.getId() + " has empty text, skipping regeneration");
                        continue;
                    }
                    
                    // Check if vector already has correct dimensions
                    if (vector.getVectorData() != null && vector.getVectorData().length == expectedDimension) {
                        logger.info("Vector ID " + vector.getId() + " already has correct dimension (" + 
                                   expectedDimension + "), skipping regeneration");
                        continue;
                    }
                    
                    // Regenerate embedding using current model
                    float[] newVector = vertexAIService.createEmbedding(originalText);
                    
                    // Check if regeneration was successful
                    if (newVector == null || newVector.length == 0) {
                        logger.warning("Failed to regenerate vector for text: " + 
                                     originalText.substring(0, Math.min(50, originalText.length())) + "...");
                        errorCount++;
                        continue;
                    }
                    
                    // Update the vector in the object
                    vector.setVectorData(newVector);
                    
                    // Add to current batch
                    currentBatch.add(vector);
                    regeneratedCount++;
                    
                    // Process batch if full
                    if (currentBatch.size() >= batchSize) {
                        embeddingRepository.saveAll(currentBatch);
                        logger.info("Saved batch of " + currentBatch.size() + " regenerated vectors");
                        currentBatch.clear();
                    }
                    
                    // Log progress periodically
                    if (regeneratedCount % 500 == 0) {
                        logger.info("Regenerated " + regeneratedCount + " vectors so far");
                    }
                    
                } catch (Exception e) {
                    logger.severe("Error regenerating vector ID " + vector.getId() + ": " + e.getMessage());
                    errorCount++;
                }
            }
            
            // Save any remaining vectors in the batch
            if (!currentBatch.isEmpty()) {
                embeddingRepository.saveAll(currentBatch);
                logger.info("Saved final batch of " + currentBatch.size() + " regenerated vectors");
            }
            
            // Clear caches after regeneration
            clearAllCaches();
            
            logger.info("Vector regeneration complete: " + 
                       regeneratedCount + " vectors regenerated, " + 
                       errorCount + " errors encountered");
            
            return regeneratedCount;
            
        } catch (Exception e) {
            logger.severe("Error during vector regeneration: " + e.getMessage());
            e.printStackTrace();
            return 0;
        }
    }
}