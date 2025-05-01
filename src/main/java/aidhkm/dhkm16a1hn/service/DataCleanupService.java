package aidhkm.dhkm16a1hn.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.logging.Logger;

/**
 * Service để tự động dọn dẹp và tối ưu hóa dữ liệu
 */
@Service
public class DataCleanupService {
    
    private static final Logger logger = Logger.getLogger(DataCleanupService.class.getName());
    
    @Autowired
    private VectorService vectorService;
    
    /**
     * Chạy định kỳ để dọn dẹp vector embeddings bị treo
     * Chạy mỗi ngày vào lúc 2 giờ sáng
     */
    @Scheduled(cron = "0 0 2 * * ?")
    @Transactional
    public void scheduledCleanupOrphanedEmbeddings() {
        logger.info("Bắt đầu quy trình dọn dẹp vector embeddings bị treo theo lịch");
        int deletedCount = vectorService.cleanupOrphanedEmbeddings();
        logger.info("Quy trình dọn dẹp vector embeddings bị treo đã hoàn tất. Đã xóa " + deletedCount + " vectors");
    }
    
    /**
     * Chạy thủ công để dọn dẹp vector embeddings bị treo
     * @return Số lượng vector đã xóa
     */
    @Transactional
    public int manualCleanupOrphanedEmbeddings() {
        logger.info("Bắt đầu quy trình dọn dẹp vector embeddings bị treo theo yêu cầu");
        int deletedCount = vectorService.cleanupOrphanedEmbeddings();
        logger.info("Quy trình dọn dẹp vector embeddings bị treo đã hoàn tất. Đã xóa " + deletedCount + " vectors");
        return deletedCount;
    }
} 