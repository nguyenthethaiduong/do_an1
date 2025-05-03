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

/**
 * Dịch vụ xử lý văn bản (file TXT)
 * Cung cấp các chức năng để kiểm tra, đọc, xử lý và trích xuất nội dung
 * từ file văn bản và tạo embeddings cho tìm kiếm ngữ nghĩa
 */
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

    /**
     * Constructor của TextService
     * Khởi tạo thư mục lưu trữ file nếu chưa tồn tại
     */
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
     * Phương thức này kiểm tra các điều kiện: file không rỗng, định dạng .txt,
     * loại nội dung phù hợp và kích thước không vượt quá giới hạn
     * 
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
     * Luồng xử lý:
     * 1. Kiểm tra tính hợp lệ của file
     * 2. Lưu file với tên duy nhất
     * 3. Đọc nội dung văn bản
     * 4. Xử lý nội dung và tạo embedding
     * 
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
     * Sử dụng BufferedReader để đọc từng dòng từ file và xây dựng chuỗi nội dung
     * 
     * @param file MultipartFile đã tải lên
     * @return Nội dung văn bản
     * @throws IOException Nếu có lỗi khi đọc file
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
     * Phương thức này ủy quyền việc xử lý cho FileProcessingService
     * 
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
     * Lớp nội bộ để đóng gói thông tin về kết quả và tiến trình xử lý file
     */
    public static class ProcessResult {
        private boolean success;
        private String message;
        private int progress;
        private String savedFilePath;
        private int extractedSegments;
        
        /**
         * Constructor mặc định
         * Khởi tạo các giá trị ban đầu
         */
        public ProcessResult() {
            this.success = false;
            this.progress = 0;
            this.extractedSegments = 0;
        }

        /**
         * Kiểm tra xem quá trình xử lý có thành công không
         * @return true nếu thành công, false nếu thất bại
         */
        public boolean isSuccess() {
            return success;
        }

        /**
         * Đặt trạng thái thành công của quá trình xử lý
         * @param success Trạng thái cần đặt
         */
        public void setSuccess(boolean success) {
            this.success = success;
        }

        /**
         * Lấy thông báo kết quả hoặc lỗi
         * @return Thông báo kết quả hoặc lỗi
         */
        public String getMessage() {
            return message;
        }

        /**
         * Đặt thông báo kết quả hoặc lỗi
         * @param message Thông báo cần đặt
         */
        public void setMessage(String message) {
            this.message = message;
        }

        /**
         * Lấy tiến trình xử lý (0-100%)
         * @return Giá trị tiến trình
         */
        public int getProgress() {
            return progress;
        }

        /**
         * Đặt tiến trình xử lý
         * @param progress Giá trị tiến trình (0-100%)
         */
        public void setProgress(int progress) {
            this.progress = progress;
        }

        /**
         * Lấy đường dẫn file đã lưu
         * @return Đường dẫn file
         */
        public String getSavedFilePath() {
            return savedFilePath;
        }

        /**
         * Đặt đường dẫn file đã lưu
         * @param savedFilePath Đường dẫn cần đặt
         */
        public void setSavedFilePath(String savedFilePath) {
            this.savedFilePath = savedFilePath;
        }

        /**
         * Lấy số lượng đoạn văn bản đã trích xuất
         * @return Số lượng đoạn
         */
        public int getExtractedSegments() {
            return extractedSegments;
        }

        /**
         * Đặt số lượng đoạn văn bản đã trích xuất
         * @param extractedSegments Số lượng đoạn
         */
        public void setExtractedSegments(int extractedSegments) {
            this.extractedSegments = extractedSegments;
        }
    }
} 