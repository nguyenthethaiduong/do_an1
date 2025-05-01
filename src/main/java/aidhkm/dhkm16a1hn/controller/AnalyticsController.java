package aidhkm.dhkm16a1hn.controller;

import aidhkm.dhkm16a1hn.model.Document;
import aidhkm.dhkm16a1hn.model.Question;
import aidhkm.dhkm16a1hn.repository.DocumentRepository;
import aidhkm.dhkm16a1hn.repository.EmbeddingRepository;
import aidhkm.dhkm16a1hn.repository.QuestionRepository;
import aidhkm.dhkm16a1hn.service.DataCleanupService;
import aidhkm.dhkm16a1hn.service.VectorService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/analytics")
public class AnalyticsController {

    @Autowired
    private QuestionRepository questionRepository;

    @Autowired
    private DocumentRepository documentRepository;
    
    @Autowired
    private EmbeddingRepository embeddingRepository;
    
    @Autowired
    private VectorService vectorService;
    
    @Autowired
    private DataCleanupService dataCleanupService;

    /**
     * Hiển thị trang dashboard
     */
    @GetMapping("")
    public String showDashboard(Model model) {
        // Thống kê số lượng
        long questionCount = questionRepository.count();
        long documentCount = documentRepository.count();
        long embeddingCount = embeddingRepository.count();
        
        model.addAttribute("questionCount", questionCount);
        model.addAttribute("documentCount", documentCount);
        model.addAttribute("embeddingCount", embeddingCount);
        
        // Câu hỏi gần đây
        List<Question> recentQuestions = questionRepository.findTop10ByOrderByCreatedAtDesc();
        model.addAttribute("recentQuestions", recentQuestions);
        
        // Tài liệu gần đây
        List<Document> recentDocuments = documentRepository.findAllOrderByCreatedAtDesc().stream()
            .limit(5)
            .collect(Collectors.toList());
        model.addAttribute("recentDocuments", recentDocuments);
        
        return "analytics";
    }
    
    /**
     * API lấy dữ liệu thống kê 
     */
    @GetMapping("/stats")
    @ResponseBody
    public Map<String, Object> getStats() {
        Map<String, Object> result = new HashMap<>();
        
        // Thống kê cơ bản
        result.put("questionCount", questionRepository.count());
        result.put("documentCount", documentRepository.count());
        result.put("embeddingCount", embeddingRepository.count());
        
        // Thống kê câu hỏi theo ngày
        List<Question> allQuestions = questionRepository.findAll();
        Map<String, Long> questionsByDate = new HashMap<>();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        
        for (Question question : allQuestions) {
            String dateStr = question.getCreatedAt().format(formatter);
            questionsByDate.put(dateStr, questionsByDate.getOrDefault(dateStr, 0L) + 1L);
        }
        
        result.put("questionsByDate", questionsByDate);
        
        return result;
    }
    
    /**
     * API lấy dữ liệu về vector embeddings
     */
    @GetMapping("/embeddings")
    @ResponseBody
    public Map<String, Object> getEmbeddingStats() {
        Map<String, Object> result = new HashMap<>();
        
        // Thống kê số lượng embedding theo tài liệu
        List<Document> documents = documentRepository.findAll();
        List<Map<String, Object>> embeddingsPerDocument = new ArrayList<>();
        
        for (Document doc : documents) {
            Map<String, Object> documentData = new HashMap<>();
            documentData.put("documentId", doc.getId());
            documentData.put("documentName", doc.getName());
            documentData.put("embeddingCount", embeddingRepository.findByDocumentId(doc.getId()).size());
            embeddingsPerDocument.add(documentData);
        }
        
        result.put("embeddingsPerDocument", embeddingsPerDocument);
        
        return result;
    }
    
    /**
     * API lấy thông tin về hệ thống
     */
    @GetMapping("/system")
    @ResponseBody
    public Map<String, Object> getSystemInfo() {
        Map<String, Object> result = new HashMap<>();
        
        // Thông tin về JVM
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long allocatedMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        
        result.put("maxMemory", maxMemory / 1024 / 1024 + " MB");
        result.put("allocatedMemory", allocatedMemory / 1024 / 1024 + " MB");
        result.put("freeMemory", freeMemory / 1024 / 1024 + " MB");
        result.put("availableProcessors", runtime.availableProcessors());
        
        return result;
    }
    
    /**
     * API để dọn dẹp vector embeddings bị treo
     */
    @PostMapping("/cleanup/orphaned-embeddings")
    @ResponseBody
    public Map<String, Object> cleanupOrphanedEmbeddings() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            int deletedCount = dataCleanupService.manualCleanupOrphanedEmbeddings();
            result.put("success", true);
            result.put("message", "Đã xóa thành công " + deletedCount + " vector embeddings bị treo");
            result.put("deletedCount", deletedCount);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "Có lỗi xảy ra: " + e.getMessage());
            result.put("error", e.getMessage());
        }
        
        return result;
    }
    
    /**
     * API lấy thông tin chi tiết về vector embeddings
     */
    @GetMapping("/embeddings/stats")
    @ResponseBody
    public Map<String, Object> getEmbeddingStatistics() {
        return vectorService.getEmbeddingStatistics();
    }
} 