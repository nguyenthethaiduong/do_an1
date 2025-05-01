package aidhkm.dhkm16a1hn.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import aidhkm.dhkm16a1hn.model.Document;
import aidhkm.dhkm16a1hn.repository.DocumentRepository;

@Service
public class TextService {
    private static final Logger logger = Logger.getLogger(TextService.class.getName());
    private static final int MAX_FILE_SIZE = 50 * 1024 * 1024; // 50MB
    private static final String TXT_CONTENT_TYPE = "text/plain";
    
    private final Path uploadDir = Paths.get("txt-uploads");
    
    @Autowired
    private VectorService vectorService;
    
    @Autowired
    private NLPService nlpService;

    @Autowired
    private DocumentRepository documentRepository;
    
    @Autowired
    private FileProcessingService fileProcessingService;

    public TextService() {
        try {
            if (!Files.exists(uploadDir)) {
                Files.createDirectories(uploadDir);
                logger.info("Created TXT upload directory at: " + uploadDir.toAbsolutePath());
            }
        } catch (IOException e) {
            logger.severe("Failed to create TXT upload directory: " + e.getMessage());
        }
    }

    /**
     * Kiểm tra tính hợp lệ của file TXT
     * @param file File cần kiểm tra
     * @return Thông báo lỗi hoặc null nếu file hợp lệ
     */
    public String validateTxtFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return "File không được để trống";
        }
        
        String contentType = file.getContentType();
        String fileName = file.getOriginalFilename();
        
        if (fileName != null && !fileName.toLowerCase().endsWith(".txt")) {
            return "Chỉ chấp nhận file TXT";
        }
        
        if (contentType != null && !contentType.equals(TXT_CONTENT_TYPE) && !contentType.equals("application/octet-stream")) {
            return "Chỉ chấp nhận file văn bản";
        }
        
        if (file.getSize() > MAX_FILE_SIZE) {
            return "Kích thước file không được vượt quá 50MB";
        }
        
        return null;
    }

    /**
     * Xử lý file TXT: lưu file, đọc nội dung và tạo embedding
     * @param file MultipartFile từ request
     * @param documentName Tên tài liệu
     * @return Kết quả xử lý với thông tin về tiến trình và lỗi
     */
    public ProcessResult processTxtFile(MultipartFile file, String documentName) {
        ProcessResult result = new ProcessResult();
        
        // Kiểm tra tính hợp lệ
        String validationError = validateTxtFile(file);
        if (validationError != null) {
            result.setSuccess(false);
            result.setMessage(validationError);
            return result;
        }

        try {
            // Tạo tên file duy nhất để tránh trùng lặp
            String originalFilename = file.getOriginalFilename();
            String uniqueFileName = UUID.randomUUID().toString() + "_" + (originalFilename != null ? originalFilename : "document.txt");
            Path targetPath = uploadDir.resolve(uniqueFileName);
            
            // Lưu file
            logger.info("Saving TXT file: " + uniqueFileName);
            byte[] bytes = file.getBytes();
            Files.write(targetPath, bytes);
            result.setProgress(30);
            result.setSavedFilePath(targetPath.toString());
            
            // Đọc nội dung file
            String content = readTxtContent(file);
            if (content == null || content.trim().isEmpty()) {
                result.setSuccess(false);
                result.setMessage("Không thể đọc nội dung file hoặc file rỗng");
                return result;
            }
            result.setProgress(50);
            
            // Xử lý nội dung và tạo embedding
            int segmentCount = processTextContent(content, documentName);
            if (segmentCount > 0) {
                result.setSuccess(true);
                result.setMessage("File văn bản đã được xử lý thành công");
                result.setProgress(100);
                result.setExtractedSegments(segmentCount);
            } else {
                result.setSuccess(false);
                result.setMessage("Có lỗi xảy ra khi xử lý nội dung văn bản");
            }
            
        } catch (IOException e) {
            logger.severe("Error processing TXT file: " + e.getMessage());
            result.setSuccess(false);
            result.setMessage("Lỗi khi xử lý file: " + e.getMessage());
        } catch (Exception e) {
            logger.severe("Unexpected error: " + e.getMessage());
            result.setSuccess(false);
            result.setMessage("Lỗi không xác định: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * Đọc nội dung file TXT
     * @param file MultipartFile đã tải lên
     * @return Nội dung văn bản
     */
    private String readTxtContent(MultipartFile file) throws IOException {
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
        }
        return content.toString();
    }

    /**
     * Xử lý nội dung văn bản và tạo embedding
     * @param content Nội dung văn bản
     * @param documentName Tên tài liệu
     * @return Số lượng đoạn văn bản đã xử lý
     */
    private int processTextContent(String content, String documentName) {
        // Sử dụng FileProcessingService để xử lý văn bản
        return fileProcessingService.processTextContent(content, documentName);
    }
    
    /**
     * Đối tượng lưu kết quả xử lý và báo cáo tiến trình
     */
    public static class ProcessResult {
        private boolean success;
        private String message;
        private int progress; // 0-100%
        private String savedFilePath;
        private int extractedSegments;
        
        public ProcessResult() {
            this.success = false;
            this.progress = 0;
            this.extractedSegments = 0;
        }

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public int getProgress() {
            return progress;
        }

        public void setProgress(int progress) {
            this.progress = progress;
        }

        public String getSavedFilePath() {
            return savedFilePath;
        }

        public void setSavedFilePath(String savedFilePath) {
            this.savedFilePath = savedFilePath;
        }

        public int getExtractedSegments() {
            return extractedSegments;
        }

        public void setExtractedSegments(int extractedSegments) {
            this.extractedSegments = extractedSegments;
        }
    }
} 