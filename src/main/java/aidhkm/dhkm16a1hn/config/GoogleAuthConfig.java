package aidhkm.dhkm16a1hn.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cấu hình xác thực OAuth2 sử dụng Service Account cho Google Cloud API
 */
@Configuration
public class GoogleAuthConfig {
    private static final Logger logger = Logger.getLogger(GoogleAuthConfig.class.getName());
    
    private static final List<String> VERTEX_AI_SCOPES = Arrays.asList(
        "https://www.googleapis.com/auth/cloud-platform"
    );
    
    @Value("${google.service-account.file:service-account.json}")
    private String serviceAccountFile;
    
    @Value("${vertexai.project.id:future-footing-456806-q4}")
    private String configuredProjectId;
    
    /**
     * Cung cấp GoogleCredentials để sử dụng cho xác thực với Google API
     * @return GoogleCredentials được cấu hình từ service account
     */
    @Bean
    public GoogleCredentials googleCredentials() {
        try {
            // Kiểm tra biến môi trường GOOGLE_CREDENTIALS_JSON
            String googleCredentialsJson = System.getenv("GOOGLE_CREDENTIALS_JSON");
            if (googleCredentialsJson != null && !googleCredentialsJson.isEmpty()) {
                // Đọc credentials từ biến môi trường
                try (InputStream is = new ByteArrayInputStream(googleCredentialsJson.getBytes(StandardCharsets.UTF_8))) {
                    logger.info("Đang tải Google credentials từ biến môi trường GOOGLE_CREDENTIALS_JSON");
                    GoogleCredentials credentials = ServiceAccountCredentials.fromStream(is)
                        .createScoped(VERTEX_AI_SCOPES);
                    
                    if (credentials instanceof ServiceAccountCredentials) {
                        ServiceAccountCredentials serviceCredentials = (ServiceAccountCredentials) credentials;
                        logger.info("✅ Đã tải GoogleCredentials thành công từ service account: " + serviceCredentials.getClientEmail());
                        logger.info("✅ Project ID từ service account: " + serviceCredentials.getProjectId());
                        
                        // Kiểm tra project ID
                        String projectId = serviceCredentials.getProjectId();
                        if (projectId != null && !projectId.equals(configuredProjectId)) {
                            logger.warning("⚠️ CẢNH BÁO: Project ID trong service account (" + projectId + ") khác với project ID đã cấu hình (" + configuredProjectId + ")");
                            logger.warning("⚠️ Điều này có thể gây lỗi xác thực khi gọi API Vertex AI");
                        }
                    } else {
                        logger.info("✅ Đã tải GoogleCredentials thành công (không phải là ServiceAccountCredentials)");
                    }
                    
                    return credentials;
                }
            }
            
            // Nếu không có biến môi trường, thử tải từ classpath resources
            Resource resource = new ClassPathResource(serviceAccountFile);
            if (resource.exists()) {
                // Đọc file JSON để lấy project_id
                String projectId = extractProjectId(resource);
                if (projectId != null && !projectId.equals(configuredProjectId)) {
                    logger.warning("⚠️ CẢNH BÁO: Project ID trong service account (" + projectId + ") khác với project ID đã cấu hình (" + configuredProjectId + ")");
                    logger.warning("⚠️ Điều này có thể gây lỗi xác thực khi gọi API Vertex AI");
                    logger.warning("⚠️ Hãy đảm bảo file service-account.json đúng cho dự án " + configuredProjectId);
                }
                
                try (InputStream is = resource.getInputStream()) {
                    logger.info("Đang tải Google credentials từ file: " + serviceAccountFile);
                    GoogleCredentials credentials = ServiceAccountCredentials.fromStream(is)
                        .createScoped(VERTEX_AI_SCOPES);
                    
                    if (credentials instanceof ServiceAccountCredentials) {
                        ServiceAccountCredentials serviceCredentials = (ServiceAccountCredentials) credentials;
                        logger.info("✅ Đã tải GoogleCredentials thành công từ service account: " + serviceCredentials.getClientEmail());
                        logger.info("✅ Project ID từ service account: " + serviceCredentials.getProjectId());
                    } else {
                        logger.info("✅ Đã tải GoogleCredentials thành công (không phải là ServiceAccountCredentials)");
                    }
                    
                    return credentials;
                }
            } else {
                // Nếu không tìm thấy file, thử lấy từ biến môi trường GOOGLE_APPLICATION_CREDENTIALS
                logger.info("Không tìm thấy file credentials, thử sử dụng GOOGLE_APPLICATION_CREDENTIALS");
                GoogleCredentials credentials = GoogleCredentials.getApplicationDefault()
                    .createScoped(VERTEX_AI_SCOPES);
                logger.info("✅ Đã tải GoogleCredentials thành công từ Application Default Credentials");
                return credentials;
            }
        } catch (IOException e) {
            logger.severe("Lỗi khi tải Google credentials: " + e.getMessage());
            try {
                // Thử một lần nữa với application default credentials
                return GoogleCredentials.getApplicationDefault();
            } catch (IOException ex) {
                logger.severe("Không thể tải Google Application Default Credentials: " + ex.getMessage());
                throw new RuntimeException("Không thể tải Google credentials", ex);
            }
        }
    }
    
    /**
     * Trích xuất project_id từ file service account JSON
     */
    private String extractProjectId(Resource resource) {
        try (InputStream is = resource.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line);
            }
            
            // Sử dụng regex để tìm project_id
            Pattern pattern = Pattern.compile("\"project_id\"\\s*:\\s*\"([^\"]*)\"");
            Matcher matcher = pattern.matcher(content.toString());
            if (matcher.find()) {
                return matcher.group(1);
            }
        } catch (Exception e) {
            logger.warning("Không thể đọc project_id từ file service account: " + e.getMessage());
        }
        return null;
    }
} 