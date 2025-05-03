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
import aidhkm.dhkm16a1hn.service.ChatService;

/**
 * Controller quản lý các thao tác liên quan đến huấn luyện, quản lý tài liệu và xử lý vector
 * Cung cấp các endpoint để tải lên, liệt kê, xóa tài liệu và tái tạo vector nhúng
 */
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

    @Autowired
    private ChatService chatService;

    /**
     * Hiển thị trang upload/training
     * Phương thức này trả về template HTML cho trang tải lên tài liệu
     * 
     * @return Tên template HTML để hiển thị
     */
    @GetMapping
    public String showTrainingPage() {
        return "upload";
    }

    /**
     * Xử lý upload tài liệu text
     * Phương thức này nhận nội dung văn bản được gửi lên từ form và lưu vào cơ sở dữ liệu
     * 
     * @param name Tên tài liệu
     * @param content Nội dung tài liệu
     * @param model Model để truyền dữ liệu đến view
     * @return Tên template HTML để hiển thị
     */
    @PostMapping("/upload")
    public String uploadDocument(@RequestParam String name, @RequestParam String content, Model model) {
        trainingService.saveDocument(name, content);
        model.addAttribute("message", "Tài liệu đã được tải lên và AI đã học!");
        return "upload";
    }
    
    /**
     * Xử lý upload file
     * Phương thức này nhận file được gửi lên, đọc nội dung và lưu vào cơ sở dữ liệu
     * 
     * @param file File được tải lên
     * @param name Tên tài liệu
     * @param model Model để truyền dữ liệu đến view
     * @return Tên template HTML để hiển thị
     */
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

    /**
     * Lấy và hiển thị danh sách tài liệu
     * Phương thức này truy vấn danh sách tài liệu từ cơ sở dữ liệu và hiển thị trong view
     * 
     * @param model Model để truyền dữ liệu đến view
     * @return Tên template HTML để hiển thị
     */
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
    
    /**
     * API xóa tài liệu
     * Phương thức này xóa tài liệu theo ID và xóa các cache liên quan
     * 
     * @param id ID của tài liệu cần xóa
     * @return Kết quả xóa tài liệu dưới dạng JSON
     */
    @DeleteMapping("/documents/{id}")
    @ResponseBody
    public Map<String, Object> deleteDocument(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();
        try {
            trainingService.deleteDocument(id);
            
            // Xóa cache để đảm bảo không còn câu trả lời cũ
            vectorService.clearAllCaches();
            chatService.clearResponseCache();
            
            response.put("success", true);
            response.put("message", "Tài liệu đã được xóa thành công và cache đã được xóa");
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Lỗi khi xóa tài liệu: " + e.getMessage());
        }
        return response;
    }

    /**
     * Khởi tạo lại toàn bộ vector cho tất cả tài liệu
     * Phương thức này gọi dịch vụ NLP để tạo lại vector nhúng cho tất cả tài liệu
     * 
     * @param model Model để truyền dữ liệu đến view
     * @return Tên template HTML để hiển thị
     */
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
     * Tái tạo lại tất cả vector với kích thước nhất quán
     * Phương thức này sử dụng chức năng regenerateAllVectors trong VectorService
     * để tái tạo vector có kích thước đồng nhất
     * 
     * @param model Model để truyền dữ liệu đến view
     * @return Tên template HTML để hiển thị
     */
    @GetMapping("/regenerate-vectors")
    public String regenerateVectors(Model model) {
        try {
            logger.info("Bắt đầu quá trình tái tạo vector");
            
            // Xóa cache trước khi tái tạo
            vectorService.clearAllCaches();
            
            // Tái tạo tất cả vector
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
     * Tạo tài liệu mẫu về phở để kiểm thử
     * Phương thức này tạo một tài liệu mẫu về phở Việt Nam để kiểm tra chức năng của hệ thống
     * 
     * @param model Model để truyền dữ liệu đến view
     * @return Tên template HTML để hiển thị
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

    /**
     * Xóa cache câu trả lời để lấy đáp án mới từ dữ liệu hiện tại
     * Phương thức này xóa bộ nhớ đệm câu trả lời để hệ thống sẽ sử dụng dữ liệu hiện tại
     * khi trả lời các câu hỏi tiếp theo
     * 
     * @param model Model để truyền dữ liệu đến view
     * @return Tên template HTML để hiển thị
     */
    @GetMapping("/clear-response-cache")
    public String clearResponseCache(Model model) {
        try {
            chatService.clearResponseCache();
            
            String message = "Đã xóa cache câu trả lời thành công.";
            model.addAttribute("message", message);
            model.addAttribute("documents", documentRepository.findAll());
            
            logger.info(message);
            
            return "documents";
        } catch (Exception e) {
            logger.severe("Lỗi khi xóa cache câu trả lời: " + e.getMessage());
            e.printStackTrace();
            
            model.addAttribute("error", "Lỗi khi xóa cache câu trả lời: " + e.getMessage());
            model.addAttribute("documents", documentRepository.findAll());
            
            return "documents";
        }
    }
}
