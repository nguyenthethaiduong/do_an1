package aidhkm.dhkm16a1hn.controller;

import aidhkm.dhkm16a1hn.service.PDFService;
import aidhkm.dhkm16a1hn.service.PDFService.ProcessResult;
import aidhkm.dhkm16a1hn.service.TextService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

@Controller
@RequestMapping("/upload")
public class FileUploadController {
    private static final Logger logger = Logger.getLogger(FileUploadController.class.getName());

    @Autowired
    private PDFService pdfService;

    @Autowired
    private TextService textService;

    /**
     * Hiển thị trang upload file
     */
    @RequestMapping("")
    public String showUploadPage() {
        return "upload";
    }

    /**
     * API endpoint để kiểm tra tiến trình xử lý
     * @param filePath Đường dẫn file đã lưu
     * @return Thông tin tiến trình
     */
    @GetMapping("/progress")
    public ResponseEntity<Map<String, Object>> getUploadProgress(@RequestParam("filePath") String filePath) {
        // Triển khai trong tương lai nếu cần theo dõi tiến trình
        Map<String, Object> response = new HashMap<>();
        response.put("message", "Tính năng này đang được phát triển");
        return ResponseEntity.ok(response);
    }

    @PostMapping("/txt-file")
    @ResponseBody
    public Map<String, Object> handleTxtFileUpload(@RequestParam("file") MultipartFile file,
                                                 @RequestParam("documentName") String documentName) {
        Map<String, Object> response = new HashMap<>();
        
        if (file.isEmpty()) {
            response.put("success", false);
            response.put("message", "Vui lòng chọn file văn bản để tải lên");
            return response;
        }
        
        TextService.ProcessResult result = textService.processTxtFile(file, documentName);
        
        response.put("success", result.isSuccess());
        response.put("message", result.getMessage());
        response.put("progress", result.getProgress());
        
        if (result.isSuccess()) {
            response.put("extractedSegments", result.getExtractedSegments());
        }
        
        return response;
    }
    
    @PostMapping("/pdf-file")
    @ResponseBody
    public Map<String, Object> handlePdfFileUpload(@RequestParam("file") MultipartFile file,
                                                 @RequestParam("documentName") String documentName) {
        Map<String, Object> response = new HashMap<>();
        
        if (file.isEmpty()) {
            response.put("success", false);
            response.put("message", "Vui lòng chọn file PDF để tải lên");
            return response;
        }
        
        PDFService.ProcessResult result = pdfService.uploadAndProcessPdf(file);
        
        response.put("success", result.isSuccess());
        response.put("message", result.getMessage());
        response.put("progress", result.getProgress());
        
        if (result.isSuccess()) {
            response.put("totalPages", result.getTotalPages());
            response.put("processedPages", result.getProcessedPages());
            response.put("extractedSegments", result.getExtractedSegments());
        }
        
        return response;
    }
} 