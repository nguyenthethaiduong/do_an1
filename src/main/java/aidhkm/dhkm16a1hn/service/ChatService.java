package aidhkm.dhkm16a1hn.service; // Khai báo package cho lớp dịch vụ

import aidhkm.dhkm16a1hn.model.*; // Import tất cả các model
import aidhkm.dhkm16a1hn.repository.ChatHistoryRepository; // Import repository lưu trữ lịch sử chat
import aidhkm.dhkm16a1hn.repository.EmbeddingRepository; // Import repository lưu trữ các vector nhúng
import aidhkm.dhkm16a1hn.repository.QuestionRepository; // Import repository lưu trữ câu hỏi
import aidhkm.dhkm16a1hn.util.VectorUtil; // Import tiện ích xử lý vector
import jakarta.annotation.PostConstruct; // Import annotation để đánh dấu phương thức khởi tạo sau khi bean được tạo
import lombok.extern.slf4j.Slf4j; // Import annotation để tạo logger
import org.springframework.beans.factory.annotation.Autowired; // Import annotation để tiêm phụ thuộc tự động
import org.springframework.scheduling.annotation.Async; // Import annotation để đánh dấu phương thức bất đồng bộ
import org.springframework.stereotype.Service; // Import annotation để đánh dấu lớp dịch vụ

import java.time.LocalDateTime; // Import lớp đại diện cho ngày giờ
import java.util.*; // Import tất cả các lớp tiện ích
import java.util.concurrent.*; // Import tất cả các lớp xử lý đồng thời
import java.util.regex.Pattern; // Import lớp mẫu biểu thức chính quy
import java.util.stream.Collectors; // Import lớp tiện ích xử lý luồng dữ liệu

@Service // Đánh dấu lớp này là một dịch vụ Spring để Spring container quản lý
@Slf4j // Tự động tạo logger cho lớp này sử dụng Lombok
public class ChatService { // Khai báo lớp dịch vụ xử lý chat - lớp chính để xử lý các tương tác chat với người dùng

    @Autowired // Tiêm phụ thuộc tự động từ Spring container
    private QuestionRepository questionRepository; // Repository để truy vấn và lưu trữ dữ liệu câu hỏi vào database

    @Autowired // Tiêm phụ thuộc tự động từ Spring container
    private EmbeddingRepository embeddingRepository; // Repository để truy vấn và lưu trữ dữ liệu vector nhúng

    @Autowired // Tiêm phụ thuộc tự động từ Spring container
    private ChatHistoryRepository chatHistoryRepository; // Repository để truy vấn và lưu trữ lịch sử chat

    @Autowired // Tiêm phụ thuộc tự động từ Spring container
    private VertexAIService vertexAIService; // Dịch vụ tích hợp với Vertex AI để sử dụng mô hình ngôn ngữ

    @Autowired // Tiêm phụ thuộc tự động từ Spring container
    private VectorService vectorService; // Dịch vụ xử lý vector, tạo nhúng và tìm kiếm tương tự

    private static final int MAX_ANSWER_LENGTH = 4000; // Độ dài tối đa của câu trả lời, giới hạn để tránh trả lời quá dài
    private static final int MAX_SIMILAR_SENTENCES = 10; // Số lượng câu tương tự tối đa để truy vấn, tối ưu hóa hiệu suất
    private static final int MAX_CACHE_SIZE = 100; // Kích thước tối đa của bộ nhớ đệm, giới hạn để tránh dùng quá nhiều bộ nhớ
    public static final String NO_INFORMATION_MESSAGE = "Không tìm thấy thông tin liên quan trong cơ sở dữ liệu."; // Thông báo khi không tìm thấy thông tin, hằng số có thể được sử dụng từ bên ngoài lớp

    // Bộ nhớ đệm cho việc tối ưu hóa câu trả lời - sử dụng LinkedHashMap với cơ chế LRU (Least Recently Used)
    private final Map<String, String> responseCache = Collections.synchronizedMap(new LinkedHashMap<String, String>(MAX_CACHE_SIZE + 1, 0.75f, true) { // Tạo bộ nhớ đệm đồng bộ từ LinkedHashMap với chức năng loại bỏ phần tử ít sử dụng nhất
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) { // Phương thức tự động loại bỏ phần tử cũ nhất khi map đạt đến kích thước tối đa
            return size() > MAX_CACHE_SIZE; // Trả về true nếu kích thước vượt quá giới hạn, kích hoạt việc xóa phần tử cũ nhất
        }
    });

    // Pool luồng cho xử lý bất đồng bộ để cải thiện hiệu suất xử lý câu hỏi
    private final ExecutorService threadPool = Executors.newFixedThreadPool(5); // Tạo pool với 5 luồng cố định để thực hiện các tác vụ bất đồng bộ

    // Các câu trả lời hội thoại cho các cụm từ đơn giản - phân loại theo ngôn ngữ
    private final Map<String, List<String>> conversationalResponsesVi = new HashMap<>(); // Danh sách câu trả lời tiếng Việt cho các câu hỏi/cụm từ đơn giản
    private final Map<String, List<String>> conversationalResponsesEn = new HashMap<>(); // Danh sách câu trả lời tiếng Anh cho các câu hỏi/cụm từ đơn giản

    @PostConstruct // Phương thức sẽ được gọi sau khi đối tượng được tạo
    public void init() { // Khởi tạo dịch vụ khi bean được tạo
        // Khởi tạo các câu trả lời hội thoại
        initializeConversationalResponses(); // Gọi phương thức khởi tạo các câu trả lời hội thoại đơn giản
    }

    /**
     * Xử lý câu hỏi từ người dùng và tạo câu trả lời.
     * Phương thức này cho phép gọi mà không cần documentId.
     *
     * @param question Câu hỏi của người dùng.
     * @return Câu trả lời cho câu hỏi.
     */
    public String processQuestion(String question) { // Phương thức xử lý câu hỏi không cần documentId - phương thức overload đơn giản hơn
        return processQuestion(question, null); // Gọi phương thức chính với documentId là null để xử lý câu hỏi không liên quan đến tài liệu cụ thể
    }

    /**
     * Xử lý câu hỏi từ người dùng và tạo câu trả lời.
     * Đây là phương thức chính để xử lý câu hỏi với tài liệu cụ thể.
     *
     * @param question Câu hỏi của người dùng.
     * @param documentId ID của tài liệu liên quan đến câu hỏi (có thể null).
     * @return Câu trả lời cho câu hỏi.
     */
    public String processQuestion(String question, Long documentId) { // Phương thức chính xử lý câu hỏi với documentId
        long startTime = System.currentTimeMillis(); // Ghi lại thời điểm bắt đầu để tính thời gian xử lý - phục vụ mục đích đánh giá hiệu suất
        String normalizedQuestion = normalizeQuestion(question); // Chuẩn hóa câu hỏi đầu vào bằng cách loại bỏ ký tự đặc biệt và định dạng thống nhất

        // Kiểm tra câu hỏi rỗng để tránh xử lý không cần thiết
        if (normalizedQuestion.isEmpty()) { // Nếu câu hỏi rỗng sau khi chuẩn hóa
            return "Vui lòng nhập câu hỏi."; // Trả về thông báo yêu cầu nhập câu hỏi thay vì xử lý tiếp
        }

        log.debug("Processing question: {}", normalizedQuestion); // Ghi log câu hỏi đang xử lý để theo dõi và gỡ lỗi

        // Kiểm tra câu trả lời hội thoại trước - đường dẫn nhanh cho các tương tác đơn giản
        String conversationalResponse = getConversationalResponse(normalizedQuestion); // Thử lấy câu trả lời hội thoại đơn giản như "xin chào", "cảm ơn"
        if (conversationalResponse != null) { // Nếu có câu trả lời hội thoại
            log.debug("Found conversational response for: {}", normalizedQuestion); // Ghi log đã tìm thấy câu trả lời hội thoại
            return conversationalResponse; // Trả về câu trả lời hội thoại ngay lập tức, không cần xử lý phức tạp
        }

        // Kiểm tra bộ nhớ đệm cho câu trả lời đã tồn tại để tối ưu hiệu suất
        String cachedResponse = responseCache.get(normalizedQuestion); // Thử lấy câu trả lời từ bộ nhớ đệm
        if (cachedResponse != null) { // Nếu có câu trả lời trong bộ nhớ đệm
            log.debug("Cache hit for question: {}", normalizedQuestion); // Ghi log đã tìm thấy trong bộ nhớ đệm
            return cachedResponse; // Trả về câu trả lời từ bộ nhớ đệm mà không cần xử lý lại
        }

        try {
            // Tìm kiếm các câu hỏi tương tự trong cơ sở dữ liệu sử dụng CompletableFuture để xử lý bất đồng bộ
            CompletableFuture<List<QuestionMatch>> similarQuestionsFuture = CompletableFuture.supplyAsync(() -> { // Tạo future để tìm kiếm câu hỏi tương tự bất đồng bộ
                try {
                    return findSimilarQuestions(normalizedQuestion); // Tìm các câu hỏi tương tự từ cơ sở dữ liệu
                } catch (Exception e) { // Bắt ngoại lệ nếu có
                    log.error("Error finding similar questions: {}", e.getMessage()); // Ghi log lỗi
                    return Collections.emptyList(); // Trả về danh sách rỗng nếu lỗi để tiếp tục xử lý
                }
            }, threadPool); // Sử dụng thread pool đã định nghĩa để thực hiện công việc

            // Tìm kiếm các câu tương tự trong cơ sở dữ liệu vector bất đồng bộ - phương pháp thứ hai
            CompletableFuture<List<String>> similarSentencesFuture = CompletableFuture.supplyAsync(() -> { // Tạo future để tìm các câu tương tự bất đồng bộ
                try {
                    List<String> sentences = vectorService.searchSimilarSentences(normalizedQuestion, MAX_SIMILAR_SENTENCES); // Tìm câu tương tự từ vector DB
                    log.debug("Found {} similar sentences for question: {}", sentences.size(), normalizedQuestion); // Ghi log số câu tìm được
                    return sentences; // Trả về các câu tương tự
                } catch (Exception e) { // Bắt ngoại lệ nếu có
                    log.error("Error searching similar sentences: {}", e.getMessage()); // Ghi log lỗi
                    return Collections.emptyList(); // Trả về danh sách rỗng nếu lỗi để tiếp tục xử lý
                }
            }, threadPool); // Sử dụng thread pool đã định nghĩa để thực hiện công việc

            // Chờ cả hai future hoàn thành với timeout để tránh chờ đợi quá lâu
            List<QuestionMatch> similarQuestions = similarQuestionsFuture.get(5, TimeUnit.SECONDS); // Đợi kết quả tìm câu hỏi với timeout 5 giây
            List<String> similarSentences = similarSentencesFuture.get(10, TimeUnit.SECONDS); // Đợi kết quả tìm câu với timeout 10 giây

            // Xử lý kết quả và tạo câu trả lời
            String answer = ""; // Khởi tạo biến câu trả lời
            if (!similarQuestions.isEmpty()) { // Nếu tìm thấy câu hỏi tương tự
                // Sử dụng câu trả lời của câu hỏi tương tự nhất - ưu tiên câu trả lời có sẵn
                QuestionMatch bestMatch = similarQuestions.get(0); // Lấy câu hỏi có độ tương tự cao nhất (đầu tiên trong danh sách đã sắp xếp)
                log.debug("Using answer from similar question with score {}: {}", bestMatch.getScore(), bestMatch.getQuestionText()); // Ghi log
                answer = bestMatch.getAnswerText(); // Sử dụng câu trả lời có sẵn từ câu hỏi tương tự
            } else if (!similarSentences.isEmpty()) { // Nếu không có câu hỏi tương tự nhưng có câu tương tự từ vector search
                // Sử dụng kết quả tìm kiếm vector để tạo câu trả lời mới
                log.debug("Found {} similar sentences, generating answer", similarSentences.size()); // Ghi log số câu tương tự
                answer = generateAnswerFromSimilarSentences(normalizedQuestion, similarSentences); // Tạo câu trả lời mới từ các câu tương tự

                // Lưu câu hỏi và câu trả lời cho sử dụng sau này để cải thiện hệ thống
                if (!answer.equals(NO_INFORMATION_MESSAGE) && documentId != null) { // Nếu có câu trả lời hợp lệ và có documentId
                    saveQuestionAnswer(normalizedQuestion, answer, documentId); // Lưu câu hỏi và trả lời vào DB để tái sử dụng sau này
                }
            } else { // Nếu không tìm thấy câu hoặc câu hỏi tương tự
                answer = NO_INFORMATION_MESSAGE; // Trả về thông báo không tìm thấy thông tin
            }

            // Lưu trữ câu trả lời vào bộ nhớ đệm nếu không phải là thông báo "không có thông tin" mặc định
            if (!answer.equals(NO_INFORMATION_MESSAGE) && responseCache.size() < MAX_CACHE_SIZE) { // Nếu câu trả lời hợp lệ và bộ đệm chưa đầy
                responseCache.put(normalizedQuestion, answer); // Thêm vào bộ nhớ đệm để sử dụng lần sau
            }

            // Duy trì kích thước bộ nhớ đệm - đoạn này có vẻ thừa vì đã có cơ chế tự loại bỏ phần tử cũ trong LinkedHashMap
            if (responseCache.size() > MAX_CACHE_SIZE) { // Nếu bộ nhớ đệm vượt quá kích thước tối đa
                // Xóa một mục ngẫu nhiên - chính sách loại bỏ đơn giản
                String keyToRemove = responseCache.keySet().iterator().next(); // Lấy phần tử đầu tiên
                responseCache.remove(keyToRemove); // Xóa phần tử đó
            }

            long processingTime = System.currentTimeMillis() - startTime; // Tính thời gian xử lý từ lúc bắt đầu đến khi hoàn thành
            log.debug("Question processed in {}ms: {}", processingTime, normalizedQuestion); // Ghi log thời gian xử lý

            return answer; // Trả về câu trả lời cuối cùng
        } catch (Exception e) { // Bắt ngoại lệ chung nếu có lỗi trong quá trình xử lý
            log.error("Error processing question: {}", e.getMessage(), e); // Ghi log lỗi chi tiết
            return "Đã xảy ra lỗi khi xử lý câu hỏi. Vui lòng thử lại."; // Trả về thông báo lỗi thân thiện với người dùng
        }
    }

    /**
     * Lấy câu trả lời hội thoại cho các cụm từ đơn giản
     * @param question Câu hỏi của người dùng
     * @return Câu trả lời hội thoại hoặc null nếu không tìm thấy
     */
    private String getConversationalResponse(String question) { // Phương thức lấy câu trả lời hội thoại đơn giản
        // Kiểm tra khớp chính xác từ danh sách câu trả lời
        if (conversationalResponsesVi.containsKey(question)) { // Nếu câu hỏi có trong danh sách câu trả lời tiếng Việt
            List<String> responses = conversationalResponsesVi.get(question); // Lấy danh sách câu trả lời
            int randomIndex = (int) (Math.random() * responses.size()); // Chọn ngẫu nhiên một chỉ số
            return responses.get(randomIndex); // Trả về câu trả lời ngẫu nhiên
        }

        // Kiểm tra xem câu hỏi có phải là cụm từ hội thoại không
        if (question.length() < 10) { // Nếu câu hỏi ngắn (ít hơn 10 ký tự)
            if (containsAcknowledgmentPhrase(question)) { // Kiểm tra nếu chứa cụm từ xác nhận
                return getRandomAcknowledgementResponse(); // Trả về câu trả lời xác nhận ngẫu nhiên
            }
        }

        return null; // Không tìm thấy câu trả lời hội thoại phù hợp
    }

    /**
     * Kiểm tra xem văn bản có phải là câu xác nhận không
     * @param text Văn bản cần kiểm tra
     * @return true nếu là câu xác nhận, ngược lại là false
     */
    private boolean isAcknowledgment(String text) { // Phương thức kiểm tra câu xác nhận
        // Danh sách các từ khóa xác nhận
        String[] acknowledgments = { // Mảng các từ xác nhận phổ biến
            "ok", "okay", "được", "được rồi", "tốt", "tốt rồi", 
            "hiểu rồi", "rõ", "rõ rồi", "cảm ơn", "cám ơn", "thanks", 
            "đã hiểu", "tôi hiểu rồi", "vâng", "đúng", "đúng rồi", 
            "sure", "yes", "yeah", "yep", "got it", "understood",
            "i see", "clear", "perfect", "great", "excellent",
            "ko", "không", "khỏi", "không cần", "thôi", "đừng", "dừng"
        };

        for (String ack : acknowledgments) { // Duyệt qua mỗi từ xác nhận
            if (text.equals(ack) || text.startsWith(ack + " ") || text.endsWith(" " + ack)) { // Kiểm tra nếu văn bản khớp với từ xác nhận
                return true; // Là câu xác nhận
            }
        }

        return false; // Không phải câu xác nhận
    }

    /**
     * Trả về câu trả lời ngẫu nhiên cho câu xác nhận
     * @return Câu trả lời xác nhận ngẫu nhiên
     */
    private String getAcknowledgmentResponse() { // Phương thức lấy câu trả lời cho câu xác nhận
        String[] responses = { // Mảng các câu trả lời xác nhận
            "Vâng, tôi luôn sẵn sàng hỗ trợ bạn.",
            "Rất vui khi được giúp đỡ bạn.",
            "Bạn cần hỏi thêm điều gì không?",
            "Tôi có thể giúp gì thêm cho bạn?",
            "Vâng, hãy cho tôi biết nếu bạn cần thêm thông tin.",
            "Bạn có thắc mắc gì khác không?",
            "Tôi rất vui khi bạn hài lòng với câu trả lời."
        };

        int randomIndex = (int) (Math.random() * responses.length);
        return responses[randomIndex]; // Trả về câu trả lời ngẫu nhiên
    }

    /**
     * Trích xuất các câu liên quan trực tiếp đến câu hỏi
     * Phương thức này tìm các câu trong ngữ cảnh có chứa từ khóa từ câu hỏi
     * 
     * @param question Câu hỏi cần tìm câu liên quan
     * @param context Ngữ cảnh chứa các câu
     * @return Các câu liên quan đến câu hỏi
     */
    private String extractRelevantSentences(String question, String context) { // Phương thức trích xuất các câu liên quan đến câu hỏi
        try {
            if (context.length() <= MAX_ANSWER_LENGTH) { // Nếu ngữ cảnh đã ngắn hơn độ dài tối đa
                return context; // Trả về toàn bộ ngữ cảnh
            }

            // Tách ngữ cảnh thành các câu riêng biệt
            String[] sentences = context.split("\\. |\\? |\\! |\\n"); // Tách ngữ cảnh bằng các dấu câu và xuống dòng

            // Lấy các câu liên quan nhất dựa trên sự khớp từ khóa
            List<String> relevantSentences = new ArrayList<>(); // Danh sách chứa các câu liên quan
            String[] questionWords = question.toLowerCase().split("\\s+"); // Tách câu hỏi thành các từ riêng biệt

            for (String sentence : sentences) {
                String lowercaseSentence = sentence.toLowerCase();
                int matchCount = 0;

                for (String word : questionWords) {
                    if (word.length() > 3 && lowercaseSentence.contains(word)) {
                        matchCount++;
                    }
                }

                if (matchCount > 0) {
                    relevantSentences.add(sentence);
                }
            }

            // If we found relevant sentences, join them
            if (!relevantSentences.isEmpty()) {
                return String.join(". ", relevantSentences);
            }

            // Otherwise return a truncated context
            return context.substring(0, Math.min(context.length(), MAX_ANSWER_LENGTH));
        } catch (Exception e) {
            log.error("Error extracting relevant sentences: {}", e.getMessage());
            return context;
        }
    }

    /**
     * Kiểm tra câu trả lời không hợp lệ
     * Phương thức này kiểm tra xem câu trả lời có hợp lệ không dựa trên nhiều tiêu chí
     * 
     * @param answer Câu trả lời cần kiểm tra
     * @return true nếu câu trả lời không hợp lệ, ngược lại là false
     */
    private boolean isInvalidAnswer(String answer) { // Phương thức kiểm tra tính hợp lệ của câu trả lời
        if (answer == null || answer.trim().isEmpty()) { // Kiểm tra nếu câu trả lời là null hoặc rỗng sau khi loại bỏ khoảng trắng
            return true; // Trả về true (không hợp lệ) nếu câu trả lời rỗng
        }

        String lowercaseAnswer = answer.toLowerCase(); // Chuyển câu trả lời thành chữ thường để tìm kiếm
        return lowercaseAnswer.contains("no relevant information") || // Kiểm tra nếu chứa cụm từ tiếng Anh "no relevant information"
               lowercaseAnswer.contains("không tìm thấy thông tin") || // Kiểm tra nếu chứa cụm từ "không tìm thấy thông tin"
               lowercaseAnswer.contains("không có thông tin"); // Kiểm tra nếu chứa cụm từ "không có thông tin"
    }

    /**
     * Chuẩn hóa câu trả lời bằng cách loại bỏ các tiền tố phổ biến và làm sạch văn bản
     * @param answer Câu trả lời cần chuẩn hóa
     * @return Câu trả lời đã được chuẩn hóa
     */
    private String normalizeAnswer(String answer) { // Khai báo phương thức normalizeAnswer với tham số là câu trả lời cần chuẩn hóa
        if (answer == null || answer.trim().isEmpty()) { // Kiểm tra nếu câu trả lời là null hoặc rỗng sau khi loại bỏ khoảng trắng
            return ""; // Trả về chuỗi rỗng nếu câu trả lời không hợp lệ
        }

        String result = answer.trim(); // Khởi tạo biến result bằng câu trả lời đã loại bỏ khoảng trắng ở đầu và cuối

        // Khai báo mảng chứa các tiền tố phổ biến cần loại bỏ
        String[] prefixes = { // Khai báo mảng prefixes chứa các cụm từ mở đầu thường gặp trong câu trả lời
            "Dựa trên thông tin cung cấp, ", // Tiền tố 1
            "Theo thông tin đã cung cấp, ", // Tiền tố 2
            "Từ thông tin đã cung cấp, ", // Tiền tố 3
            "Based on the information provided, ", // Tiền tố 4 (tiếng Anh)
            "According to the information, ", // Tiền tố 5 (tiếng Anh)
            "Dựa vào thông tin, ", // Tiền tố 6
            "Từ dữ liệu đã cung cấp, ", // Tiền tố 7
            "Thông tin cho biết ", // Tiền tố 8
            "Theo dữ liệu, ", // Tiền tố 9
            "Theo nguồn thông tin, ", // Tiền tố 10
            "Căn cứ vào thông tin, " // Tiền tố 11
        };

        for (String prefix : prefixes) { // Vòng lặp duyệt qua từng tiền tố trong mảng prefixes
            if (result.toLowerCase().startsWith(prefix.toLowerCase())) { // Kiểm tra xem câu trả lời (đã chuyển thành chữ thường) có bắt đầu bằng tiền tố hiện tại không
                result = result.substring(prefix.length()); // Nếu có, cắt bỏ tiền tố đó khỏi câu trả lời
                break; // Thoát khỏi vòng lặp sau khi đã tìm thấy và xử lý một tiền tố
            }
        }

        // Khai báo mảng chứa các hậu tố phổ biến cần loại bỏ
        String[] suffixes = { // Khai báo mảng suffixes chứa các cụm từ kết thúc thường gặp trong câu trả lời
            " Hy vọng thông tin này hữu ích cho bạn.", // Hậu tố 1
            " Đây là thông tin từ dữ liệu đã cung cấp.", // Hậu tố 2
            " Đó là thông tin tôi có thể cung cấp.", // Hậu tố 3
            " Trên đây là thông tin về câu hỏi của bạn.", // Hậu tố 4
            " Hy vọng điều này trả lời được câu hỏi của bạn.", // Hậu tố 5
            " Tôi hy vọng điều này giúp ích cho bạn." // Hậu tố 6
        };

        for (String suffix : suffixes) { // Vòng lặp duyệt qua từng hậu tố trong mảng suffixes
            if (result.endsWith(suffix)) { // Kiểm tra xem câu trả lời có kết thúc bằng hậu tố hiện tại không
                result = result.substring(0, result.length() - suffix.length()); // Nếu có, cắt bỏ hậu tố đó khỏi câu trả lời
                break; // Thoát khỏi vòng lặp sau khi đã tìm thấy và xử lý một hậu tố
            }
        }

        // Viết hoa chữ cái đầu tiên của câu trả lời nếu câu trả lời không rỗng
        if (!result.isEmpty()) { // Kiểm tra nếu câu trả lời không rỗng sau khi đã xử lý
            result = Character.toUpperCase(result.charAt(0)) + result.substring(1); // Viết hoa chữ cái đầu tiên của câu trả lời
        }

        return result.trim(); // Trả về câu trả lời đã được chuẩn hóa và loại bỏ khoảng trắng thừa ở đầu và cuối
    }

    /**
     * Giới hạn độ dài câu trả lời tới MAX_ANSWER_LENGTH
     * Nếu câu trả lời dài hơn giới hạn, sẽ cắt bớt và thêm "..." vào cuối
     * 
     * @param answer Câu trả lời cần giới hạn độ dài
     * @return Câu trả lời đã được giới hạn độ dài
     */
    private String limitAnswerLength(String answer) { // Phương thức giới hạn độ dài câu trả lời
        if (answer.length() <= MAX_ANSWER_LENGTH) { // Kiểm tra nếu độ dài câu trả lời không vượt quá giới hạn
            return answer; // Trả về nguyên câu trả lời nếu không vượt quá giới hạn
        }
        return answer.substring(0, MAX_ANSWER_LENGTH - 3) + "..."; // Cắt bớt câu trả lời và thêm "..." vào cuối để chỉ ra rằng câu trả lời đã bị cắt ngắn
    }

    /**
     * Lưu câu hỏi và câu trả lời
     */
    private void saveQuestionAnswer(String question, String answer, Long documentId) {
        try {
            Question q = new Question();
            q.setQuestionText(question);
            q.setAnswerText(answer);
            q.setDocumentId(documentId);
            q.setCreatedAt(LocalDateTime.now());
            questionRepository.save(q);

            if (chatHistoryRepository != null) {
                ChatHistory chat = new ChatHistory();
                chat.setQuestion(question);
                chat.setAnswer(answer);
                chat.setDocumentId(documentId);
                chat.setCreatedAt(LocalDateTime.now());
                chatHistoryRepository.save(chat);
            }
        } catch (Exception e) {
            log.warn("Error saving question and answer: " + e.getMessage());
        }
    }

    /**
     * Viết hoa chữ cái đầu tiên của mỗi từ trong chuỗi
     */
    private String capitalizeEveryWord(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        StringBuilder result = new StringBuilder();
        boolean capitalizeNext = true;

        for (char c : text.toCharArray()) {
            if (Character.isWhitespace(c) || c == '.' || c == ',' || c == '!' || c == '?' || c == ':' || c == ';' || c == '-') {
                capitalizeNext = true;
                result.append(c);
            } else if (capitalizeNext) {
                result.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                result.append(c);
            }
        }

        return result.toString();
    }

    /**
     * Trả về một phản hồi ngẫu nhiên cho câu xác nhận
     */
    private String getRandomAcknowledgementResponse() {
        List<String> responses = Arrays.asList(
            "Cảm ơn bạn. Tôi có thể giúp gì thêm cho bạn?",
            "Rất vui khi có thể giúp đỡ bạn. Bạn cần hỏi gì thêm không?",
            "Tôi rất vui khi thông tin hữu ích cho bạn. Bạn còn câu hỏi nào khác không?",
            "Tôi luôn sẵn sàng hỗ trợ bạn. Bạn cần tìm hiểu thêm về vấn đề gì không?",
            "Cảm ơn bạn đã tương tác. Bạn cần tôi giải thích thêm về điều gì không?",
            "Tôi rất vui khi có thể giúp đỡ. Còn vấn đề nào bạn muốn tìm hiểu không?"
        );

        int randomIndex = (int) (Math.random() * responses.size());
        return responses.get(randomIndex);
    }

    /**
     * Trả về một phản hồi ngẫu nhiên cho câu phủ định
     */
    private String getRandomDisagreementResponse() {
        List<String> responses = Arrays.asList(
            "Tôi hiểu. Vậy bạn muốn hỏi gì khác không?",
            "Xin lỗi vì thông tin chưa đáp ứng nhu cầu của bạn. Bạn cần tìm hiểu điều gì khác?",
            "Tôi sẽ cố gắng cải thiện. Bạn muốn tôi giải thích gì thêm không?",
            "Tôi hiểu rằng thông tin chưa phù hợp. Bạn có thể nêu rõ yêu cầu để tôi hỗ trợ tốt hơn.",
            "Cảm ơn phản hồi của bạn. Vui lòng cho tôi biết bạn cần tôi hỗ trợ gì khác.",
            "Tôi rất tiếc về điều đó. Bạn có câu hỏi khác mà tôi có thể giúp không?",
            "Cảm ơn ý kiến của bạn. Tôi sẽ lưu ý để cải thiện trong tương lai.",
            "Tôi đã ghi nhận phản hồi. Bạn cần hỗ trợ thêm điều gì không?",
            "Tôi sẽ nỗ lực để cung cấp thông tin tốt hơn. Bạn còn câu hỏi nào khác không?"
        );

        int randomIndex = (int) (Math.random() * responses.size());
        return responses.get(randomIndex);
    }

    /**
     * Trích xuất Document ID từ ngữ cảnh
     * Phương thức này tìm kiếm Document ID trong chuỗi ngữ cảnh theo định dạng "ID|..."
     * 
     * @param context Chuỗi ngữ cảnh có thể chứa Document ID
     * @return Document ID nếu tìm thấy, ngược lại là null
     */
    private Long getDocumentIdFromContext(String context) { // Phương thức trích xuất Document ID từ chuỗi ngữ cảnh
        try {
            if (context != null && !context.isEmpty()) { // Kiểm tra nếu ngữ cảnh không null và không rỗng
                String[] parts = context.split("\\|"); // Tách ngữ cảnh thành các phần bằng ký tự "|"
                if (parts.length > 1) { // Kiểm tra nếu có ít nhất 2 phần sau khi tách
                    return Long.parseLong(parts[0].trim()); // Chuyển phần đầu tiên thành Long và trả về (là Document ID)
                }
            }
        } catch (Exception e) { // Bắt ngoại lệ nếu không thể chuyển đổi thành Long
            log.error("Error extracting document ID from context", e); // Ghi log lỗi
        }
        return null; // Trả về null nếu không thể trích xuất Document ID
    }

    /**
     * Tìm các câu hỏi tương tự trong cơ sở dữ liệu
     * Sử dụng vector embedding và tính toán độ tương đồng cosine
     * 
     * @param question Câu hỏi cần tìm kiếm tương đồng
     * @return Danh sách các câu hỏi tương tự đã được sắp xếp theo độ tương đồng
     */
    private List<QuestionMatch> findSimilarQuestions(String question) { // Phương thức tìm kiếm câu hỏi tương tự dựa trên độ tương đồng ngữ nghĩa
        try {
            log.debug("Finding similar questions for: {}", question); // Ghi log câu hỏi đang tìm kiếm để theo dõi
            List<Question> allQuestions = questionRepository.findAll(); // Lấy tất cả câu hỏi từ cơ sở dữ liệu
            if (allQuestions.isEmpty()) { // Kiểm tra nếu không có câu hỏi nào trong cơ sở dữ liệu
                return Collections.emptyList(); // Trả về danh sách rỗng nếu không có dữ liệu
            }

            // Tạo vector nhúng cho câu hỏi đầu vào
            float[] questionVector = vectorService.createEmbedding(question); // Tạo vector nhúng cho câu hỏi đầu vào
            if (questionVector.length == 0) { // Kiểm tra nếu không thể tạo vector nhúng
                log.warn("Could not create embedding for question: {}", question); // Ghi log cảnh báo
                return Collections.emptyList(); // Trả về danh sách rỗng nếu không thể tạo vector
            }

            // Tính độ tương đồng với tất cả câu hỏi trong cơ sở dữ liệu
            List<QuestionMatch> scoredQuestions = new ArrayList<>(); // Tạo danh sách chứa các câu hỏi có điểm tương đồng
            for (Question q : allQuestions) { // Duyệt qua từng câu hỏi trong cơ sở dữ liệu
                float[] storedVector = vectorService.createEmbedding(q.getQuestionText()); // Tạo vector nhúng cho câu hỏi trong DB
                float similarity = VectorUtil.cosineSimilarity(questionVector, storedVector); // Tính độ tương đồng cosine giữa hai vector

                if (similarity > 0.6) { // Điểm ngưỡng 0.6 cho độ tương đồng đủ cao
                    scoredQuestions.add(new QuestionMatch(q, similarity)); // Thêm vào danh sách nếu độ tương đồng vượt ngưỡng
                }
            }

            // Sắp xếp theo điểm tương đồng giảm dần
            scoredQuestions.sort((q1, q2) -> Float.compare(q2.getScore(), q1.getScore())); // Sắp xếp để câu hỏi tương đồng nhất lên đầu

            return scoredQuestions; // Trả về danh sách câu hỏi đã sắp xếp
        } catch (Exception e) { // Bắt ngoại lệ nếu có lỗi
            log.error("Error finding similar questions: {}", e.getMessage()); // Ghi log lỗi
            return Collections.emptyList(); // Trả về danh sách rỗng nếu có lỗi
        }
    }

    /**
     * Tạo câu trả lời từ các câu tương tự bằng cách sử dụng Vertex AI
     * Phương thức này kết hợp các câu tương tự và sử dụng mô hình ngôn ngữ để tạo câu trả lời mạch lạc
     * 
     * @param question Câu hỏi của người dùng
     * @param similarSentences Danh sách các câu tương tự được tìm thấy
     * @return Câu trả lời được tạo ra hoặc thông báo không tìm thấy thông tin
     */
    private String generateAnswerFromSimilarSentences(String question, List<String> similarSentences) { // Phương thức tạo câu trả lời từ các câu tương tự
        try {
            if (similarSentences.isEmpty()) { // Kiểm tra nếu không có câu tương tự nào
                return NO_INFORMATION_MESSAGE; // Trả về thông báo không tìm thấy thông tin
            }

            // Kết hợp các câu tương tự làm ngữ cảnh
            String context = String.join("\n\n", similarSentences); // Nối các câu tương tự với nhau, phân cách bằng 2 dòng mới

            // Trích xuất các câu liên quan nhất
            String relevantText = extractRelevantSentences(question, context); // Trích xuất các câu liên quan trực tiếp đến câu hỏi
            if (relevantText.isEmpty()) { // Nếu không có câu liên quan nào được tìm thấy
                relevantText = context; // Sử dụng toàn bộ ngữ cảnh
            }

            // Xác định loại câu hỏi để tạo prompt chuyên biệt
            QuestionType questionType = detectQuestionType(question); // Phát hiện loại câu hỏi (định nghĩa, so sánh, quy trình, v.v.)

            // Sử dụng loại câu hỏi đã phát hiện để xác định nếu đó là câu hỏi định nghĩa
            boolean isDefinitionQuestion = (questionType == QuestionType.DEFINITION); // Kiểm tra xem có phải câu hỏi định nghĩa không

            // Đối với câu hỏi định nghĩa, xác minh xem ngữ cảnh có chứa chủ đề
            if (isDefinitionQuestion) { // Nếu là câu hỏi định nghĩa
                // Trích xuất chủ đề của câu hỏi định nghĩa (phần trước "là gì")
                String subject = extractSubjectFromDefinitionQuestion(question); // Trích xuất chủ đề, ví dụ "cháo" từ "cháo là gì"

                if (subject != null && !subject.isEmpty()) { // Nếu trích xuất được chủ đề
                    // Kiểm tra xem ngữ cảnh có chứa chủ đề không
                    boolean contextContainsSubject = 
                        relevantText.toLowerCase().contains(subject.toLowerCase()); // Kiểm tra xem văn bản có chứa chủ đề không (không phân biệt hoa thường)

                    // Nếu ngữ cảnh không chứa chủ đề, trả về thông báo không có thông tin
                    if (!contextContainsSubject) { // Nếu không tìm thấy chủ đề trong văn bản
                        log.warn("Definition subject '{}' not found in context - query: '{}'", 
                                subject, question); // Ghi log cảnh báo
                        return NO_INFORMATION_MESSAGE; // Trả về thông báo không tìm thấy thông tin
                    }

                    // Đối với câu hỏi định nghĩa, cũng yêu cầu ngưỡng tương đồng cao hơn
                    // Câu đầu tiên trong similarSentences chứa điểm tương đồng trong log
                    float highestSimilarity = getHighestSimilarityFromSentences(similarSentences); // Lấy điểm tương đồng cao nhất
                    final float DEFINITION_SIMILARITY_THRESHOLD = 0.5f; // Ngưỡng tương đồng cho câu hỏi định nghĩa

                    if (highestSimilarity < DEFINITION_SIMILARITY_THRESHOLD) { // Nếu điểm tương đồng thấp hơn ngưỡng
                        log.warn("Definition question '{}' has similarity {} below threshold {} - returning no info", 
                                question, highestSimilarity, DEFINITION_SIMILARITY_THRESHOLD); // Ghi log cảnh báo
                        return NO_INFORMATION_MESSAGE; // Trả về thông báo không tìm thấy thông tin
                    }

                    log.info("Definition question '{}' with subject '{}' passed checks - similarity: {}", 
                            question, subject, highestSimilarity); // Ghi log thông tin khi câu hỏi định nghĩa vượt qua kiểm tra
                }
            } else { // Nếu không phải câu hỏi định nghĩa
                // Đối với các câu hỏi không phải định nghĩa, áp dụng ngưỡng tương đồng thấp hơn
                float highestSimilarity = getHighestSimilarityFromSentences(similarSentences); // Lấy điểm tương đồng cao nhất
                final float GENERAL_SIMILARITY_THRESHOLD = 0.25f; // Ngưỡng tương đồng cho câu hỏi thông thường

                if (highestSimilarity < GENERAL_SIMILARITY_THRESHOLD) { // Nếu điểm tương đồng thấp hơn ngưỡng
                    log.warn("Question '{}' has similarity {} below threshold {} - returning no info", 
                            question, highestSimilarity, GENERAL_SIMILARITY_THRESHOLD); // Ghi log cảnh báo
                    return NO_INFORMATION_MESSAGE; // Trả về thông báo không tìm thấy thông tin
                }

                log.info("Question '{}' passed similarity check: {}", question, highestSimilarity); // Ghi log thông tin khi câu hỏi vượt qua kiểm tra
            }

            // Tạo prompt dựa trên loại câu hỏi
            String prompt = generatePromptByQuestionType(question, questionType); // Tạo prompt phù hợp với loại câu hỏi

            // Tạo văn bản bằng Vertex API
            String generatedText = vertexAIService.generateText(prompt); // Gửi prompt đến Vertex AI để tạo câu trả lời

            // Xác thực và chuẩn hóa câu trả lời
            if (isInvalidAnswer(generatedText)) { // Kiểm tra xem câu trả lời có hợp lệ không
                // Sử dụng cách tiếp cận trực tiếp hơn cho câu trả lời không hợp lệ
                if (isDefinitionQuestion && relevantText.length() <= 300) { // Nếu là câu hỏi định nghĩa và văn bản đủ ngắn
                    // Đối với câu hỏi định nghĩa, chỉ trả về câu đầu tiên
                    String[] sentences = relevantText.split("(?<=[.!?])\\s+"); // Tách văn bản thành các câu
                    if (sentences.length > 0) { // Nếu có ít nhất một câu
                        return sentences[0]; // Trả về câu đầu tiên
                    }
                }
                return extractFirstFewSentences(relevantText, 2); // Trích xuất chỉ 2 câu đầu tiên nếu không là câu hỏi định nghĩa hoặc văn bản quá dài
            }

            String normalizedAnswer = normalizeAnswer(generatedText); // Chuẩn hóa câu trả lời bằng cách loại bỏ các phần thừa
            return limitAnswerLength(normalizedAnswer); // Giới hạn độ dài và trả về câu trả lời cuối cùng
        } catch (Exception e) { // Bắt ngoại lệ nếu có lỗi
            log.error("Error generating answer from similar sentences: {}", e.getMessage()); // Ghi log lỗi
            return NO_INFORMATION_MESSAGE; // Trả về thông báo không tìm thấy thông tin nếu có lỗi
        }
    }

    /**
     * Trích xuất chủ đề từ câu hỏi định nghĩa
     * Ví dụ, từ "cháo là gì" trích xuất "cháo"
     * @param question Câu hỏi định nghĩa cần trích xuất chủ đề
     * @return Chủ đề được trích xuất hoặc null nếu không thể trích xuất
     */
    private String extractSubjectFromDefinitionQuestion(String question) { // Khai báo phương thức trích xuất chủ đề từ câu hỏi định nghĩa
        try { // Bắt đầu khối try để xử lý ngoại lệ có thể xảy ra
            if (question == null || question.isEmpty()) { // Kiểm tra nếu câu hỏi là null hoặc rỗng
                return null; // Trả về null nếu câu hỏi không hợp lệ
            }

            // Xử lý câu hỏi tiếng Việt dạng "X là gì"
            if (question.contains("là gì")) { // Kiểm tra nếu câu hỏi chứa cụm từ "là gì" - dấu hiệu của câu hỏi định nghĩa tiếng Việt
                // Xử lý trường hợp cụ thể như "X là gì" với cách xử lý đặc biệt
                String[] parts = question.split("\\s+là\\s+gì"); // Chia câu hỏi thành các phần dựa trên cụm từ "là gì"
                if (parts.length > 0 && !parts[0].trim().isEmpty()) { // Kiểm tra nếu có phần đầu tiên và không rỗng
                    // Làm sạch chủ đề bằng cách loại bỏ các tiền tố phổ biến
                    String subject = parts[0].trim(); // Gán phần đầu tiên (đã loại bỏ khoảng trắng) cho biến subject
                    String[] prefixesToRemove = {"cho biết", "hãy cho biết", "xin hỏi", "vui lòng cho biết", "em muốn hỏi"}; // Mảng các tiền tố cần loại bỏ
                    for (String prefix : prefixesToRemove) { // Vòng lặp duyệt qua từng tiền tố
                        if (subject.toLowerCase().startsWith(prefix)) { // Kiểm tra nếu chủ đề bắt đầu bằng tiền tố hiện tại
                            subject = subject.substring(prefix.length()).trim(); // Loại bỏ tiền tố khỏi chủ đề
                        }
                    }

                    // Loại bỏ dấu câu và các ký tự khác ở cuối
                    subject = subject.replaceAll("[,.?!:;]+$", "").trim(); // Loại bỏ các dấu câu ở cuối chủ đề

                    return subject; // Trả về chủ đề đã được trích xuất và làm sạch
                }
            }

            // Xử lý câu hỏi với "định nghĩa" hoặc "giải thích"
            if (question.contains("định nghĩa") || question.contains("giải thích")) { // Kiểm tra nếu câu hỏi chứa từ "định nghĩa" hoặc "giải thích"
                String[] wordsToRemove = {"định nghĩa", "giải thích", "về", "cho", "tôi", "tui", "mình", "chúng tôi", "chúng ta"}; // Mảng các từ cần loại bỏ
                String cleaned = question; // Gán câu hỏi cho biến cleaned
                for (String word : wordsToRemove) { // Vòng lặp duyệt qua từng từ cần loại bỏ
                    cleaned = cleaned.replace(word, ""); // Thay thế từ cần loại bỏ bằng chuỗi rỗng
                }
                return cleaned.trim(); // Trả về câu hỏi đã được làm sạch (đã loại bỏ khoảng trắng)
            }

            return null; // Trả về null nếu không thể trích xuất chủ đề
        } catch (Exception e) { // Bắt ngoại lệ nếu có
            log.error("Error extracting subject from definition question: {}", e.getMessage()); // Ghi log lỗi
            return null; // Trả về null trong trường hợp có lỗi
        }
    }

    /**
     * Trích xuất chỉ N câu đầu tiên từ một văn bản
     */
    private String extractFirstFewSentences(String text, int sentenceCount) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        String[] sentences = text.split("(?<=[.!?])\\s+");
        if (sentences.length <= sentenceCount) {
            return text;
        }

        StringBuilder result = new StringBuilder();
        for (int i = 0; i < Math.min(sentenceCount, sentences.length); i++) {
            result.append(sentences[i]);
            if (i < sentenceCount - 1) {
                result.append(" ");
            }
        }

        return result.toString().trim();
    }

    /**
     * Lớp nội bộ để biểu diễn một câu hỏi phù hợp với điểm tương đồng của nó
     * Dùng để lưu trữ câu hỏi và điểm tương đồng tương ứng khi tìm kiếm câu hỏi tương tự
     */
    private static class QuestionMatch {
        private final Question question; // Đối tượng câu hỏi từ cơ sở dữ liệu
        private final float score; // Điểm tương đồng với câu hỏi hiện tại

        public QuestionMatch(Question question, float score) {
            this.question = question; // Gán câu hỏi
            this.score = score; // Gán điểm tương đồng
        }

        public String getQuestionText() {
            return question.getQuestionText(); // Trả về nội dung câu hỏi
        }

        public String getAnswerText() {
            return question.getAnswerText(); // Trả về nội dung câu trả lời
        }

        public float getScore() {
            return score; // Trả về điểm tương đồng
        }
    }

    /**
     * Enum cho các loại câu hỏi khác nhau để tạo gợi ý chuyên biệt
     * Giúp phân loại câu hỏi và tạo prompt phù hợp cho từng loại
     */
    private enum QuestionType {
        DEFINITION,       // X là gì - câu hỏi yêu cầu định nghĩa về một khái niệm
        COMPARISON,       // So sánh X và Y - câu hỏi yêu cầu so sánh giữa hai hoặc nhiều đối tượng
        PROCEDURE,        // Làm thế nào để X, Cách để X - câu hỏi về quy trình thực hiện
        CAUSE_EFFECT,     // Tại sao X, Vì sao X - câu hỏi về nguyên nhân và kết quả
        HISTORICAL,       // X bắt đầu từ đâu, X có từ khi nào - câu hỏi về lịch sử, nguồn gốc
        LISTING,          // Liệt kê X, Kể ra các X - câu hỏi yêu cầu liệt kê danh sách
        EXAMPLES,         // Ví dụ về X, Cho ví dụ X - câu hỏi yêu cầu ví dụ minh họa
        WHO_WHAT,         // Ai là X, X là ai, Cái gì là X - câu hỏi về định danh
        ANALYSIS,         // Phân tích X, Đánh giá X - câu hỏi yêu cầu phân tích, đánh giá
        GENERAL           // Câu hỏi mặc định/khác - loại dùng cho các câu hỏi không thuộc các loại trên
    }

    /**
     * Khởi tạo các câu trả lời hội thoại đơn giản
     * Phương thức này được gọi trong init() để chuẩn bị dữ liệu cho các câu chào hỏi thông thường
     */
    private void initializeConversationalResponses() { // Phương thức khởi tạo các câu trả lời hội thoại đơn giản
        // Các cặp câu hỏi/câu chào và câu trả lời
        conversationalResponsesVi.put("xin chào", Arrays.asList("Xin chào! Tôi có thể giúp gì cho bạn?", "Chào bạn! Bạn cần hỗ trợ gì?")); // Câu chào tiếng Việt và danh sách câu trả lời
        conversationalResponsesVi.put("chào", Arrays.asList("Chào bạn! Tôi có thể giúp gì cho bạn?", "Xin chào! Bạn cần hỗ trợ gì?")); // Câu chào ngắn và danh sách câu trả lời
        conversationalResponsesVi.put("hello", Arrays.asList("Xin chào! Tôi có thể giúp gì cho bạn?", "Chào bạn! Tôi có thể hỗ trợ bạn như thế nào?")); // Câu chào tiếng Anh nhưng trả lời tiếng Việt
        conversationalResponsesVi.put("hi", Arrays.asList("Xin chào! Tôi có thể giúp gì cho bạn?", "Chào bạn! Tôi có thể hỗ trợ bạn như thế nào?")); // Chào ngắn tiếng Anh nhưng trả lời tiếng Việt
        conversationalResponsesVi.put("tạm biệt", Arrays.asList("Tạm biệt! Hẹn gặp lại bạn.", "Chào tạm biệt! Rất vui được giúp đỡ bạn.")); // Câu tạm biệt và danh sách câu trả lời
        conversationalResponsesVi.put("cảm ơn", Arrays.asList("Không có gì! Rất vui được giúp đỡ bạn.", "Rất vui khi được hỗ trợ bạn!")); // Câu cảm ơn và danh sách câu trả lời
        conversationalResponsesVi.put("bạn làm được gì", Arrays.asList("Tôi là trợ lý AI được tạo ra để hỗ trợ bạn tìm kiếm thông tin và trả lời các câu hỏi của bạn.", "Tôi được thiết kế để giúp bạn tìm kiếm và truy xuất thông tin từ tài liệu của bạn.")); // Hỏi về chatbot

    }

    /**
     * Chuẩn hóa câu hỏi bằng cách loại bỏ ký tự đặc biệt và khoảng trắng thừa
     * Điều này giúp tăng khả năng khớp với câu hỏi tương tự và cải thiện chất lượng tìm kiếm
     * 
     * @param question Câu hỏi cần chuẩn hóa
     * @return Câu hỏi đã được chuẩn hóa
     */
    private String normalizeQuestion(String question) { // Phương thức chuẩn hóa câu hỏi
        if (question == null) { // Kiểm tra nếu câu hỏi là null
            return ""; // Trả về chuỗi rỗng nếu câu hỏi null
        }

        // Chuyển thành chữ thường và loại bỏ khoảng trắng ở đầu và cuối
        String normalized = question.toLowerCase().trim(); // Chuyển câu hỏi thành chữ thường và loại bỏ khoảng trắng thừa

        // Loại bỏ dấu câu trừ các dấu câu cần thiết
        normalized = normalized.replaceAll("[^\\p{L}\\p{N}\\s.,?!-]", ""); // Chỉ giữ lại chữ cái, số, khoảng trắng và một số dấu câu cơ bản

        // Thay thế nhiều khoảng trắng bằng một khoảng trắng duy nhất
        normalized = normalized.replaceAll("\\s+", " "); // Chuẩn hóa khoảng trắng

        return normalized; // Trả về câu hỏi đã được chuẩn hóa
    }

    /**
     * Kiểm tra xem văn bản có chứa các cụm từ xác nhận không
     * Ví dụ: "ok", "được", "cảm ơn", "vâng", v.v.
     * 
     * @param text Văn bản cần kiểm tra
     * @return true nếu văn bản chứa cụm từ xác nhận, ngược lại là false
     */
    private boolean containsAcknowledgmentPhrase(String text) { // Phương thức kiểm tra xem văn bản có chứa cụm từ xác nhận không
        if (text == null || text.isEmpty()) { // Kiểm tra nếu văn bản null hoặc rỗng
            return false; // Trả về false nếu văn bản không hợp lệ
        }

        String normalized = text.toLowerCase().trim(); // Chuẩn hóa văn bản thành chữ thường và loại bỏ khoảng trắng thừa

        // Các cụm từ xác nhận phổ biến trong tiếng Việt
        String[] acknowledgments = { // Mảng các cụm từ xác nhận
            "ok", "okay", "được", "được rồi", "tốt", "tốt rồi", 
            "hiểu rồi", "rõ", "rõ rồi", "cảm ơn", "cám ơn", "thanks", 
            "đã hiểu", "tôi hiểu rồi", "vâng", "đúng", "đúng rồi",
            "ko", "không", "khỏi", "không cần", "thôi", "đừng", "dừng"
        };

        for (String phrase : acknowledgments) { // Duyệt qua từng cụm từ xác nhận
            if (normalized.equals(phrase) || normalized.contains(" " + phrase + " ") || 
                normalized.startsWith(phrase + " ") || normalized.endsWith(" " + phrase)) { // Kiểm tra nếu văn bản khớp với cụm từ xác nhận
                return true; // Trả về true nếu tìm thấy cụm từ xác nhận
            }
        }

        return false; // Trả về false nếu không tìm thấy cụm từ xác nhận nào
    }

    /**
     * Trích xuất điểm tương đồng cao nhất từ nhật ký câu
     * VectorService ghi lại điểm tương đồng cho mỗi kết quả phù hợp
     * Phương thức này phân tích các câu để tìm giá trị điểm tương đồng cao nhất
     * 
     * @param similarSentences Danh sách các câu tương tự có thể chứa thông tin điểm tương đồng
     * @return Điểm tương đồng cao nhất, hoặc giá trị mặc định nếu không tìm thấy
     */
    private float getHighestSimilarityFromSentences(List<String> similarSentences) { // Phương thức lấy điểm tương đồng cao nhất từ các câu
        if (similarSentences == null || similarSentences.isEmpty()) { // Kiểm tra nếu danh sách null hoặc rỗng
            return 0.0f; // Trả về 0 nếu không có câu nào
        }

        // Thử lấy điểm tương đồng từ dữ liệu log của VectorService
        try {
            // Đầu tiên kiểm tra câu đầu tiên thường chứa metadata vector
            String firstSentence = similarSentences.get(0); // Lấy câu đầu tiên
            log.debug("Checking similarity from first sentence: {}", firstSentence); // Ghi log thông tin câu đầu tiên

            // Ghi log tất cả các câu để debug
            for (int i = 0; i < similarSentences.size(); i++) { // Duyệt qua tất cả các câu
                log.debug("Sentence {}: {}", i, similarSentences.get(i)); // Ghi log từng câu
            }

            // Thử tất cả các mẫu có thể để trích xuất điểm
            // Mẫu 1: Định dạng điểm trực tiếp trong văn bản - đã cập nhật để khớp với định dạng thực tế
            java.util.regex.Pattern scorePattern = java.util.regex.Pattern.compile("score=(\\d+\\.\\d+)"); // Tạo mẫu regex để tìm "score=X.XXX"
            java.util.regex.Matcher scoreMatcher = scorePattern.matcher(firstSentence); // Tạo matcher cho câu đầu tiên
            if (scoreMatcher.find()) { // Nếu tìm thấy mẫu
                float score = Float.parseFloat(scoreMatcher.group(1)); // Chuyển đổi nhóm đầu tiên thành float
                log.debug("Found similarity score from pattern 'score=X.XXX': {}", score); // Ghi log điểm tìm được
                return score; // Trả về điểm tương đồng
            } else {
                log.debug("Pattern 'score=X.XXX' not found in: {}", firstSentence); // Ghi log nếu không tìm thấy
            }

            // Mẫu 2: Định dạng similarity
            java.util.regex.Pattern similarityPattern = java.util.regex.Pattern.compile("similarity:\\s*(\\d+\\.\\d+)"); // Tạo mẫu regex để tìm "similarity: X.XXX"
            java.util.regex.Matcher similarityMatcher = similarityPattern.matcher(firstSentence); // Tạo matcher cho câu đầu tiên
            if (similarityMatcher.find()) { // Nếu tìm thấy mẫu
                float score = Float.parseFloat(similarityMatcher.group(1)); // Chuyển đổi nhóm đầu tiên thành float
                log.debug("Found similarity score from pattern 'similarity: X.XXX': {}", score); // Ghi log điểm tìm được
                return score; // Trả về điểm tương đồng
            } else {
                log.debug("Pattern 'similarity: X.XXX' not found in: {}", firstSentence); // Ghi log nếu không tìm thấy
            }

            // Nếu không tìm thấy trong câu đầu tiên, tìm kiếm tất cả các câu
            for (String sentence : similarSentences) { // Duyệt qua từng câu
                // Kiểm tra bất kỳ biến thể nào của mẫu điểm
                String[] patterns = {"score=(\\d+\\.\\d+)", "similarity[:\\s]+(\\d+\\.\\d+)", 
                                    "similarity score[:\\s]+(\\d+\\.\\d+)", "score[:\\s]+(\\d+\\.\\d+)"}; // Các mẫu regex khác nhau để tìm điểm

                for (String patternStr : patterns) { // Duyệt qua từng mẫu
                    java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(patternStr); // Tạo mẫu regex
                    java.util.regex.Matcher matcher = pattern.matcher(sentence); // Tạo matcher cho câu hiện tại
                    if (matcher.find()) { // Nếu tìm thấy mẫu
                        float score = Float.parseFloat(matcher.group(1)); // Chuyển đổi nhóm đầu tiên thành float
                        log.debug("Found similarity score {} in pattern: {}", score, patternStr); // Ghi log điểm tìm được
                        return score; // Trả về điểm tương đồng
                    }
                }
            }


            // Nếu không tìm thấy mẫu nào nhưng có câu, giả định ít nhất có một số độ tương đồng
            log.debug("No explicit similarity score found in sentences, using default value"); // Ghi log không tìm thấy điểm rõ ràng
            return 0.5f; // Tăng từ 0.3f để vượt qua ngưỡng cho kết quả khớp tốt hơn
        } catch (Exception e) { // Bắt ngoại lệ nếu có lỗi
            log.error("Error extracting similarity from sentences: {}", e.getMessage()); // Ghi log lỗi
            return 0.0f; // Trả về 0 nếu có lỗi
        }
    }

    /**
     * Phát hiện loại câu hỏi để tạo gợi ý phù hợp
     * Phân loại câu hỏi thành các nhóm như định nghĩa, so sánh, quy trình, v.v.
     * 
     * @param question Câu hỏi cần phân loại
     * @return Loại câu hỏi được phát hiện
     */
    private QuestionType detectQuestionType(String question) {
        String lowerCaseQuestion = question.toLowerCase();

        // Câu hỏi định nghĩa
        if (lowerCaseQuestion.matches(".*(là ai |là gì|định nghĩa|khái niệm|nghĩa là|ý nghĩa của|giải thích|giải nghĩa).*") ||
                (lowerCaseQuestion.contains("cho biết") &&
                        (lowerCaseQuestion.contains("là gì") || lowerCaseQuestion.contains("định nghĩa"))) ||
                lowerCaseQuestion.matches(".*\\bphải không\\b.*") ||
                lowerCaseQuestion.matches(".*\\bcó phải là\\b.*") ||
                lowerCaseQuestion.matches(".*\\bcó nghĩa là\\b.*") ||
                lowerCaseQuestion.matches(".*\\bđược hiểu là\\b.*") ||
                lowerCaseQuestion.endsWith("là gì") ||
                lowerCaseQuestion.matches(".*\\blà gì\\b.*")) {
            return QuestionType.DEFINITION;
        }

        // Câu hỏi so sánh
        if (lowerCaseQuestion.matches(".*(so sánh|khác nhau|giống nhau|điểm giống|điểm khác|phân biệt).*")) {
            return QuestionType.COMPARISON;
        }

        // Câu hỏi quy trình
        if (lowerCaseQuestion.matches(".*(làm thế nào|làm sao|cách|quy trình|các bước|hướng dẫn|thực hiện).*")) {
            return QuestionType.PROCEDURE;
        }

        // Câu hỏi nguyên nhân – kết quả
        if (lowerCaseQuestion.matches(".*(tại sao|vì sao|lý do|nguyên nhân|dẫn đến|kết quả của|hệ quả).*")) {
            return QuestionType.CAUSE_EFFECT;
        }

        // Câu hỏi lịch sử
        if (lowerCaseQuestion.matches(".*(lịch sử|nguồn gốc|bắt đầu|xuất phát|ra đời|hình thành|khi nào).*")) {
            return QuestionType.HISTORICAL;
        }

        // Câu hỏi liệt kê
        if (lowerCaseQuestion.matches(".*(liệt kê|kể tên|nêu|các loại|những loại|bao nhiêu|có mấy).*")) {
            return QuestionType.LISTING;
        }

        // Câu hỏi ví dụ
        if (lowerCaseQuestion.matches(".*(ví dụ|minh họa|dẫn chứng|trường hợp).*")) {
            return QuestionType.EXAMPLES;
        }

        // Câu hỏi ai/cái gì (định danh)
        if (lowerCaseQuestion.matches(".*(ai là|là ai|người nào|cái gì|vật gì|nơi nào|ở đâu).*")) {
            return QuestionType.WHO_WHAT;
        }

        // Câu hỏi phân tích
        if (lowerCaseQuestion.matches(".*(đánh giá|nhận xét|phân tích|ưu điểm|nhược điểm|mặt tốt|mặt xấu).*")) {
            return QuestionType.ANALYSIS;
        }

        // Mặc định
        return QuestionType.GENERAL;
    }


    /**
     * Tạo gợi ý phù hợp dựa trên loại câu hỏi để gửi đến dịch vụ AI
     * @param question Câu hỏi của người dùng
     * @param questionType Loại câu hỏi đã được phát hiện
     * @return Chuỗi prompt hoàn chỉnh để gửi đến AI
     */
    private String generatePromptByQuestionType(String question, QuestionType questionType) { // Khai báo phương thức tạo prompt dựa trên loại câu hỏi
        log.info("Generating prompt for question type: {}", questionType); // Ghi log thông tin về loại câu hỏi đang xử lý

        StringBuilder prompt = new StringBuilder("Trả lời câu hỏi dưới đây một cách chính xác và súc tích:\n\n"); // Khởi tạo chuỗi prompt với phần mở đầu chung
        prompt.append(question); // Thêm câu hỏi của người dùng vào prompt
        prompt.append("\n\n"); // Thêm hai dòng trống để phân tách câu hỏi và hướng dẫn

        switch (questionType) { // Bắt đầu khối switch để xử lý từng loại câu hỏi khác nhau
            case DEFINITION: // Trường hợp câu hỏi định nghĩa
                prompt.append("Yêu cầu: Đưa ra định nghĩa vừa đủ và chính xác về khái niệm được hỏi. " + // Thêm hướng dẫn cho câu hỏi định nghĩa
                        "Viết 1-3 câu chi tiết để trình bày các khía cạnh quan trọng của khái niệm. " +
                        "Bắt đầu bằng cụm từ '[Khái niệm] là' để giới thiệu định nghĩa. " +
                        "Trả lời vừa đủ, chi tiết và chính xác về mặt học thuật. " +
                        "Sử dụng ngôn ngữ dễ hiểu nhưng vẫn đảm bảo tính chuyên môn. " +
                        "Đưa ra đầy đủ thông tin từ tài liệu về định nghĩa, đặc điểm chính và ứng dụng nếu có. " +
                        "KHÔNG kết thúc bằng các câu như 'hy vọng giúp ích' hoặc 'đây là định nghĩa về'.");
                break; // Kết thúc xử lý cho trường hợp câu hỏi định nghĩa

            case COMPARISON: // Trường hợp câu hỏi so sánh
                prompt.append("Yêu cầu: Trả lời ngắn gọn, tập trung vào 2-3 điểm khác biệt chính và quan trọng nhất. " + // Thêm hướng dẫn cho câu hỏi so sánh
                        "KHÔNG liệt kê quá nhiều điểm. KHÔNG dài dòng giải thích từng điểm. Nếu có thể, sử dụng cấu trúc " +
                        "so sánh đối chiếu rõ ràng. KHÔNG giới thiệu câu trả lời. KHÔNG kết luận lại sau khi so sánh.");
                break; // Kết thúc xử lý cho trường hợp câu hỏi so sánh

            case PROCEDURE: // Trường hợp câu hỏi về quy trình/thủ tục
                prompt.append("Yêu cầu: Liệt kê tối đa 5 bước chính theo thứ tự logic. " + // Thêm hướng dẫn cho câu hỏi về quy trình
                        "Sử dụng động từ mệnh lệnh để bắt đầu mỗi bước. " +
                        "Mỗi bước cần ngắn gọn và rõ ràng. " +
                        "KHÔNG giải thích dài dòng cho từng bước. " +
                        "KHÔNG giới thiệu câu trả lời. " +
                        "KHÔNG kết luận sau khi liệt kê các bước.");
                break; // Kết thúc xử lý cho trường hợp câu hỏi về quy trình

            case CAUSE_EFFECT: // Trường hợp câu hỏi về nguyên nhân-kết quả
                prompt.append("Yêu cầu: Chỉ nêu tối đa 3 nguyên nhân chính theo thứ tự quan trọng. " + // Thêm hướng dẫn cho câu hỏi về nguyên nhân-kết quả
                        "Mỗi nguyên nhân cần ngắn gọn và súc tích. " +
                        "KHÔNG giải thích quá chi tiết. " +
                        "KHÔNG giới thiệu câu trả lời. " +
                        "KHÔNG kết luận lại sau khi liệt kê nguyên nhân.");
                break; // Kết thúc xử lý cho trường hợp câu hỏi về nguyên nhân-kết quả

            case HISTORICAL: // Trường hợp câu hỏi về lịch sử
                prompt.append("Yêu cầu: Trả lời ngắn gọn, chỉ nêu thông tin chính xác về bối cảnh lịch sử. " + // Thêm hướng dẫn cho câu hỏi về lịch sử
                        "Nêu rõ thời gian, địa điểm, nhân vật liên quan (nếu có). " +
                        "KHÔNG đi quá sâu vào chi tiết không cần thiết. " +
                        "KHÔNG giới thiệu câu trả lời. " +
                        "KHÔNG kết luận lại sau khi trả lời.");
                break; // Kết thúc xử lý cho trường hợp câu hỏi về lịch sử

            case LISTING: // Trường hợp câu hỏi yêu cầu liệt kê
                prompt.append("Yêu cầu: Liệt kê tối đa 5 mục chính theo thứ tự quan trọng. " + // Thêm hướng dẫn cho câu hỏi yêu cầu liệt kê
                        "Mỗi mục cần ngắn gọn, súc tích và đi thẳng vào trọng tâm. " +
                        "KHÔNG giải thích chi tiết từng mục. " +
                        "KHÔNG giới thiệu câu trả lời. " +
                        "KHÔNG kết luận lại sau khi liệt kê.");
                break; // Kết thúc xử lý cho trường hợp câu hỏi yêu cầu liệt kê

            case EXAMPLES: // Trường hợp câu hỏi yêu cầu ví dụ
                prompt.append("Yêu cầu: Cung cấp tối đa 3 ví dụ cụ thể, đa dạng và tiêu biểu nhất. " + // Thêm hướng dẫn cho câu hỏi yêu cầu ví dụ
                        "Mỗi ví dụ cần ngắn gọn và rõ ràng. " +
                        "KHÔNG giải thích dài dòng cho từng ví dụ. " +
                        "KHÔNG giới thiệu câu trả lời. " +
                        "KHÔNG kết luận lại sau khi đưa ra ví dụ.");
                break; // Kết thúc xử lý cho trường hợp câu hỏi yêu cầu ví dụ

            case WHO_WHAT: // Trường hợp câu hỏi về ai/cái gì
                prompt.append("Yêu cầu: Trả lời chính xác và ngắn gọn trong 1-2 câu. " + // Thêm hướng dẫn cho câu hỏi về ai/cái gì
                        "Nêu thông tin cốt lõi chính xác về đối tượng được hỏi. " +
                        "KHÔNG đưa ra thông tin phụ không liên quan. " +
                        "KHÔNG giới thiệu câu trả lời. " +
                        "KHÔNG kết luận lại sau khi trả lời.");
                break; // Kết thúc xử lý cho trường hợp câu hỏi về ai/cái gì

            case ANALYSIS: // Trường hợp câu hỏi phân tích
                prompt.append("Yêu cầu: Trả lời ngắn gọn, cân bằng giữa ưu điểm và nhược điểm (nếu có). " + // Thêm hướng dẫn cho câu hỏi phân tích
                        "Chỉ tập trung vào 2-3 điểm phân tích quan trọng nhất. " +
                        "KHÔNG đi quá sâu vào chi tiết. " +
                        "KHÔNG giới thiệu câu trả lời. " +
                        "KHÔNG kết luận lại sau khi phân tích.");
                break; // Kết thúc xử lý cho trường hợp câu hỏi phân tích

            case GENERAL: // Trường hợp câu hỏi chung/mặc định
            default: // Trường hợp mặc định
                prompt.append("Yêu cầu: Trả lời ngắn gọn, súc tích trong 1-3 câu tập trung vào trọng tâm câu hỏi. " + // Thêm hướng dẫn cho câu hỏi chung
                        "KHÔNG đưa ra thông tin phụ không cần thiết. " +
                        "KHÔNG giới thiệu câu trả lời. " +
                        "KHÔNG kết luận lại sau khi trả lời.");
                break; // Kết thúc xử lý cho trường hợp câu hỏi chung
        }

        // Thêm yêu cầu chung cho tất cả các loại câu trả lời
        prompt.append("\n\nQuy tắc bắt buộc: Đi thẳng vào nội dung câu trả lời. KHÔNG bắt đầu bằng 'Câu trả lời là', " + // Thêm các quy tắc chung cho tất cả các loại câu hỏi
                "'Dưới đây là', 'Tóm lại', v.v. Trả lời bằng tiếng Việt có dấu, đúng ngữ pháp và dễ hiểu.");

        return prompt.toString(); // Trả về chuỗi prompt hoàn chỉnh
    }

    /**
     * Xóa cache câu trả lời để đảm bảo câu trả lời mới nhất từ dữ liệu
     * Phương thức này thường được gọi khi cập nhật cơ sở dữ liệu hoặc khi muốn làm mới bộ đệm
     * 
     * @return Không có giá trị trả về (void)
     */
    public void clearResponseCache() { // Phương thức xóa bộ nhớ đệm câu trả lời
        int cacheSize = responseCache.size(); // Lấy kích thước bộ nhớ đệm hiện tại
        responseCache.clear(); // Xóa toàn bộ bộ nhớ đệm
        log.info("Đã xóa {} câu trả lời khỏi cache", cacheSize); // Ghi log số lượng câu trả lời đã xóa
    }
}
