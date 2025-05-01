package aidhkm.dhkm16a1hn.controller;

import aidhkm.dhkm16a1hn.service.ChatService;
import aidhkm.dhkm16a1hn.service.TextService;
import aidhkm.dhkm16a1hn.service.PDFService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.HashMap;

@Controller
public class ChatController {
    
    @Autowired
    private ChatService chatService;
    
    @Autowired
    private TextService textService;
    
    @Autowired
    private PDFService pdfService;
    
    @GetMapping("/chat")
    public String showChatPage() {
        return "chat";
    }
    
    @PostMapping("/chat/ask")
    @ResponseBody
    public ResponseEntity<?> processQuestion(@RequestBody Map<String, String> request) {
        String question = request.get("question");
        if (question == null || question.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Câu hỏi không được để trống"));
        }
        
        try {
            // Log câu hỏi đầu vào
            System.out.println("Question received: " + question);
            
            String answer = chatService.processQuestion(question);
            
            // Debug thông tin câu trả lời
            System.out.println("Final answer sent to client: " + answer);
            
            return ResponseEntity.ok(Map.of("answer", answer));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(Map.of("error", "Có lỗi xảy ra khi xử lý câu hỏi"));
        }
    }
    
    @PostMapping("/chat/upload")
    @ResponseBody
    public ResponseEntity<?> uploadFile(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "File không được để trống"));
        }
        
        try {
            String fileName = file.getOriginalFilename();
            String fileExtension = "";
            if (fileName != null && fileName.contains(".")) {
                fileExtension = fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
            }
            
            boolean processed = false;
            String message = "";
            
            // Xử lý theo loại file
            if ("pdf".equals(fileExtension)) {
                // Xử lý file PDF
                PDFService.ProcessResult result = pdfService.uploadAndProcessPdf(file);
                processed = result.isSuccess();
                message = result.getMessage();
            } else if ("txt".equals(fileExtension) || "doc".equals(fileExtension) || "docx".equals(fileExtension)) {
                // Xử lý file text
                TextService.ProcessResult result = textService.processTxtFile(file, fileName);
                processed = result.isSuccess();
                message = result.getMessage();
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "Định dạng file không được hỗ trợ. Vui lòng sử dụng PDF, TXT, DOC hoặc DOCX."
                ));
            }
            
            if (processed) {
                return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "File đã được tải lên và xử lý thành công. " + message
                ));
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "Không thể xử lý file. " + message
                ));
            }
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(Map.of(
                "error", "Có lỗi xảy ra khi xử lý file: " + e.getMessage()
            ));
        }
    }
}
