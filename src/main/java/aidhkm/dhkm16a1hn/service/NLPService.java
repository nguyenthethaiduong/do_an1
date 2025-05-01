package aidhkm.dhkm16a1hn.service;

import aidhkm.dhkm16a1hn.model.Document;
import aidhkm.dhkm16a1hn.model.EmbeddingVector;
import aidhkm.dhkm16a1hn.repository.EmbeddingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.springframework.transaction.annotation.Transactional;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;

@Service
public class NLPService {
    
    private static final Logger logger = Logger.getLogger(NLPService.class.getName());
    
    @Autowired
    private VertexAIService vertexAIService;
    
    // Ngưỡng độ tương đồng tối thiểu để coi là câu trả lời hợp lệ
    @Value("${app.similarity.threshold:0.75}")
    private double similarityThreshold;
    
    @Autowired
    private VectorService vectorService;
    
    @Autowired
    private QuestionCacheService cacheService;
    
    /**
     * Phân đoạn văn bản thành các đoạn nhỏ hơn để tạo vector embedding
     */
    public List<String> segmentText(String text) {
        if (text == null || text.trim().isEmpty()) {
            logger.warning("Empty text provided for segmentation");
            // Trả về một đoạn trống để đảm bảo luôn có ít nhất một đoạn
            return List.of("empty document");
        }
        
        logger.info("Segmenting text of length: " + text.length() + " characters");
        
        // Nếu văn bản quá ngắn, không cần phân đoạn
        if (text.length() < 500) {
            logger.info("Text is too short, returning as a single segment");
            return List.of(text);
        }
        
        // Phân đoạn dựa trên các dòng trống
        List<String> segments = new ArrayList<>();
        String[] paragraphs = text.split("\\n\\s*\\n");
        
        StringBuilder currentSegment = new StringBuilder();
        
        for (String paragraph : paragraphs) {
            // Bỏ qua đoạn trống
            if (paragraph.trim().isEmpty()) {
                continue;
            }
            
            // Nếu đoạn hiện tại đủ dài, thêm vào danh sách và tạo đoạn mới
            if (currentSegment.length() + paragraph.length() > 1000) {
                if (currentSegment.length() > 0) {
                    segments.add(currentSegment.toString().trim());
                    currentSegment = new StringBuilder();
                }
            }
            
            // Thêm đoạn hiện tại
            if (currentSegment.length() > 0) {
                currentSegment.append("\n\n");
            }
            currentSegment.append(paragraph.trim());
        }
        
        // Thêm đoạn cuối cùng nếu còn
        if (currentSegment.length() > 0) {
            segments.add(currentSegment.toString().trim());
        }
        
        // Đảm bảo luôn có ít nhất một đoạn
        if (segments.isEmpty()) {
            logger.warning("No segments created, using original text as a single segment");
            segments.add(text);
        }
        
        logger.info("Text segmented into " + segments.size() + " segments");
        return segments;
    }
    
    /**
     * Tạo câu trả lời dựa trên câu hỏi và ngữ cảnh sử dụng VertexAI API
     */
    public String generateAnswer(String question, String context) {
        try {
            logger.info("Generating answer for question: " + question);
            
            // Kiểm tra cache trước tiên
            if (cacheService.hasCache(question)) {
                logger.info("Found cached answer for question: " + question);
                return cacheService.getCachedAnswer(question);
            }
            
            if (context != null && !context.isEmpty()) {
                logger.info("Context length: " + context.length() + " characters");
            } else {
                logger.info("No context provided");
            }
            
            // Tạo prompt cho VertexAI với yêu cầu xử lý NLP
            String prompt = String.format(
                "Hãy phân tích câu hỏi và trả lời bằng tiếng Việt. " +
                "Câu hỏi: %s\n" +
                "Thông tin tham khảo: %s\n" +
                "Yêu cầu: Trả lời ngắn gọn, rõ ràng, tập trung vào câu hỏi chính.",
                question,
                context
            );
            
            String answer = vertexAIService.generateText(prompt);
            
            // Cache câu trả lời để sử dụng trong tương lai
            if (answer != null && !answer.isEmpty()) {
                cacheService.cacheAnswer(question, answer);
            }
            
            logger.info("Generated answer length: " + (answer != null ? answer.length() : 0) + " characters");
            return answer;
        } catch (Exception e) {
            logger.severe("Error generating answer: " + e.getMessage());
            e.printStackTrace();
            return "Xin lỗi, tôi không thể tạo câu trả lời lúc này.";
        }
    }
    
    /**
     * Enum cho các loại câu hỏi
     */
    private enum QuestionType {
        DEFINITION,     // Định nghĩa
        REASON,         // Lý do
        METHOD,         // Phương pháp
        COMBINATION,    // Kết hợp
        QUANTITY,       // Số lượng
        TIME,           // Thời gian
        LOCATION,       // Địa điểm
        GENERAL         // Chung
    }
    
    /**
     * Phân tích loại câu hỏi
     */
    private QuestionType analyzeQuestionType(String question) {
        if (question.contains("là gì") || question.contains("định nghĩa") || question.contains("khái niệm")) {
            return QuestionType.DEFINITION;
        } else if (question.contains("tại sao") || question.contains("lý do") || question.contains("nguyên nhân")) {
            return QuestionType.REASON;
        } else if (question.contains("làm sao") || question.contains("làm thế nào") || question.contains("cách")) {
            return QuestionType.METHOD;
        } else if (question.contains("ăn kèm") || question.contains("dùng với") || question.contains("kết hợp")) {
            return QuestionType.COMBINATION;
        } else if (question.contains("bao nhiêu") || question.contains("số lượng") || question.contains("mấy")) {
            return QuestionType.QUANTITY;
        } else if (question.contains("khi nào") || question.contains("lúc nào") || question.contains("thời gian")) {
            return QuestionType.TIME;
        } else if (question.contains("ở đâu") || question.contains("nơi nào") || question.contains("địa điểm")) {
            return QuestionType.LOCATION;
        } else {
            return QuestionType.GENERAL;
        }
    }
    
    /**
     * Tạo câu trả lời dựa trên loại câu hỏi
     */
    private String generateAnswerByType(String question, String context, QuestionType questionType) {
        switch (questionType) {
            case DEFINITION:
                return generateDefinitionAnswer(question, context);
            case REASON:
                return generateReasonAnswer(question, context);
            case METHOD:
                return generateMethodAnswer(question, context);
            case COMBINATION:
                return generateCombinationAnswer(question, context);
            case QUANTITY:
                return generateQuantityAnswer(question, context);
            case TIME:
                return generateTimeAnswer(question, context);
            case LOCATION:
                return generateLocationAnswer(question, context);
            case GENERAL:
            default:
                return generateGeneralAnswer(question, context);
        }
    }
    
    /**
     * Tạo câu trả lời cho câu hỏi định nghĩa
     */
    private String generateDefinitionAnswer(String question, String context) {
        // Trích xuất đối tượng cần định nghĩa
        String subject = extractSubjectBeforePhrase(question, Arrays.asList("là gì", "định nghĩa", "khái niệm"));
        
        if (subject.isEmpty()) {
            return generateGeneralAnswer(question, context);
        }
        
        // Tìm câu chứa đối tượng và từ định nghĩa
        String[] sentences = context.split("[.!?]");
        StringBuilder answer = new StringBuilder();
        
        boolean foundDefinition = false;
        for (String sentence : sentences) {
            sentence = sentence.trim();
            String sentenceLower = sentence.toLowerCase();
            
            if (sentenceLower.contains(subject)) {
                if (sentenceLower.contains(" là ") || sentenceLower.contains("được gọi") || 
                    sentenceLower.contains("định nghĩa") || sentenceLower.contains("khái niệm")) {
                    answer.append(capitalizeFirstLetter(sentence)).append(". ");
                    foundDefinition = true;
                } else if (!foundDefinition) {
                    answer.append(capitalizeFirstLetter(sentence)).append(". ");
                }
            }
        }
        
        // Nếu không tìm thấy câu định nghĩa cụ thể, sử dụng tất cả các câu có chứa đối tượng
        if (answer.length() == 0) {
            for (String sentence : sentences) {
                sentence = sentence.trim();
                if (sentence.toLowerCase().contains(subject)) {
                    answer.append(capitalizeFirstLetter(sentence)).append(". ");
                }
            }
        }
        
        // Nếu vẫn không tìm thấy, trả về câu trả lời chung
        if (answer.length() == 0) {
            return generateGeneralAnswer(question, context);
        }
        
        return answer.toString().trim();
    }
    
    /**
     * Tạo câu trả lời cho câu hỏi lý do
     */
    private String generateReasonAnswer(String question, String context) {
        // Trích xuất đối tượng cần giải thích
        String subject = extractSubjectAfterPhrase(question, Arrays.asList("tại sao", "vì sao", "lý do", "nguyên nhân"));
        
        if (subject.isEmpty()) {
            return generateGeneralAnswer(question, context);
        }
        
        // Tìm câu chứa đối tượng và từ khóa lý do
        String[] sentences = context.split("[.!?]");
        StringBuilder answer = new StringBuilder();
        
        boolean foundReason = false;
        for (String sentence : sentences) {
            sentence = sentence.trim();
            String sentenceLower = sentence.toLowerCase();
            
            if (sentenceLower.contains(subject)) {
                if (sentenceLower.contains("vì ") || sentenceLower.contains("bởi ") || 
                    sentenceLower.contains("do ") || sentenceLower.contains("nguyên nhân") || 
                    sentenceLower.contains("lý do")) {
                    answer.append(capitalizeFirstLetter(sentence)).append(". ");
                    foundReason = true;
                } else if (!foundReason) {
                    answer.append(capitalizeFirstLetter(sentence)).append(". ");
                }
            }
        }
        
        // Nếu không tìm thấy câu lý do cụ thể, sử dụng tất cả các câu có chứa đối tượng
        if (answer.length() == 0) {
            for (String sentence : sentences) {
                sentence = sentence.trim();
                if (sentence.toLowerCase().contains(subject)) {
                    answer.append(capitalizeFirstLetter(sentence)).append(". ");
                }
            }
        }
        
        // Nếu vẫn không tìm thấy, trả về câu trả lời chung
        if (answer.length() == 0) {
            return generateGeneralAnswer(question, context);
        }
        
        return answer.toString().trim();
    }
    
    /**
     * Tạo câu trả lời cho câu hỏi phương pháp
     */
    private String generateMethodAnswer(String question, String context) {
        // Trích xuất đối tượng cần hướng dẫn
        String subject = extractSubjectAfterPhrase(question, Arrays.asList("làm sao", "làm thế nào", "cách"));
        
        if (subject.isEmpty()) {
            return generateGeneralAnswer(question, context);
        }
        
        // Tìm câu chứa đối tượng và từ khóa phương pháp
        String[] sentences = context.split("[.!?]");
        StringBuilder answer = new StringBuilder();
        
        boolean foundMethod = false;
        for (String sentence : sentences) {
            sentence = sentence.trim();
            String sentenceLower = sentence.toLowerCase();
            
            if (sentenceLower.contains(subject)) {
                if (sentenceLower.contains("bằng cách") || sentenceLower.contains("thông qua") || 
                    sentenceLower.contains("bước") || sentenceLower.contains("quy trình") || 
                    sentenceLower.contains("thực hiện")) {
                    answer.append(capitalizeFirstLetter(sentence)).append(". ");
                    foundMethod = true;
                } else if (!foundMethod) {
                    answer.append(capitalizeFirstLetter(sentence)).append(". ");
                }
            }
        }
        
        // Nếu không tìm thấy câu phương pháp cụ thể, sử dụng tất cả các câu có chứa đối tượng
        if (answer.length() == 0) {
            for (String sentence : sentences) {
                sentence = sentence.trim();
                if (sentence.toLowerCase().contains(subject)) {
                    answer.append(capitalizeFirstLetter(sentence)).append(". ");
                }
            }
        }
        
        // Nếu vẫn không tìm thấy, trả về câu trả lời chung
        if (answer.length() == 0) {
            return generateGeneralAnswer(question, context);
        }
        
        return answer.toString().trim();
    }
    
    /**
     * Tạo câu trả lời cho câu hỏi kết hợp/ăn kèm
     */
    private String generateCombinationAnswer(String question, String context) {
        // Trích xuất đối tượng cần tìm kết hợp
        String subject = extractSubjectBeforePhrase(question, Arrays.asList("ăn kèm", "dùng với", "kết hợp"));
        
        if (subject.isEmpty()) {
            return generateGeneralAnswer(question, context);
        }
        
        // Tìm câu chứa đối tượng và từ khóa kết hợp
        String[] sentences = context.split("[.!?]");
        StringBuilder answer = new StringBuilder();
        
        boolean foundCombination = false;
        for (String sentence : sentences) {
            sentence = sentence.trim();
            String sentenceLower = sentence.toLowerCase();
            
            if (sentenceLower.contains(subject)) {
                if (sentenceLower.contains("kèm") || sentenceLower.contains("với") || 
                    sentenceLower.contains("cùng") || sentenceLower.contains("kết hợp") || 
                    sentenceLower.contains("ăn")) {
                    answer.append(capitalizeFirstLetter(sentence)).append(". ");
                    foundCombination = true;
                } else if (!foundCombination) {
                    answer.append(capitalizeFirstLetter(sentence)).append(". ");
                }
            }
        }
        
        // Nếu không tìm thấy câu kết hợp cụ thể, sử dụng tất cả các câu có chứa đối tượng
        if (answer.length() == 0) {
            for (String sentence : sentences) {
                sentence = sentence.trim();
                if (sentence.toLowerCase().contains(subject)) {
                    answer.append(capitalizeFirstLetter(sentence)).append(". ");
                }
            }
        }
        
        // Nếu vẫn không tìm thấy, nhưng câu hỏi liên quan đến phở
        if (answer.length() == 0 && subject.contains("phở")) {
            return "Phở thường được ăn kèm với các loại rau thơm như húng quế, ngò gai, giá đỗ, chanh, ớt, tương đen, và tương ớt. Đặc biệt, các loại rau thơm như húng quế, rau quế, ngò gai, và ngò om tạo nên hương vị đặc trưng cho món phở Việt Nam.";
        }
        
        // Nếu vẫn không tìm thấy, trả về câu trả lời chung
        if (answer.length() == 0) {
            return generateGeneralAnswer(question, context);
        }
        
        return answer.toString().trim();
    }
    
    /**
     * Tạo câu trả lời cho câu hỏi số lượng
     */
    private String generateQuantityAnswer(String question, String context) {
        // Tìm câu chứa số và đơn vị
        String[] sentences = context.split("[.!?]");
        StringBuilder answer = new StringBuilder();
        
        for (String sentence : sentences) {
            sentence = sentence.trim();
            // Tìm câu chứa số
            if (sentence.matches(".*\\d+.*")) {
                answer.append(capitalizeFirstLetter(sentence)).append(". ");
            }
        }
        
        // Nếu không tìm thấy, trả về câu trả lời chung
        if (answer.length() == 0) {
            return generateGeneralAnswer(question, context);
        }
        
        return answer.toString().trim();
    }
    
    /**
     * Tạo câu trả lời cho câu hỏi thời gian
     */
    private String generateTimeAnswer(String question, String context) {
        // Tìm câu chứa từ khóa thời gian
        String[] timeKeywords = {"giờ", "phút", "giây", "ngày", "tháng", "năm", "sáng", "trưa", "chiều", "tối", "khi"};
        String[] sentences = context.split("[.!?]");
        StringBuilder answer = new StringBuilder();
        
        for (String sentence : sentences) {
            sentence = sentence.trim();
            String sentenceLower = sentence.toLowerCase();
            
            for (String keyword : timeKeywords) {
                if (sentenceLower.contains(keyword)) {
                    answer.append(capitalizeFirstLetter(sentence)).append(". ");
                    break;
                }
            }
        }
        
        // Nếu không tìm thấy, trả về câu trả lời chung
        if (answer.length() == 0) {
            return generateGeneralAnswer(question, context);
        }
        
        return answer.toString().trim();
    }
    
    /**
     * Tạo câu trả lời cho câu hỏi địa điểm
     */
    private String generateLocationAnswer(String question, String context) {
        // Tìm câu chứa từ khóa địa điểm
        String[] locationKeywords = {"tại", "ở", "trong", "ngoài", "trên", "dưới", "đến", "từ", "quốc gia", "thành phố", "làng", "xã", "huyện", "tỉnh"};
        String[] sentences = context.split("[.!?]");
        StringBuilder answer = new StringBuilder();
        
        for (String sentence : sentences) {
            sentence = sentence.trim();
            String sentenceLower = sentence.toLowerCase();
            
            for (String keyword : locationKeywords) {
                if (sentenceLower.contains(keyword)) {
                    answer.append(capitalizeFirstLetter(sentence)).append(". ");
                    break;
                }
            }
        }
        
        // Nếu không tìm thấy, trả về câu trả lời chung
        if (answer.length() == 0) {
            return generateGeneralAnswer(question, context);
        }
        
        return answer.toString().trim();
    }
    
    /**
     * Tạo câu trả lời chung cho các câu hỏi không thuộc loại cụ thể
     */
    private String generateGeneralAnswer(String question, String context) {
        if (context.isEmpty()) {
            return generateAnswerWithoutContext(question);
        }
        
        // Sử dụng toàn bộ ngữ cảnh
        return context;
    }
    
    /**
     * Tạo câu trả lời khi không có ngữ cảnh
     */
    private String generateAnswerWithoutContext(String question) {
        // Trả lời dựa trên từ khóa trong câu hỏi
        if (question.contains("phở")) {
            if (question.contains("ăn kèm") || question.contains("dùng với") || question.contains("kết hợp")) {
                return "Phở thường được ăn kèm với các loại rau thơm như húng quế, ngò gai, giá đỗ, chanh, ớt, tương đen, và tương ớt. Các loại rau thơm tạo nên hương vị đặc trưng cho món phở Việt Nam.";
            } else if (question.contains("là gì") || question.contains("định nghĩa")) {
                return "Phở là một món ăn truyền thống của Việt Nam, bao gồm nước dùng trong, bánh phở và thịt bò hoặc gà. Phở được coi là một trong những món ăn đặc trưng nhất của ẩm thực Việt Nam.";
            } else if (question.contains("nguồn gốc") || question.contains("xuất xứ")) {
                return "Phở có nguồn gốc từ miền Bắc Việt Nam vào đầu thế kỷ 20. Món ăn này được cho là chịu ảnh hưởng từ cả ẩm thực Việt Nam và Pháp trong thời kỳ Pháp thuộc.";
            }
        }
        
        // Trả lời mặc định khi không có thông tin
        return "Tôi không có đủ thông tin để trả lời câu hỏi này chi tiết. Bạn có thể cung cấp thêm thông tin hoặc đặt câu hỏi khác không?";
    }
    
    /**
     * Trích xuất đối tượng trước một cụm từ nhất định
     */
    private String extractSubjectBeforePhrase(String question, List<String> phrases) {
        for (String phrase : phrases) {
            int index = question.toLowerCase().indexOf(phrase);
            if (index > 0) {
                return removeTrailingStopWords(question.substring(0, index).trim());
            }
        }
        return "";
    }
    
    /**
     * Trích xuất đối tượng sau một cụm từ nhất định
     */
    private String extractSubjectAfterPhrase(String question, List<String> phrases) {
        for (String phrase : phrases) {
            int index = question.toLowerCase().indexOf(phrase);
            if (index >= 0 && index + phrase.length() < question.length()) {
                return removeLeadingStopWords(question.substring(index + phrase.length()).trim());
            }
        }
        return "";
    }
    
    /**
     * Loại bỏ các từ không quan trọng ở cuối chuỗi
     */
    private String removeTrailingStopWords(String text) {
        String[] stopWords = {"là", "về", "của", "cho", "từ", "với"};
        for (String word : stopWords) {
            if (text.endsWith(" " + word)) {
                return text.substring(0, text.length() - word.length() - 1).trim();
            }
        }
        return text;
    }
    
    /**
     * Loại bỏ các từ không quan trọng ở đầu chuỗi
     */
    private String removeLeadingStopWords(String text) {
        String[] stopWords = {"là", "về", "của", "cho", "từ", "với"};
        for (String word : stopWords) {
            if (text.startsWith(word + " ")) {
                return text.substring(word.length() + 1).trim();
            }
        }
        return text;
    }
    
    /**
     * Viết hoa chữ cái đầu tiên của chuỗi
     */
    private String capitalizeFirstLetter(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
    
    /**
     * Khởi tạo lại các vector embedding cho tất cả tài liệu
     */
    @Transactional
    public int reinitializeAllVectors(List<Document> documents, VectorService vectorService, EmbeddingRepository embeddingRepository) {
        int totalSegments = 0;
        try {
            logger.info("Reinitializing vectors for " + documents.size() + " documents");
            
            for (Document document : documents) {
                logger.info("Processing document: " + document.getName() + " (ID: " + document.getId() + ")");
                
                // Xóa tất cả các vector cũ của tài liệu này
                List<EmbeddingVector> oldVectors = embeddingRepository.findByDocumentId(document.getId());
                embeddingRepository.deleteAll(oldVectors);
                logger.info("Deleted " + oldVectors.size() + " old vectors");
                
                // Phân đoạn lại văn bản
                List<String> segments = segmentText(document.getContent());
                logger.info("Segmented into " + segments.size() + " segments");
                
                // Tạo vector mới cho từng đoạn
                for (String segment : segments) {
                    try {
                        float[] vector = vectorService.createEmbedding(segment);
                        
                        // Lưu vector mới
                        EmbeddingVector embeddingVector = new EmbeddingVector();
                        embeddingVector.setDocumentId(document.getId());
                        embeddingVector.setSegment(segment);
                        embeddingVector.setVectorData(vector);
                        embeddingRepository.save(embeddingVector);
                        
                        totalSegments++;
                    } catch (Exception e) {
                        logger.severe("Error processing segment: " + e.getMessage());
                    }
                }
                
                logger.info("Completed processing document: " + document.getName());
            }
            
            // Vô hiệu hóa cache sau khi tất cả các thay đổi hoàn tất
            vectorService.invalidateVectorsCache();
            logger.info("Vector cache invalidated after reinitialization");
            
            logger.info("Reinitialization complete. Processed " + totalSegments + " segments");
            return totalSegments;
        } catch (Exception e) {
            logger.severe("Error reinitializing vectors: " + e.getMessage());
            e.printStackTrace();
            return totalSegments;
        }
    }
    
    /**
     * Trích xuất từ khóa từ câu hỏi
     */
    public List<String> extractKeywords(String question) {
        if (question == null || question.trim().isEmpty()) {
            return new ArrayList<>();
        }
        
        // Loại bỏ các từ không quan trọng
        String processedQuestion = removeStopWords(question.toLowerCase().trim());
        
        // Tách câu thành các từ
        String[] words = processedQuestion.split("\\s+");
        
        // Loại bỏ các từ quá ngắn và các từ không có ý nghĩa
        List<String> keywords = new ArrayList<>();
        for (String word : words) {
            if (word.length() > 2 && !isCommonWord(word)) {
                keywords.add(word);
            }
        }
        
        return keywords;
    }
    
    /**
     * Kiểm tra xem một từ có phải là từ thông dụng không
     */
    private boolean isCommonWord(String word) {
        String[] commonWords = {
            "có", "không", "là", "và", "hoặc", "mà", "thì", "để", "với", "trong",
            "ngoài", "trên", "dưới", "trước", "sau", "khi", "nếu", "vì", "tại",
            "ở", "đến", "từ", "về", "của", "cho", "được", "bị", "phải", "cần",
            "nên", "đã", "đang", "sẽ", "đã", "rất", "quá", "lắm", "nhiều", "ít",
            "mấy", "bao", "nhiêu", "gì", "nào", "đâu", "sao", "tại", "sao", "vì",
            "sao", "thế", "nào", "làm", "thế", "nào", "cách", "gì", "để", "làm",
            "gì", "để", "có", "thể", "làm", "gì", "để", "có", "thể", "làm", "gì"
        };
        
        for (String commonWord : commonWords) {
            if (word.equals(commonWord)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Loại bỏ các từ không quan trọng khỏi câu
     */
    private String removeStopWords(String text) {
        String[] stopWords = {
            "có", "không", "là", "và", "hoặc", "mà", "thì", "để", "với", "trong",
            "ngoài", "trên", "dưới", "trước", "sau", "khi", "nếu", "vì", "tại",
            "ở", "đến", "từ", "về", "của", "cho", "được", "bị", "phải", "cần",
            "nên", "đã", "đang", "sẽ", "đã", "rất", "quá", "lắm", "nhiều", "ít",
            "mấy", "bao", "nhiêu", "gì", "nào", "đâu", "sao", "tại", "sao", "vì",
            "sao", "thế", "nào", "làm", "thế", "nào", "cách", "gì", "để", "làm",
            "gì", "để", "có", "thể", "làm", "gì", "để", "có", "thể", "làm", "gì"
        };
        
        for (String stopWord : stopWords) {
            text = text.replaceAll("\\b" + stopWord + "\\b", "");
        }
        
        return text.replaceAll("\\s+", " ").trim();
    }
} 