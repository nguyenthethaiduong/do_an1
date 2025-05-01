package aidhkm.dhkm16a1hn.controller;

import aidhkm.dhkm16a1hn.service.TrainingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import aidhkm.dhkm16a1hn.model.Document;
import java.util.logging.Logger;
import aidhkm.dhkm16a1hn.service.NLPService;
import aidhkm.dhkm16a1hn.service.VectorService;
import aidhkm.dhkm16a1hn.repository.EmbeddingRepository;
import aidhkm.dhkm16a1hn.repository.DocumentRepository;

@Controller
@RequestMapping("/training")
public class TrainingController {

    private static final Logger logger = Logger.getLogger(TrainingController.class.getName());

    @Autowired
    private TrainingService trainingService;

    @Autowired
    private NLPService nlpService;

    @Autowired
    private VectorService vectorService;

    @Autowired
    private EmbeddingRepository embeddingRepository;

    @Autowired
    private DocumentRepository documentRepository;

    // Hiển thị trang upload/training
    @GetMapping
    public String showTrainingPage() {
        return "upload";
    }

    // Xử lý upload tài liệu text
    @PostMapping("/upload")
    public String uploadDocument(@RequestParam String name, @RequestParam String content, Model model) {
        trainingService.saveDocument(name, content);
        model.addAttribute("message", "Tài liệu đã được tải lên và AI đã học!");
        return "upload";
    }
    
    // Thêm phương thức xử lý upload file
    @PostMapping("/upload-file")
    public String uploadFile(@RequestParam("file") MultipartFile file, @RequestParam String name, Model model) {
        if (file.isEmpty()) {
            model.addAttribute("error", "Vui lòng chọn file để tải lên");
            return "upload";
        }

        try {
            StringBuilder content = new StringBuilder();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));
            
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
            
            trainingService.saveDocument(name, content.toString());
            model.addAttribute("message", "File đã được tải lên và AI đã học!");
            
        } catch (IOException e) {
            model.addAttribute("error", "Lỗi khi đọc file: " + e.getMessage());
        }
        
        return "upload";
    }

    // Lấy danh sách tài liệu
    @GetMapping("/documents")
    public String listDocuments(Model model) {
        List<Document> documents = trainingService.getAllDocuments();
        logger.info("===== DANH SÁCH TÀI LIỆU =====");
        logger.info("Tổng số tài liệu: " + documents.size());
        for (Document doc : documents) {
            logger.info("ID: " + doc.getId() + ", Tên: " + doc.getName() + ", Ngày tạo: " + doc.getCreatedAt());
        }
        logger.info("================================");
        
        model.addAttribute("documents", documents);
        return "documents";
    }
    
    // API xóa tài liệu
    @DeleteMapping("/documents/{id}")
    @ResponseBody
    public Map<String, Object> deleteDocument(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();
        try {
            trainingService.deleteDocument(id);
            response.put("success", true);
            response.put("message", "Tài liệu đã được xóa thành công");
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Lỗi khi xóa tài liệu: " + e.getMessage());
        }
        return response;
    }

    @GetMapping("/reinitialize-vectors")
    public String reinitializeVectors(Model model) {
        try {
            List<Document> documents = documentRepository.findAll();
            int createdVectors = nlpService.reinitializeAllVectors(documents, vectorService, embeddingRepository);
            
            model.addAttribute("message", "Đã tạo lại " + createdVectors + " vector embedding cho " + documents.size() + " tài liệu.");
            model.addAttribute("documents", documentRepository.findAll());
            
            logger.info("Đã tạo lại " + createdVectors + " vector embedding cho " + documents.size() + " tài liệu.");
            
            return "documents";
        } catch (Exception e) {
            logger.severe("Lỗi khi tạo lại vector embeddings: " + e.getMessage());
            e.printStackTrace();
            
            model.addAttribute("error", "Lỗi khi tạo lại vector embeddings: " + e.getMessage());
            model.addAttribute("documents", documentRepository.findAll());
            
            return "documents";
        }
    }
    
    /**
     * Endpoint to regenerate all vectors with consistent dimensions
     * This uses the new regenerateAllVectors method in VectorService
     */
    @GetMapping("/regenerate-vectors")
    public String regenerateVectors(Model model) {
        try {
            logger.info("Starting vector regeneration process");
            
            // Clear caches before regeneration
            vectorService.clearAllCaches();
            
            // Regenerate all vectors
            int regeneratedCount = vectorService.regenerateAllVectors();
            
            String message = "Đã cập nhật " + regeneratedCount + " vector embedding với kích thước nhất quán.";
            model.addAttribute("message", message);
            model.addAttribute("documents", documentRepository.findAll());
            
            logger.info(message);
            
            return "documents";
        } catch (Exception e) {
            logger.severe("Lỗi khi cập nhật vector embeddings: " + e.getMessage());
            e.printStackTrace();
            
            model.addAttribute("error", "Lỗi khi cập nhật vector embeddings: " + e.getMessage());
            model.addAttribute("documents", documentRepository.findAll());
            
            return "documents";
        }
    }

    /**
     * Create a sample document about phở for testing
     */
    @GetMapping("/sample-pho")
    public String createSamplePho(Model model) {
        try {
            String name = "Phở Việt Nam";
            String content = "Phở là gì?\n\n" +
                    "Phở là một món ăn truyền thống của Việt Nam, có nguồn gốc từ Bắc Việt và được coi là một trong những món ăn tiêu biểu nhất của ẩm thực Việt Nam.\n\n" +
                    "Phở là món súp nước dùng có bánh phở (một loại bánh làm từ gạo) và thịt, thường là thịt bò (phở bò) hoặc thịt gà (phở gà), cùng các gia vị như hành, gừng, quế, hồi, và các loại rau thơm như hành lá, rau mùi.\n\n" +
                    "Nước dùng phở được nấu từ xương bò hoặc xương gà trong nhiều giờ, tạo nên hương vị đặc trưng, thơm ngon. Khi thưởng thức, người ta thường ăn kèm phở với các loại rau sống như giá đỗ, húng quế, ngò gai, và chanh, ớt.\n\n" +
                    "Phở có nhiều biến thể khác nhau tùy theo vùng miền, với hai loại chính là phở Bắc (đơn giản, thanh nhẹ) và phở Nam (ngọt hơn, nhiều gia vị và rau sống hơn).\n\n" +
                    "Hiện nay, phở đã trở thành một món ăn nổi tiếng trên toàn thế giới và là một trong những biểu tượng văn hóa ẩm thực của Việt Nam.";
            
            trainingService.saveDocument(name, content);
            
            model.addAttribute("message", "Đã tạo mẫu tài liệu về Phở Việt Nam");
            model.addAttribute("documents", documentRepository.findAll());
            
            logger.info("Đã tạo mẫu tài liệu về Phở Việt Nam");
            
            return "documents";
        } catch (Exception e) {
            logger.severe("Lỗi khi tạo mẫu tài liệu: " + e.getMessage());
            e.printStackTrace();
            
            model.addAttribute("error", "Lỗi khi tạo mẫu tài liệu: " + e.getMessage());
            model.addAttribute("documents", documentRepository.findAll());
            
            return "documents";
        }
    }
}
