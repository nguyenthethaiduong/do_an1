package aidhkm.dhkm16a1hn.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import aidhkm.dhkm16a1hn.model.Document;
import aidhkm.dhkm16a1hn.repository.DocumentRepository;
import aidhkm.dhkm16a1hn.service.FileProcessingService;

@Service
public class PDFService {
    private static final Logger logger = Logger.getLogger(PDFService.class.getName());
    private static final int MAX_FILE_SIZE = 50 * 1024 * 1024; // 50MB
    private static final String PDF_CONTENT_TYPE = "application/pdf";
    
    private final Path uploadDir;
    
    @Autowired
    private VectorService vectorService;
    
    @Autowired
    private NLPService nlpService;

    @Autowired
    private DocumentRepository documentRepository;
    
    @Autowired
    private FileProcessingService fileProcessingService;

    public PDFService() {
        String uploadDirPath = System.getenv("UPLOAD_DIR");
        if (uploadDirPath == null || uploadDirPath.isEmpty()) {
            // Sử dụng thư mục tạm thời của hệ thống nếu không có biến môi trường
            uploadDir = Paths.get(System.getProperty("java.io.tmpdir"), "pdf-uploads");
            logger.info("Using temporary directory for PDF uploads: " + uploadDir);
        } else {
            // Sử dụng đường dẫn từ biến môi trường
            uploadDir = Paths.get(uploadDirPath, "pdf-uploads");
            logger.info("Using configured directory for PDF uploads: " + uploadDir);
        }
        
        try {
            if (!Files.exists(uploadDir)) {
                Files.createDirectories(uploadDir);
                logger.info("Created PDF upload directory at: " + uploadDir.toAbsolutePath());
            }
        } catch (IOException e) {
            logger.severe("Failed to create PDF upload directory: " + e.getMessage());
        }
    }

    /**
     * Kiểm tra tính hợp lệ của file PDF
     * @param file File cần kiểm tra
     * @return Thông báo lỗi hoặc null nếu file hợp lệ
     */
    public String validatePdfFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return "File không được để trống";
        }
        
        String contentType = file.getContentType();
        String fileName = file.getOriginalFilename();
        
        if (fileName != null && !fileName.toLowerCase().endsWith(".pdf")) {
            return "Chỉ chấp nhận file PDF";
        }
        
        if (contentType != null && !contentType.equals(PDF_CONTENT_TYPE) && !contentType.equals("application/octet-stream")) {
            return "Chỉ chấp nhận file PDF";
        }
        
        if (file.getSize() > MAX_FILE_SIZE) {
            return "Kích thước file không được vượt quá 50MB";
        }
        
        return null;
    }

    /**
     * Xử lý và tải lên file PDF
     * @param file File PDF từ request
     * @return Kết quả xử lý
     */
    public ProcessResult uploadAndProcessPdf(MultipartFile file) {
        ProcessResult result = new ProcessResult();
        
        // Kiểm tra tính hợp lệ
        String validationError = validatePdfFile(file);
        if (validationError != null) {
            result.setSuccess(false);
            result.setMessage(validationError);
            return result;
        }
        
        try {
            // Tạo tên file duy nhất
            String originalFilename = file.getOriginalFilename();
            String documentName = originalFilename != null ? originalFilename.replace(".pdf", "") : "Tài liệu PDF";
            String uniqueFileName = UUID.randomUUID().toString() + "_" + (originalFilename != null ? originalFilename : "document.pdf");
            Path targetPath = uploadDir.resolve(uniqueFileName);
            
            // Lưu file
            logger.info("Saving PDF file: " + uniqueFileName);
            File convFile = targetPath.toFile();
            try (FileOutputStream fos = new FileOutputStream(convFile)) {
                fos.write(file.getBytes());
            }
            result.setProgress(30);
            result.setSavedFilePath(targetPath.toString());
            
            // Trích xuất văn bản từ PDF
            String extractedText = extractTextFromPdf(convFile);
            if (extractedText == null || extractedText.trim().isEmpty()) {
                result.setSuccess(false);
                result.setMessage("Không thể trích xuất văn bản từ file PDF hoặc file không chứa văn bản");
                return result;
            }
            result.setProgress(60);
            
            // Đếm số trang của PDF
            int pageCount = countPdfPages(convFile);
            result.setTotalPages(pageCount);
            
            // Xử lý văn bản và tạo embedding
            int segmentCount = processTextContent(extractedText, documentName);
            if (segmentCount > 0) {
                result.setSuccess(true);
                result.setMessage("File PDF đã được xử lý thành công. Đã trích xuất " + segmentCount + " đoạn văn bản từ " + pageCount + " trang.");
                result.setProgress(100);
                result.setExtractedSegments(segmentCount);
                result.setProcessedPages(pageCount); // Đánh dấu tất cả các trang đã được xử lý
            } else {
                result.setSuccess(false);
                result.setMessage("Có lỗi xảy ra khi xử lý nội dung PDF");
            }
            
        } catch (IOException e) {
            logger.severe("Error processing PDF file: " + e.getMessage());
            result.setSuccess(false);
            result.setMessage("Lỗi khi xử lý file: " + e.getMessage());
        } catch (Exception e) {
            logger.severe("Unexpected error: " + e.getMessage());
            e.printStackTrace();
            result.setSuccess(false);
            result.setMessage("Lỗi không xác định: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * Trích xuất văn bản từ file PDF
     * @param pdfFile File PDF
     * @return Văn bản trích xuất
     */
    private String extractTextFromPdf(File pdfFile) {
        try (PDDocument document = Loader.loadPDF(pdfFile)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        } catch (IOException e) {
            logger.severe("Error extracting text from PDF: " + e.getMessage());
            return null;
        }
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
     * Đếm số trang trong file PDF
     * @param pdfFile File PDF
     * @return Số trang
     */
    private int countPdfPages(File pdfFile) {
        try (PDDocument document = Loader.loadPDF(pdfFile)) {
            return document.getNumberOfPages();
        } catch (IOException e) {
            logger.severe("Error counting PDF pages: " + e.getMessage());
            return 0;
        }
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
        private int totalPages;
        private int processedPages;
        
        public ProcessResult() {
            this.success = false;
            this.progress = 0;
            this.extractedSegments = 0;
            this.totalPages = 0;
            this.processedPages = 0;
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
        
        public int getTotalPages() {
            return totalPages;
        }
        
        public void setTotalPages(int totalPages) {
            this.totalPages = totalPages;
        }
        
        public int getProcessedPages() {
            return processedPages;
        }
        
        public void setProcessedPages(int processedPages) {
            this.processedPages = processedPages;
        }
    }
} 