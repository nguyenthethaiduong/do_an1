package aidhkm.dhkm16a1hn.service; // Khai báo package chứa lớp dịch vụ NLP

import aidhkm.dhkm16a1hn.model.Document; // Import model Document để làm việc với dữ liệu tài liệu
import aidhkm.dhkm16a1hn.model.EmbeddingVector; // Import model EmbeddingVector để làm việc với vector nhúng
import aidhkm.dhkm16a1hn.repository.EmbeddingRepository; // Import repository để thao tác với cơ sở dữ liệu vector nhúng
import org.springframework.beans.factory.annotation.Autowired; // Import annotation để tiêm phụ thuộc tự động
import org.springframework.stereotype.Service; // Import annotation để đánh dấu lớp là một dịch vụ
import java.util.ArrayList; // Import ArrayList để xử lý danh sách động
import java.util.List; // Import interface List để làm việc với các danh sách
import java.util.logging.Logger; // Import Logger để ghi log
import org.springframework.transaction.annotation.Transactional; // Import annotation để quản lý giao dịch
import java.util.Arrays; // Import Arrays để làm việc với mảng
import org.springframework.beans.factory.annotation.Value; // Import annotation để đọc giá trị từ file cấu hình
import java.util.regex.Pattern; // Import Pattern để làm việc với biểu thức chính quy
import java.util.concurrent.TimeUnit; // Import TimeUnit để làm việc với đơn vị thời gian

/**
 * Dịch vụ xử lý ngôn ngữ tự nhiên (NLP)
 * Cung cấp các chức năng phân tích văn bản, trích xuất từ khóa,
 * phân đoạn văn bản và tạo câu trả lời thông minh dựa trên ngữ cảnh
 */
@Service // Đánh dấu lớp này là một dịch vụ Spring để Spring container quản lý
public class NLPService { // Khai báo lớp dịch vụ xử lý ngôn ngữ tự nhiên
    
    private static final Logger logger = Logger.getLogger(NLPService.class.getName()); // Khởi tạo Logger để ghi log hoạt động của lớp
    
    @Autowired // Tiêm phụ thuộc tự động từ Spring container
    private VertexAIService vertexAIService; // Dịch vụ giao tiếp với Vertex AI để tạo câu trả lời
    
    // Ngưỡng độ tương đồng tối thiểu để coi là câu trả lời hợp lệ
    @Value("${app.similarity.threshold:0.75}") // Đọc giá trị từ file cấu hình, giá trị mặc định là 0.75
    private double similarityThreshold; // Ngưỡng độ tương đồng để xác định kết quả phù hợp
    
    @Autowired // Tiêm phụ thuộc tự động từ Spring container
    private VectorService vectorService; // Dịch vụ xử lý vector nhúng
    
    @Autowired // Tiêm phụ thuộc tự động từ Spring container
    private QuestionCacheService cacheService; // Dịch vụ lưu trữ cache câu hỏi-câu trả lời
    
    /**
     * Phân đoạn văn bản thành các đoạn nhỏ hơn để tạo vector embedding
     * Phương thức này chia văn bản dài thành các đoạn có kích thước phù hợp
     * để tạo vector nhúng hiệu quả, tối ưu hóa việc tìm kiếm ngữ nghĩa
     * 
     * @param text Văn bản cần phân đoạn
     * @return Danh sách các đoạn văn bản đã được phân tách
     */
    public List<String> segmentText(String text) { // Phương thức phân đoạn văn bản
        if (text == null || text.trim().isEmpty()) { // Kiểm tra nếu văn bản rỗng hoặc null
            logger.warning("Empty text provided for segmentation"); // Ghi log cảnh báo văn bản rỗng
            // Trả về một đoạn trống để đảm bảo luôn có ít nhất một đoạn
            return List.of("empty document"); // Trả về một danh sách có một phần tử là "empty document"
        }
        
        logger.info("Segmenting text of length: " + text.length() + " characters"); // Ghi log thông tin độ dài văn bản
        
        // Nếu văn bản quá ngắn, không cần phân đoạn
        if (text.length() < 500) { // Kiểm tra nếu văn bản ngắn hơn 500 ký tự
            logger.info("Text is too short, returning as a single segment"); // Ghi log thông tin văn bản quá ngắn
            return List.of(text); // Trả về văn bản gốc dưới dạng danh sách một phần tử
        }
        
        // Phân đoạn dựa trên các dòng trống
        List<String> segments = new ArrayList<>(); // Khởi tạo danh sách để lưu các đoạn văn bản
        String[] paragraphs = text.split("\\n\\s*\\n"); // Tách văn bản thành các đoạn dựa trên dòng trống
        
        StringBuilder currentSegment = new StringBuilder(); // Khởi tạo StringBuilder để xây dựng đoạn hiện tại
        
        for (String paragraph : paragraphs) { // Duyệt qua từng đoạn văn bản
            // Bỏ qua đoạn trống
            if (paragraph.trim().isEmpty()) { // Kiểm tra nếu đoạn rỗng sau khi loại bỏ khoảng trắng
                continue; // Bỏ qua đoạn rỗng
            }
            
            // Nếu đoạn hiện tại đủ dài, thêm vào danh sách và tạo đoạn mới
            if (currentSegment.length() + paragraph.length() > 1000) { // Kiểm tra nếu tổng độ dài vượt quá 1000 ký tự
                if (currentSegment.length() > 0) { // Kiểm tra nếu đoạn hiện tại không rỗng
                    segments.add(currentSegment.toString().trim()); // Thêm đoạn hiện tại vào danh sách
                    currentSegment = new StringBuilder(); // Khởi tạo đoạn mới
                }
            }
            
            // Thêm đoạn hiện tại
            if (currentSegment.length() > 0) { // Kiểm tra nếu đoạn hiện tại không rỗng
                currentSegment.append("\n\n"); // Thêm dòng trống để phân tách đoạn
            }
            currentSegment.append(paragraph.trim()); // Thêm đoạn hiện tại (đã loại bỏ khoảng trắng thừa)
        }
        
        // Thêm đoạn cuối cùng nếu còn
        if (currentSegment.length() > 0) { // Kiểm tra nếu đoạn cuối cùng không rỗng
            segments.add(currentSegment.toString().trim()); // Thêm đoạn cuối cùng vào danh sách
        }
        
        // Đảm bảo luôn có ít nhất một đoạn
        if (segments.isEmpty()) { // Kiểm tra nếu không có đoạn nào được tạo
            logger.warning("No segments created, using original text as a single segment"); // Ghi log cảnh báo
            segments.add(text); // Thêm toàn bộ văn bản gốc làm một đoạn
        }
        
        logger.info("Text segmented into " + segments.size() + " segments"); // Ghi log thông tin số lượng đoạn đã tạo
        return segments; // Trả về danh sách các đoạn
    }
    
    /**
     * Tạo câu trả lời dựa trên câu hỏi và ngữ cảnh sử dụng VertexAI API
     * Phương thức này sử dụng trí tuệ nhân tạo để tạo câu trả lời cho câu hỏi
     * dựa trên ngữ cảnh cung cấp, với khả năng sử dụng cache để tối ưu hóa
     * thời gian phản hồi
     * 
     * @param question Câu hỏi cần trả lời
     * @param context Ngữ cảnh để trả lời câu hỏi, có thể là các đoạn văn bản liên quan
     * @return Câu trả lời được tạo ra
     */
    public String generateAnswer(String question, String context) { // Phương thức tạo câu trả lời
        try {
            logger.info("Generating answer for question: " + question); // Ghi log thông tin câu hỏi
            
            // Kiểm tra cache trước tiên
            if (cacheService.hasCache(question)) { // Kiểm tra nếu câu hỏi đã có trong cache
                logger.info("Found cached answer for question: " + question); // Ghi log thông tin tìm thấy câu trả lời trong cache
                return cacheService.getCachedAnswer(question); // Trả về câu trả lời từ cache
            }
            
            if (context != null && !context.isEmpty()) { // Kiểm tra nếu có ngữ cảnh
                logger.info("Context length: " + context.length() + " characters"); // Ghi log thông tin độ dài ngữ cảnh
            } else {
                logger.info("No context provided"); // Ghi log thông tin không có ngữ cảnh
            }
            
            // Tạo prompt cho VertexAI với yêu cầu xử lý NLP
            String prompt = String.format(
                "Hãy phân tích câu hỏi và trả lời bằng tiếng Việt. " + // Phần mở đầu yêu cầu trả lời bằng tiếng Việt
                "Câu hỏi: %s\n" + // Định dạng để chèn câu hỏi
                "Thông tin tham khảo: %s\n" + // Định dạng để chèn ngữ cảnh
                "Yêu cầu: Trả lời ngắn gọn, rõ ràng, tập trung vào câu hỏi chính.", // Yêu cầu câu trả lời ngắn gọn và tập trung
                question, // Chèn câu hỏi vào prompt
                context // Chèn ngữ cảnh vào prompt
            );
            
            String answer = vertexAIService.generateText(prompt); // Gọi dịch vụ Vertex AI để tạo câu trả lời
            
            // Cache câu trả lời để sử dụng trong tương lai
            if (answer != null && !answer.isEmpty()) { // Kiểm tra nếu câu trả lời không rỗng
                cacheService.cacheAnswer(question, answer); // Lưu câu trả lời vào cache
            }
            
            logger.info("Generated answer length: " + (answer != null ? answer.length() : 0) + " characters"); // Ghi log thông tin độ dài câu trả lời
            return answer; // Trả về câu trả lời
        } catch (Exception e) { // Bắt ngoại lệ nếu có lỗi
            logger.severe("Error generating answer: " + e.getMessage()); // Ghi log lỗi
            e.printStackTrace(); // In stack trace để debug
            return "Xin lỗi, tôi không thể tạo câu trả lời lúc này."; // Trả về thông báo lỗi thân thiện với người dùng
        }
    }
    
    /**
     * Enum cho các loại câu hỏi
     * Phân loại câu hỏi để tạo câu trả lời phù hợp với ngữ cảnh và nhu cầu người dùng
     */
    private enum QuestionType { // Enum định nghĩa các loại câu hỏi
        DEFINITION,     // Định nghĩa - câu hỏi về khái niệm, ý nghĩa
        REASON,         // Lý do - câu hỏi về nguyên nhân, lý do
        METHOD,         // Phương pháp - câu hỏi về cách thức, quy trình
        COMBINATION,    // Kết hợp - câu hỏi về sự kết hợp, tương tác giữa các yếu tố
        QUANTITY,       // Số lượng - câu hỏi về số lượng, đo lường
        TIME,           // Thời gian - câu hỏi về thời điểm, khoảng thời gian
        LOCATION,       // Địa điểm - câu hỏi về vị trí, nơi chốn
        GENERAL         // Chung - các loại câu hỏi khác không thuộc các loại trên
    }
    
    /**
     * Phân tích loại câu hỏi
     * Phương thức này phân tích nội dung câu hỏi để xác định loại câu hỏi,
     * giúp tạo câu trả lời phù hợp với đặc thù của từng loại câu hỏi
     * 
     * @param question Câu hỏi cần phân tích
     * @return Loại câu hỏi được xác định
     */
    private QuestionType analyzeQuestionType(String question) { // Phương thức phân tích loại câu hỏi
        if (question.contains("là gì") || question.contains("định nghĩa") || question.contains("khái niệm")) { // Kiểm tra các từ khóa cho câu hỏi định nghĩa
            return QuestionType.DEFINITION; // Trả về loại câu hỏi định nghĩa
        } else if (question.contains("tại sao") || question.contains("lý do") || question.contains("nguyên nhân")) { // Kiểm tra các từ khóa cho câu hỏi lý do
            return QuestionType.REASON; // Trả về loại câu hỏi lý do
        } else if (question.contains("làm sao") || question.contains("làm thế nào") || question.contains("cách")) { // Kiểm tra các từ khóa cho câu hỏi phương pháp
            return QuestionType.METHOD; // Trả về loại câu hỏi phương pháp
        } else if (question.contains("ăn kèm") || question.contains("dùng với") || question.contains("kết hợp")) { // Kiểm tra các từ khóa cho câu hỏi kết hợp
            return QuestionType.COMBINATION; // Trả về loại câu hỏi kết hợp
        } else if (question.contains("bao nhiêu") || question.contains("số lượng") || question.contains("mấy")) { // Kiểm tra các từ khóa cho câu hỏi số lượng
            return QuestionType.QUANTITY; // Trả về loại câu hỏi số lượng
        } else if (question.contains("khi nào") || question.contains("lúc nào") || question.contains("thời gian")) { // Kiểm tra các từ khóa cho câu hỏi thời gian
            return QuestionType.TIME; // Trả về loại câu hỏi thời gian
        } else if (question.contains("ở đâu") || question.contains("nơi nào") || question.contains("địa điểm")) { // Kiểm tra các từ khóa cho câu hỏi địa điểm
            return QuestionType.LOCATION; // Trả về loại câu hỏi địa điểm
        } else {
            return QuestionType.GENERAL; // Trả về loại câu hỏi chung nếu không khớp với các loại trên
        }
    }
    
    /**
     * Tạo câu trả lời dựa trên loại câu hỏi
     * Phương thức này chuyển hướng quá trình tạo câu trả lời đến phương thức
     * xử lý chuyên biệt tương ứng với từng loại câu hỏi đã được phân tích
     * 
     * @param question Câu hỏi của người dùng
     * @param context Ngữ cảnh để tạo câu trả lời
     * @param questionType Loại câu hỏi đã được phân tích
     * @return Câu trả lời phù hợp với loại câu hỏi
     */
    private String generateAnswerByType(String question, String context, QuestionType questionType) { // Phương thức tạo câu trả lời dựa trên loại câu hỏi
        switch (questionType) { // Kiểm tra loại câu hỏi
            case DEFINITION: // Trường hợp câu hỏi định nghĩa
                return generateDefinitionAnswer(question, context); // Gọi phương thức tạo câu trả lời cho câu hỏi định nghĩa
            case REASON: // Trường hợp câu hỏi lý do
                return generateReasonAnswer(question, context); // Gọi phương thức tạo câu trả lời cho câu hỏi lý do
            case METHOD: // Trường hợp câu hỏi phương pháp
                return generateMethodAnswer(question, context); // Gọi phương thức tạo câu trả lời cho câu hỏi phương pháp
            case COMBINATION: // Trường hợp câu hỏi kết hợp
                return generateCombinationAnswer(question, context); // Gọi phương thức tạo câu trả lời cho câu hỏi kết hợp
            case QUANTITY: // Trường hợp câu hỏi số lượng
                return generateQuantityAnswer(question, context); // Gọi phương thức tạo câu trả lời cho câu hỏi số lượng
            case TIME: // Trường hợp câu hỏi thời gian
                return generateTimeAnswer(question, context); // Gọi phương thức tạo câu trả lời cho câu hỏi thời gian
            case LOCATION: // Trường hợp câu hỏi địa điểm
                return generateLocationAnswer(question, context); // Gọi phương thức tạo câu trả lời cho câu hỏi địa điểm
            case GENERAL: // Trường hợp câu hỏi chung
            default: // Mặc định
                return generateGeneralAnswer(question, context); // Gọi phương thức tạo câu trả lời cho câu hỏi chung
        }
    }
    
    /**
     * Tạo câu trả lời cho câu hỏi định nghĩa
     * Phương thức này trích xuất đối tượng cần định nghĩa từ câu hỏi và tìm
     * các câu liên quan trong ngữ cảnh để tạo câu trả lời mô tả khái niệm
     * 
     * @param question Câu hỏi định nghĩa cần trả lời
     * @param context Ngữ cảnh chứa thông tin để tạo câu trả lời
     * @return Câu trả lời mô tả khái niệm được hỏi
     */
    private String generateDefinitionAnswer(String question, String context) { // Phương thức tạo câu trả lời cho câu hỏi định nghĩa
        // Trích xuất đối tượng cần định nghĩa
        String subject = extractSubjectBeforePhrase(question, Arrays.asList("là gì", "định nghĩa", "khái niệm")); // Trích xuất chủ đề từ câu hỏi
        
        if (subject.isEmpty()) { // Kiểm tra nếu không trích xuất được chủ đề
            return generateGeneralAnswer(question, context); // Chuyển sang tạo câu trả lời chung nếu không xác định được chủ đề
        }
        
        // Tìm câu chứa đối tượng và từ định nghĩa
        String[] sentences = context.split("[.!?]"); // Tách ngữ cảnh thành các câu riêng biệt
        StringBuilder answer = new StringBuilder(); // Khởi tạo StringBuilder để xây dựng câu trả lời
        
        boolean foundDefinition = false; // Biến đánh dấu đã tìm thấy định nghĩa chưa
        for (String sentence : sentences) { // Duyệt qua từng câu trong ngữ cảnh
            sentence = sentence.trim(); // Loại bỏ khoảng trắng thừa
            String sentenceLower = sentence.toLowerCase(); // Chuyển câu thành chữ thường để dễ tìm kiếm
            
            if (sentenceLower.contains(subject)) { // Kiểm tra nếu câu chứa chủ đề cần định nghĩa
                if (sentenceLower.contains(" là ") || sentenceLower.contains("được gọi") || 
                    sentenceLower.contains("định nghĩa") || sentenceLower.contains("khái niệm")) { // Kiểm tra nếu câu chứa từ khóa định nghĩa
                    answer.append(capitalizeFirstLetter(sentence)).append(". "); // Thêm câu vào câu trả lời với chữ cái đầu viết hoa
                    foundDefinition = true; // Đánh dấu đã tìm thấy định nghĩa
                } else if (!foundDefinition) { // Nếu chưa tìm thấy định nghĩa
                    answer.append(capitalizeFirstLetter(sentence)).append(". "); // Thêm câu chứa chủ đề vào câu trả lời
                }
            }
        }
        
        // Nếu không tìm thấy câu định nghĩa cụ thể, sử dụng tất cả các câu có chứa đối tượng
        if (answer.length() == 0) { // Kiểm tra nếu chưa có câu trả lời
            for (String sentence : sentences) { // Duyệt lại từng câu trong ngữ cảnh
                sentence = sentence.trim(); // Loại bỏ khoảng trắng thừa
                if (sentence.toLowerCase().contains(subject)) { // Kiểm tra nếu câu chứa chủ đề
                    answer.append(capitalizeFirstLetter(sentence)).append(". "); // Thêm câu vào câu trả lời
                }
            }
        }
        
        // Nếu vẫn không tìm thấy, trả về câu trả lời chung
        if (answer.length() == 0) { // Kiểm tra nếu vẫn chưa có câu trả lời
            return generateGeneralAnswer(question, context); // Trả về câu trả lời chung
        }
        
        return answer.toString().trim(); // Trả về câu trả lời đã được xây dựng, loại bỏ khoảng trắng thừa
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
     * Trích xuất chủ đề xuất hiện trước cụm từ chỉ định trong câu hỏi
     * Phương thức này tìm và trích xuất phần nội dung xuất hiện trước các cụm từ
     * được chỉ định, sau đó loại bỏ các từ dừng không mang ý nghĩa từ kết quả
     * 
     * @param question Câu hỏi cần trích xuất chủ đề
     * @param phrases Danh sách các cụm từ đánh dấu vị trí chủ đề
     * @return Chủ đề đã được trích xuất và làm sạch
     */
    private String extractSubjectBeforePhrase(String question, List<String> phrases) { // Phương thức trích xuất chủ đề trước cụm từ
        for (String phrase : phrases) { // Duyệt qua từng cụm từ trong danh sách
            int index = question.toLowerCase().indexOf(phrase); // Tìm vị trí của cụm từ trong câu hỏi
            if (index > 0) { // Nếu tìm thấy cụm từ và có nội dung trước nó
                String subject = question.substring(0, index).trim(); // Cắt lấy phần trước cụm từ
                return removeTrailingStopWords(subject); // Loại bỏ các từ dừng ở cuối và trả về
            }
        }
        return ""; // Trả về chuỗi rỗng nếu không trích xuất được
    }
    
    /**
     * Trích xuất chủ đề xuất hiện sau cụm từ chỉ định trong câu hỏi
     * Phương thức này tìm và trích xuất phần nội dung xuất hiện sau các cụm từ
     * được chỉ định, sau đó loại bỏ các từ dừng không mang ý nghĩa từ kết quả
     * 
     * @param question Câu hỏi cần trích xuất chủ đề
     * @param phrases Danh sách các cụm từ đánh dấu vị trí chủ đề
     * @return Chủ đề đã được trích xuất và làm sạch
     */
    private String extractSubjectAfterPhrase(String question, List<String> phrases) { // Phương thức trích xuất chủ đề sau cụm từ
        for (String phrase : phrases) { // Duyệt qua từng cụm từ trong danh sách
            int index = question.toLowerCase().indexOf(phrase); // Tìm vị trí của cụm từ trong câu hỏi
            if (index >= 0) { // Nếu tìm thấy cụm từ
                int start = index + phrase.length(); // Tính vị trí bắt đầu của chủ đề (sau cụm từ)
                if (start < question.length()) { // Kiểm tra xem vị trí bắt đầu có hợp lệ không
                    String subject = question.substring(start).trim(); // Cắt lấy phần sau cụm từ
                    return removeLeadingStopWords(subject); // Loại bỏ các từ dừng ở đầu và trả về
                }
            }
        }
        return ""; // Trả về chuỗi rỗng nếu không trích xuất được
    }
    
    /**
     * Loại bỏ các từ dừng (stop words) ở cuối chuỗi
     * Các từ dừng thường là các từ phụ không mang nhiều ý nghĩa ngữ nghĩa
     * như "của", "và", "là", "để", v.v.
     * 
     * @param text Chuỗi cần xử lý
     * @return Chuỗi đã loại bỏ các từ dừng ở cuối
     */
    private String removeTrailingStopWords(String text) { // Phương thức loại bỏ từ dừng ở cuối
        String[] stopWords = {"của", "và", "là", "với", "để", "như", "thì", "mà", "những", "các", "về"}; // Danh sách từ dừng
        String result = text.trim(); // Chuỗi kết quả, bắt đầu bằng chuỗi gốc đã cắt khoảng trắng thừa
        
        for (String stopWord : stopWords) { // Duyệt qua từng từ dừng
            if (result.toLowerCase().endsWith(" " + stopWord)) { // Kiểm tra xem chuỗi có kết thúc bằng từ dừng không
                result = result.substring(0, result.length() - stopWord.length() - 1).trim(); // Loại bỏ từ dừng ở cuối
            }
        }
        
        return result; // Trả về chuỗi đã loại bỏ từ dừng
    }
    
    /**
     * Loại bỏ các từ dừng (stop words) ở đầu chuỗi
     * Các từ dừng thường là các từ phụ không mang nhiều ý nghĩa ngữ nghĩa
     * như "về", "của", "là", "có", v.v.
     * 
     * @param text Chuỗi cần xử lý
     * @return Chuỗi đã loại bỏ các từ dừng ở đầu
     */
    private String removeLeadingStopWords(String text) { // Phương thức loại bỏ từ dừng ở đầu
        String[] stopWords = {"về", "của", "là", "có", "một", "những", "các", "và", "với", "để", "như"}; // Danh sách từ dừng
        String result = text.trim(); // Chuỗi kết quả, bắt đầu bằng chuỗi gốc đã cắt khoảng trắng thừa
        
        for (String stopWord : stopWords) { // Duyệt qua từng từ dừng
            if (result.toLowerCase().startsWith(stopWord + " ")) { // Kiểm tra xem chuỗi có bắt đầu bằng từ dừng không
                result = result.substring(stopWord.length() + 1).trim(); // Loại bỏ từ dừng ở đầu
            }
        }
        
        return result; // Trả về chuỗi đã loại bỏ từ dừng
    }
    
    /**
     * Viết hoa chữ cái đầu tiên của chuỗi
     * Phương thức này đảm bảo rằng chuỗi bắt đầu bằng một chữ cái viết hoa,
     * giúp định dạng văn bản trong câu trả lời được chuẩn mực
     * 
     * @param text Chuỗi cần xử lý
     * @return Chuỗi đã được viết hoa chữ cái đầu tiên
     */
    private String capitalizeFirstLetter(String text) { // Phương thức viết hoa chữ cái đầu
        if (text == null || text.isEmpty()) { // Kiểm tra nếu chuỗi rỗng hoặc null
            return text; // Trả về chuỗi ban đầu
        }
        
        return Character.toUpperCase(text.charAt(0)) + text.substring(1); // Viết hoa chữ cái đầu và nối với phần còn lại
    }
    
    /**
     * Tái tạo tất cả các vector nhúng cho danh sách tài liệu
     * Phương thức này được sử dụng khi cần cập nhật lại toàn bộ vector
     * nhúng, ví dụ khi mô hình đã thay đổi hoặc cần cải thiện chất lượng vector
     * 
     * @param documents Danh sách tài liệu cần tái tạo vector
     * @param vectorService Dịch vụ xử lý vector
     * @param embeddingRepository Repository lưu trữ vector nhúng
     * @return Số lượng vector đã được tái tạo
     */
    @Transactional // Đảm bảo tính toàn vẹn của giao dịch với cơ sở dữ liệu
    public int reinitializeAllVectors(List<Document> documents, VectorService vectorService, EmbeddingRepository embeddingRepository) { // Phương thức tái tạo vector nhúng
        logger.info("Bắt đầu tái tạo vector nhúng cho " + documents.size() + " tài liệu"); // Ghi log thông tin về số lượng tài liệu
        
        int totalDocuments = documents.size(); // Tổng số tài liệu cần xử lý
        int processedDocuments = 0; // Số lượng tài liệu đã xử lý
        int totalVectorsCreated = 0; // Tổng số vector đã tạo
        
        try {
            // Xóa tất cả vector nhúng hiện có
            logger.info("Xóa tất cả vector nhúng hiện có"); // Ghi log thông tin bắt đầu xóa vector cũ
            embeddingRepository.deleteAll(); // Xóa tất cả vector nhúng từ cơ sở dữ liệu
            
            // Duyệt qua từng tài liệu và tạo lại vector nhúng
            for (Document document : documents) { // Duyệt qua từng tài liệu trong danh sách
                String content = document.getContent(); // Lấy nội dung của tài liệu
                Long documentId = document.getId(); // Lấy ID của tài liệu
                
                try {
                    // Phân đoạn nội dung
                    List<String> segments = segmentText(content); // Phân đoạn nội dung thành các đoạn nhỏ hơn
                    logger.info("Tài liệu " + documentId + " được phân thành " + segments.size() + " đoạn"); // Ghi log thông tin số đoạn
                    
                    // Tạo vector nhúng cho từng đoạn
                    for (int i = 0; i < segments.size(); i++) { // Duyệt qua từng đoạn
                        String segment = segments.get(i); // Lấy nội dung đoạn
                        
                        // Tạo vector nhúng cho đoạn văn bản
                        float[] embedding = vectorService.createEmbedding(segment); // Tạo vector nhúng mới
                        
                        if (embedding != null) { // Kiểm tra nếu vector được tạo thành công
                            // Lưu vector nhúng vào cơ sở dữ liệu
                            EmbeddingVector vector = new EmbeddingVector();
                            vector.setDocumentId(documentId);
                            vector.setSegment(segment);
                            vector.setVectorData(embedding);
                            embeddingRepository.save(vector);
                            
                            totalVectorsCreated++; // Tăng số lượng vector đã tạo
                            
                            // Ghi log tiến trình sau mỗi 10 vector
                            if (totalVectorsCreated % 10 == 0) { // Kiểm tra số vector đã tạo để ghi log định kỳ
                                logger.info("Đã tạo " + totalVectorsCreated + " vector nhúng, đang xử lý tài liệu " + 
                                    processedDocuments + "/" + totalDocuments); // Ghi log tiến trình
                            }
                        }
                        
                        // Tạm dừng giữa các lần tạo vector để tránh quá tải API
                        if (i < segments.size() - 1) { // Nếu không phải đoạn cuối cùng
                            try {
                                TimeUnit.MILLISECONDS.sleep(50); // Tạm dừng 50ms giữa các lần tạo vector
                            } catch (InterruptedException e) { // Bắt ngoại lệ nếu bị gián đoạn
                                Thread.currentThread().interrupt(); // Đánh dấu luồng hiện tại bị gián đoạn
                                logger.warning("Quá trình tạo vector bị gián đoạn: " + e.getMessage()); // Ghi log cảnh báo
                            }
                        }
                    }
                    
                    processedDocuments++; // Tăng số lượng tài liệu đã xử lý
                    
                } catch (Exception e) { // Bắt ngoại lệ khi xử lý một tài liệu
                    logger.severe("Lỗi khi tạo vector nhúng cho tài liệu " + documentId + ": " + e.getMessage()); // Ghi log lỗi
                    e.printStackTrace(); // In stack trace để debug
                }
            }
            
            logger.info("Hoàn tất tái tạo vector nhúng. Đã tạo tổng cộng " + totalVectorsCreated + 
                " vector cho " + processedDocuments + "/" + totalDocuments + " tài liệu"); // Ghi log kết quả cuối cùng
            
            return totalVectorsCreated; // Trả về tổng số vector đã tạo
            
        } catch (Exception e) { // Bắt ngoại lệ tổng quát
            logger.severe("Lỗi trong quá trình tái tạo vector nhúng: " + e.getMessage()); // Ghi log lỗi tổng quát
            e.printStackTrace(); // In stack trace để debug
            return 0; // Trả về 0 nếu xảy ra lỗi
        }
    }
    
    /**
     * Trích xuất từ khóa từ câu hỏi
     * Phương thức này loại bỏ các từ dừng và các từ phổ biến
     * để xác định các từ khóa quan trọng trong câu hỏi
     * 
     * @param question Câu hỏi cần trích xuất từ khóa
     * @return Danh sách các từ khóa đã trích xuất
     */
    public List<String> extractKeywords(String question) { // Phương thức trích xuất từ khóa
        List<String> keywords = new ArrayList<>(); // Khởi tạo danh sách để lưu từ khóa
        
        // Loại bỏ các từ dừng (stop words)
        String cleanedQuestion = removeStopWords(question.toLowerCase()); // Loại bỏ từ dừng và chuyển thành chữ thường
        
        // Tách các từ
        String[] words = cleanedQuestion.split("\\s+"); // Tách câu hỏi thành các từ riêng biệt
        
        // Lọc các từ quá ngắn và các từ phổ biến
        for (String word : words) { // Duyệt qua từng từ
            word = word.trim(); // Loại bỏ khoảng trắng thừa
            
            // Bỏ qua các từ quá ngắn và các từ phổ biến
            if (word.length() > 2 && !isCommonWord(word)) { // Kiểm tra độ dài từ và tính phổ biến
                keywords.add(word); // Thêm từ khóa vào danh sách
            }
        }
        
        return keywords; // Trả về danh sách từ khóa
    }
    
    /**
     * Kiểm tra xem một từ có phải là từ phổ biến không
     * Các từ phổ biến thường là các từ xuất hiện nhiều trong ngôn ngữ
     * nhưng không mang nhiều ý nghĩa phân biệt
     * 
     * @param word Từ cần kiểm tra
     * @return true nếu từ là từ phổ biến, ngược lại là false
     */
    private boolean isCommonWord(String word) { // Phương thức kiểm tra từ phổ biến
        // Danh sách các từ phổ biến trong tiếng Việt
        String[] commonWords = { // Mảng chứa các từ phổ biến
            "và", "hoặc", "thì", "là", "được", "có", "không", "của", "này", "đó", 
            "trong", "ngoài", "trên", "dưới", "cho", "tại", "bởi", "vì", "nhưng", "mà",
            "nếu", "khi", "lúc", "từ", "đến", "về", "theo", "bạn", "tôi", "chúng",
            "họ", "nó", "đang", "sẽ", "đã", "rồi", "xong", "sau", "trước", "với",
            "cùng", "qua", "lại", "vào", "ra", "thế", "vậy", "như", "làm", "nên", 
            "hay", "vẫn", "còn", "cũng"
        };
        
        for (String commonWord : commonWords) { // Duyệt qua từng từ phổ biến
            if (commonWord.equals(word)) { // So sánh từ cần kiểm tra với từ phổ biến
                return true; // Trả về true nếu từ là từ phổ biến
            }
        }
        
        return false; // Trả về false nếu từ không phải là từ phổ biến
    }
    
    /**
     * Loại bỏ các từ dừng từ văn bản
     * Phương thức này loại bỏ các từ dừng và dấu câu từ văn bản
     * để làm sạch nội dung trước khi xử lý thêm
     * 
     * @param text Văn bản cần làm sạch
     * @return Văn bản đã được làm sạch
     */
    private String removeStopWords(String text) { // Phương thức loại bỏ từ dừng khỏi văn bản
        // Danh sách từ dừng trong tiếng Việt
        String[] stopWords = { // Mảng chứa các từ dừng
            "và", "hoặc", "thì", "là", "được", "có", "không", "của", "này", "đó", 
            "trong", "ngoài", "trên", "dưới", "cho", "tại", "bởi", "vì", "nhưng", "mà",
            "nếu", "khi", "lúc", "từ", "đến", "về", "theo", "bạn", "tôi", "chúng",
            "họ", "nó", "đang", "sẽ", "đã", "rồi", "xong", "sau", "trước", "với",
            "cùng", "qua", "lại", "vào", "ra", "thế", "vậy", "như", "làm", "nên", 
            "hay", "vẫn", "còn", "cũng"
        };
        
        // Loại bỏ dấu câu
        text = text.replaceAll("[,.?!;:]", " "); // Thay thế các dấu câu bằng khoảng trắng
        
        // Loại bỏ từ dừng
        String[] words = text.split("\\s+"); // Tách văn bản thành các từ
        StringBuilder result = new StringBuilder(); // Khởi tạo StringBuilder để xây dựng kết quả
        
        for (String word : words) { // Duyệt qua từng từ
            boolean isStopWord = false; // Biến đánh dấu từ có phải từ dừng không
            
            for (String stopWord : stopWords) { // Duyệt qua từng từ dừng
                if (stopWord.equals(word)) { // So sánh từ hiện tại với từ dừng
                    isStopWord = true; // Đánh dấu là từ dừng
                    break; // Thoát vòng lặp nếu tìm thấy
                }
            }
            
            if (!isStopWord && !word.isEmpty()) { // Nếu không phải từ dừng và không rỗng
                result.append(word).append(" "); // Thêm từ vào kết quả
            }
        }
        
        return result.toString().trim(); // Trả về kết quả, loại bỏ khoảng trắng thừa
    }
} 