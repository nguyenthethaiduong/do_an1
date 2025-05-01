package aidhkm.dhkm16a1hn.config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.logging.Logger;

@Component
public class LoggingConfiguration {
    private static final Logger logger = Logger.getLogger(LoggingConfiguration.class.getName());

    @PostConstruct
    public void init() {
        try {
            File logsDir = new File("logs");
            if (!logsDir.exists()) {
                boolean created = logsDir.mkdirs();
                if (created) {
                    logger.info("Thư mục logs đã được tạo thành công");
                } else {
                    logger.warning("Không thể tạo thư mục logs");
                }
            }
        } catch (Exception e) {
            logger.severe("Lỗi khi tạo thư mục logs: " + e.getMessage());
        }
    }
} 