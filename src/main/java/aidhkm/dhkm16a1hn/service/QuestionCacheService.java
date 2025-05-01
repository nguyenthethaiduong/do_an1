package aidhkm.dhkm16a1hn.service;

import org.springframework.stereotype.Service;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.EnableCaching;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Service để cache câu hỏi và câu trả lời để tối ưu hiệu suất
 */
@Service
@EnableCaching
public class QuestionCacheService {
    private static final Logger logger = Logger.getLogger(QuestionCacheService.class.getName());
    
    // Cache đơn giản dựa trên HashMap để lưu trữ câu hỏi và câu trả lời
    private final Map<String, String> questionAnswerCache = new HashMap<>();
    
    /**
     * Lấy câu trả lời từ cache
     * @param question Câu hỏi
     * @return Câu trả lời đã cache hoặc null nếu không có
     */
    @Cacheable(value = "questionAnswers", key = "#question")
    public String getCachedAnswer(String question) {
        logger.info("Checking cache for question: " + question);
        return questionAnswerCache.get(question);
    }
    
    /**
     * Lưu câu trả lời vào cache
     * @param question Câu hỏi
     * @param answer Câu trả lời
     */
    public void cacheAnswer(String question, String answer) {
        logger.info("Caching answer for question: " + question);
        questionAnswerCache.put(question, answer);
    }
    
    /**
     * Kiểm tra xem câu hỏi đã có trong cache chưa
     * @param question Câu hỏi cần kiểm tra
     * @return true nếu câu hỏi đã được cache
     */
    public boolean hasCache(String question) {
        return questionAnswerCache.containsKey(question);
    }
    
    /**
     * Xóa tất cả cache
     */
    @CacheEvict(value = "questionAnswers", allEntries = true)
    public void clearCache() {
        logger.info("Clearing question answer cache");
        questionAnswerCache.clear();
    }
} 