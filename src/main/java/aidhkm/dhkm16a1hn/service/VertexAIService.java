package aidhkm.dhkm16a1hn.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import aidhkm.dhkm16a1hn.config.GoogleAuthConfig;
import aidhkm.dhkm16a1hn.util.VectorUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.*;
import java.util.logging.Logger;
import java.util.concurrent.TimeUnit;
import java.util.Random;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Async;
import org.springframework.core.task.TaskExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.Arrays;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.net.URL;
import java.util.concurrent.ThreadLocalRandom;
import java.util.Arrays;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.io.StringWriter;
import java.io.PrintWriter;
import com.fasterxml.jackson.core.type.TypeReference;

@Service
public class VertexAIService {
    private static final Logger logger = Logger.getLogger(VertexAIService.class.getName());
    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY_MS = 1000;
    private static final int TOKEN_EXPIRATION_TIME_SECONDS = 3600;

    private static final String DEFAULT_MODEL = "models/gemini-1.5-pro";
    private static final String DEFAULT_EMBEDDING_MODEL = "text-embedding-005";

    @Value("${vertexai.project.id:future-footing-456806-q4}")
    private String projectId;

    @Value("${vertexai.location:us-central1}")
    private String location;

    @Value("${vertexai.chat.model:models/gemini-1.5-pro}")
    private String modelName;

    @Value("${vertexai.embedding.model:text-embedding-005}")
    private String embeddingModelName;

    @Autowired
    private GoogleCredentials googleCredentials;
    private String accessToken;
    private long tokenExpirationTime;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    private final Map<String, String> responseCache = new LinkedHashMap<String, String>(100, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 500;
        }
    };

    private final Map<String, float[]> embeddingCache = new ConcurrentHashMap<>();
    private static final int EMBEDDING_CACHE_SIZE = 2000;

    /**
     * Khởi tạo dịch vụ Vertex AI
     * Phương thức này thiết lập các thành phần cần thiết như WebClient 
     * và ObjectMapper để tương tác với API Vertex AI
     */
    public VertexAIService() {
        this.webClient = WebClient.builder()
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
                
        // Cấu hình ObjectMapper cho xử lý UTF-8 phù hợp
        this.objectMapper = new ObjectMapper();
        // Đảm bảo mã hóa UTF-8 đúng
        this.objectMapper.configure(com.fasterxml.jackson.core.JsonGenerator.Feature.ESCAPE_NON_ASCII, false);
        this.objectMapper.configure(com.fasterxml.jackson.core.JsonParser.Feature.AUTO_CLOSE_SOURCE, true);
    }

    @PostConstruct
    public void init() {
        logger.info("Khởi tạo VertexAIService với model: " + modelName);
        logger.info("Embedding model: " + embeddingModelName);
        logger.info("Project ID: " + projectId);
        logger.info("Location: " + location);
        
        try {
            // Xóa token cũ (nếu có) để đảm bảo luôn dùng token mới
            this.accessToken = null;
            this.tokenExpirationTime = 0;
            
            // Kiểm tra xem GoogleCredentials có sẵn sàng không
            if (googleCredentials == null) {
                logger.warning("GoogleCredentials không được inject, thử khởi tạo thủ công...");
                try {
                    initializeGoogleCredentials();
                } catch (Exception e) {
                    logger.severe("Không thể khởi tạo GoogleCredentials thủ công: " + e.getMessage());
                    throw new RuntimeException("GoogleCredentials chưa được cấu hình đúng", e);
                }
            }
            
            // Kiểm tra xem GoogleCredentials có phải là ServiceAccountCredentials không
            if (googleCredentials instanceof ServiceAccountCredentials) {
                ServiceAccountCredentials serviceCredentials = (ServiceAccountCredentials) googleCredentials;
                String credentialProjectId = serviceCredentials.getProjectId();
                
                // Kiểm tra nếu project ID trong credentials khác với cấu hình
                if (credentialProjectId != null && !credentialProjectId.equals(projectId)) {
                    logger.warning("⚠️ CẢNH BÁO: Service account thuộc về dự án (" + credentialProjectId + 
                                 ") khác với project ID trong cấu hình (" + projectId + ")");
                    logger.warning("⚠️ Sử dụng project ID từ cấu hình: " + projectId);
                    // Không thay đổi project ID
                }
            }
            
            // Làm mới token OAuth2 ban đầu
            refreshAccessToken();
            
            logger.info("Khởi tạo xác thực OAuth2 thành công với token: " + accessToken.substring(0, 10) + "...");
            logger.info("Sử dụng Project ID: " + projectId + " cho các API calls");
        } catch (Exception e) {
            logger.severe("Lỗi khi khởi tạo xác thực OAuth2: " + e.getMessage());
            logger.severe("Hãy đảm bảo biến môi trường GOOGLE_CREDENTIALS_JSON hợp lệ hoặc tệp service-account.json có quyền truy cập Vertex AI API");
            logger.severe("Vui lòng kiểm tra biến môi trường GOOGLE_CREDENTIALS_JSON, GOOGLE_APPLICATION_CREDENTIALS hoặc file service-account.json");
        }
    }
    
    /**
     * Khởi tạo GoogleCredentials thủ công nếu không được inject
     */
    private void initializeGoogleCredentials() throws IOException {
        logger.info("Đang khởi tạo GoogleCredentials thủ công...");
        
        try {
            // Thử tải từ biến môi trường GOOGLE_CREDENTIALS_JSON
            String googleCredentialsJson = System.getenv("GOOGLE_CREDENTIALS_JSON");
            if (googleCredentialsJson != null && !googleCredentialsJson.isEmpty()) {
                logger.info("Đang tải Google credentials từ biến môi trường GOOGLE_CREDENTIALS_JSON");
                try (InputStream is = new ByteArrayInputStream(googleCredentialsJson.getBytes(StandardCharsets.UTF_8))) {
                    List<String> scopes = Arrays.asList("https://www.googleapis.com/auth/cloud-platform");
                    this.googleCredentials = ServiceAccountCredentials.fromStream(is)
                        .createScoped(scopes);
                    return;
                }
            }
            
            // Thử tải từ classpath resources
            InputStream serviceAccountStream = getClass().getClassLoader().getResourceAsStream("service-account.json");
            if (serviceAccountStream != null) {
                logger.info("Đang tải Google credentials từ file service-account.json");
                List<String> scopes = Arrays.asList("https://www.googleapis.com/auth/cloud-platform");
                this.googleCredentials = ServiceAccountCredentials.fromStream(serviceAccountStream)
                    .createScoped(scopes);
                return;
            }
            
            // Thử dùng Application Default Credentials
            logger.info("Thử dùng Application Default Credentials (GOOGLE_APPLICATION_CREDENTIALS)");
            this.googleCredentials = GoogleCredentials.getApplicationDefault()
                .createScoped(Arrays.asList("https://www.googleapis.com/auth/cloud-platform"));
            
        } catch (IOException e) {
            logger.severe("Không thể khởi tạo GoogleCredentials: " + e.getMessage());
            throw e;
        }
    }
    
    /**
     * Làm mới OAuth2 token sử dụng GoogleCredentials
     */
    private void refreshAccessToken() {
        try {
            // Sử dụng GoogleCredentials để lấy token mới
            this.googleCredentials.refreshIfExpired();
            
            // Lấy token mới từ GoogleCredentials
            com.google.auth.oauth2.AccessToken newToken = this.googleCredentials.getAccessToken();
            
            if (newToken != null) {
                this.accessToken = newToken.getTokenValue();
                // Tính thời gian hết hạn: Lấy thời gian hết hạn từ token hoặc mặc định là 1 giờ
                this.tokenExpirationTime = newToken.getExpirationTime() != null 
                    ? newToken.getExpirationTime().getTime() / 1000  // convert to seconds
                    : System.currentTimeMillis() / 1000 + TOKEN_EXPIRATION_TIME_SECONDS;
                
                logger.info("OAuth2 token đã được làm mới, sẽ hết hạn sau " + 
                           (tokenExpirationTime - System.currentTimeMillis()/1000) + " giây");

                // Kiểm tra xem token này thuộc về dự án nào
                verifyProjectId();
            } else {
                logger.severe("Không thể lấy token mới - token trả về là null");
                throw new RuntimeException("Không thể lấy token xác thực từ GoogleCredentials");
            }
        } catch (IOException e) {
            logger.severe("Lỗi khi làm mới OAuth2 token: " + e.getMessage());
            if (e.getMessage() != null && e.getMessage().contains("401 Unauthorized")) {
                logger.severe("Lỗi xác thực 401 - Vui lòng kiểm tra lại service account credentials");
            }
            throw new RuntimeException("Không thể làm mới OAuth2 token: " + e.getMessage(), e);
        }
    }

    /**
     * Kiểm tra xác minh project ID từ token với project ID từ cấu hình
     */
    private void verifyProjectId() {
        try {
            // Giải mã JWT token để xem thuộc về dự án nào
            String[] parts = accessToken.split("\\.");
            if (parts.length < 2) {
                logger.warning("Token không có định dạng JWT: không thể xác định project ID từ token");
                return;
            }
            
            // Giải mã phần payload của JWT
            String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
            if (payload.contains("1012560367704")) {
                logger.warning("⚠️ CẢNH BÁO: Service account thuộc về dự án 1012560367704 nhưng API được cấu hình để gọi với dự án " + projectId);
                logger.warning("⚠️ Sẽ sử dụng dự án " + projectId + " từ cấu hình cho các API calls");
            }
        } catch (Exception e) {
            logger.warning("Không thể xác minh project ID từ token: " + e.getMessage());
        }
    }
    
    /**
     * Lấy token OAuth2 đang hoạt động, tự động làm mới nếu cần
     */
    private String getAccessToken() {
        // Kiểm tra xem token đã hết hạn chưa
        if (isAccessTokenExpired()) {
            logger.info("Token đã hết hạn hoặc chưa được khởi tạo, đang làm mới...");
            refreshAccessToken();
        }
        return accessToken;
    }

    /**
     * Tạo văn bản từ mô hình Vertex AI Gemini
     * Phương thức này gửi prompt đến API Vertex AI để tạo phản hồi,
     * xử lý bộ nhớ đệm và trả về văn bản được tạo
     * 
     * @param prompt Nội dung prompt
     * @return Văn bản được tạo
     */
    public String generateText(String prompt) {
        long startTime = System.currentTimeMillis();
        try {
            logger.info("Đang xử lý yêu cầu tạo văn bản");
            
            // Kiểm tra cache
            String cacheKey = String.valueOf(prompt.hashCode());
            if (responseCache.containsKey(cacheKey)) {
                logger.info("Tìm thấy kết quả trong bộ nhớ đệm");
                logger.info("Thời gian lấy từ bộ nhớ đệm: " + (System.currentTimeMillis() - startTime) + "ms");
                return responseCache.get(cacheKey);
            }
            
            // Xây dựng yêu cầu
            String requestBody = buildVertexAIRequest(prompt);
            logger.info("Body yêu cầu tạo văn bản (đã cắt ngắn): " + 
                requestBody.substring(0, Math.min(requestBody.length(), 500)) + 
                (requestBody.length() > 500 ? "..." : ""));
            
            // Gọi API với retry logic
            long apiCallStart = System.currentTimeMillis();
            String response = callVertexAPIWithRetry(requestBody);
            logger.info("Thời gian gọi API: " + (System.currentTimeMillis() - apiCallStart) + "ms");
            
            // Xử lý phản hồi
            long parseStart = System.currentTimeMillis();
            String generatedText = response;
            if (response != null && response.startsWith("{")) {
                try {
                    JsonNode jsonResponse = objectMapper.readTree(response);
                    generatedText = extractTextFromResponse(jsonResponse);
                } catch (Exception e) {
                    StringWriter sw = new StringWriter();
                    e.printStackTrace(new PrintWriter(sw));
                    logger.severe("Lỗi phân tích phản hồi JSON: " + e.getMessage());
                    logger.severe("Stack trace phân tích JSON: " + sw.toString());
                    logger.severe("Phản hồi thô (đã cắt ngắn): " + 
                        (response != null ? response.substring(0, Math.min(response.length(), 1000)) + "..." : "null"));
                }
            }
            logger.info("Thời gian phân tích phản hồi: " + (System.currentTimeMillis() - parseStart) + "ms");
            
            // Lưu vào cache
            if (generatedText != null) {
                responseCache.put(cacheKey, generatedText);
            }
            
            logger.info("Tổng thời gian tạo văn bản: " + (System.currentTimeMillis() - startTime) + "ms");
            return generatedText != null ? generatedText : "Xin lỗi, tôi không thể tạo phản hồi lúc này.";
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            logger.severe("Lỗi khi tạo văn bản: " + e.getMessage());
            logger.severe("Stack trace lỗi tạo văn bản: " + sw.toString());
            return "Xin lỗi, tôi không thể tạo phản hồi lúc này.";
        }
    }

    /**
     * Trích xuất văn bản từ phản hồi JSON
     * Phương thức này phân tích cấu trúc JSON trả về từ API Vertex AI
     * để lấy nội dung văn bản được tạo ra
     * 
     * @param jsonResponse Đối tượng JsonNode chứa phản hồi từ API
     * @return Văn bản đã được trích xuất hoặc null nếu có lỗi
     */
    private String extractTextFromResponse(JsonNode jsonResponse) {
        try {
            logger.info("Phân tích phản hồi: " + jsonResponse.toString().substring(0, Math.min(200, jsonResponse.toString().length())) + "...");
            
            if (jsonResponse.has("candidates") && jsonResponse.get("candidates").size() > 0) {
                JsonNode candidate = jsonResponse.get("candidates").get(0);
                
                if (candidate.has("content") && 
                    candidate.get("content").has("parts") && 
                    candidate.get("content").get("parts").size() > 0) {
                    
                    String generatedText = candidate.get("content")
                                             .get("parts")
                                             .get(0)
                                             .get("text")
                                             .asText();
                    
                    logger.info("Đã nhận phản hồi từ Gemini 1.5 Pro");
                    return generatedText;
                }
            }
            
            // Fallback: thử cấu trúc cũ
            if (jsonResponse.has("predictions") && jsonResponse.get("predictions").size() > 0) {
                JsonNode prediction = jsonResponse.get("predictions").get(0);
                
                if (prediction.has("content") && 
                    prediction.get("content").has("parts") && 
                    prediction.get("content").get("parts").size() > 0) {
                    
                    String generatedText = prediction.get("content")
                                               .get("parts")
                                               .get(0)
                                               .get("text")
                                               .asText();
                    
                    logger.info("Đã nhận phản hồi từ Gemini 1.5 Pro (định dạng cũ)");
                    return generatedText;
                }
            }
            
            logger.warning("Định dạng phản hồi không hợp lệ từ API Gemini 1.5 Pro: " + jsonResponse.toString().substring(0, Math.min(500, jsonResponse.toString().length())));
            return null;
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            logger.severe("Lỗi khi trích xuất văn bản từ phản hồi: " + e.getMessage());
            logger.severe("Stack trace đầy đủ: " + sw.toString());
            return null;
        }
    }

    /**
     * Xây dựng yêu cầu JSON cho API Vertex AI
     * Phương thức này tạo cấu trúc JSON phù hợp cho yêu cầu tạo văn bản,
     * bao gồm prompt và các thông số cấu hình
     * 
     * @param prompt Nội dung prompt đầu vào
     * @return Chuỗi JSON đại diện cho yêu cầu
     */
    private String buildVertexAIRequest(String prompt) {
        ObjectNode rootNode = objectMapper.createObjectNode();
        
        // Tạo mảng contents
        ArrayNode contentsArray = objectMapper.createArrayNode();
        
        // Tạo content object
        ObjectNode contentObj = objectMapper.createObjectNode();
        contentObj.put("role", "user"); // Thêm role user
        
        // Tạo parts array
        ArrayNode parts = objectMapper.createArrayNode();
        
        // Part object với text
        ObjectNode part = objectMapper.createObjectNode();
        part.put("text", prompt);
        parts.add(part);
        
        contentObj.set("parts", parts);
        contentsArray.add(contentObj);
        
        // Đặt mảng contents vào root
        rootNode.set("contents", contentsArray);
        
        // Cấu hình tạo văn bản
        ObjectNode generationConfig = objectMapper.createObjectNode();
        generationConfig.put("temperature", 0.1);
        generationConfig.put("maxOutputTokens", 500);
        generationConfig.put("topP", 0.95);
        generationConfig.put("topK", 40);
        
        rootNode.set("generationConfig", generationConfig);
        
        try {
            String json = objectMapper.writeValueAsString(rootNode);
            logger.info("Đã tạo JSON Request: " + json);
            return json;
        } catch (JsonProcessingException e) {
            logger.severe("Lỗi chuyển đổi yêu cầu: " + e.getMessage());
            return "{}";
        }
    }

    /**
     * Gọi API Vertex AI với cơ chế retry - phương thức đơn giản hóa
     * Phương thức này sử dụng cơ chế thử lại để đảm bảo tính ổn định
     * khi gặp các lỗi tạm thời từ API Vertex AI
     * 
     * @param requestData Dữ liệu request
     * @return Kết quả từ API
     */
    private String callVertexAPIWithRetry(String requestData) {
        // Lấy project ID thực từ credentials
        String actualProjectId = getActualProjectIdFromCredentials();
        
        // Xây dựng endpoint URL cho Vertex AI - định dạng URL tùy thuộc vào loại model
        String endpoint;
        String modelNameForUrl = modelName;
        
        // Kiểm tra nếu model bắt đầu bằng "models/"
        if (modelName.startsWith("models/")) {
            // Loại bỏ tiền tố "models/" để sử dụng trong URL
            modelNameForUrl = modelName.substring("models/".length());
            logger.info("Removing 'models/' prefix from model name for URL: " + modelNameForUrl);
        }
        
        if (modelName.contains("gemini")) {
            // For all Gemini models we should use the generateContent endpoint
            endpoint = String.format(
                "https://%s-aiplatform.googleapis.com/v1/projects/%s/locations/%s/publishers/google/models/%s:generateContent",
                location, actualProjectId, location, modelNameForUrl);
            
            logger.info("Sử dụng endpoint API Gemini với hậu tố :generateContent");
        } else {
            // Các model khác dùng định dạng URL thông thường với ":predict"
            endpoint = String.format(
                "https://%s-aiplatform.googleapis.com/v1/projects/%s/locations/%s/publishers/google/models/%s:predict",
                location, actualProjectId, location, modelNameForUrl);
        }
        
        logger.info("Gọi API Vertex AI tại endpoint: " + endpoint);
        logger.info("Sử dụng project ID: " + actualProjectId + " cho gọi API Vertex AI");
        
        // Gọi phương thức đầy đủ với giá trị mặc định
        return callVertexAPIWithRetry(endpoint, requestData, 0, MAX_RETRIES);
    }

    /**
     * Lấy project ID thực tế từ GoogleCredentials
     * Phương thức này trả về project ID từ credentials nếu có,
     * nếu không sẽ sử dụng project ID từ cấu hình
     * 
     * @return Project ID từ credentials hoặc từ cấu hình nếu không lấy được
     */
    private String getActualProjectIdFromCredentials() {
        String actualProjectId = projectId; // giá trị mặc định từ cấu hình
        
        try {
            if (googleCredentials instanceof ServiceAccountCredentials) {
                ServiceAccountCredentials serviceCredentials = (ServiceAccountCredentials) googleCredentials;
                if (serviceCredentials.getProjectId() != null && !serviceCredentials.getProjectId().isEmpty()) {
                    String credentialProjectId = serviceCredentials.getProjectId();
                    if (!credentialProjectId.equals(projectId)) {
                        logger.warning("⚠️ Project ID từ credentials (" + credentialProjectId + 
                            ") khác với cấu hình (" + projectId + "), sử dụng ID từ cấu hình cho API call");
                    }
                }
            }
        } catch (Exception e) {
            logger.warning("Không thể lấy project ID từ GoogleCredentials: " + e.getMessage());
        }
        
        logger.info("Sử dụng project ID từ cấu hình: " + actualProjectId);
        return actualProjectId;
    }

    /**
     * Gọi API Vertex AI với cơ chế retry
     * Phương thức này sử dụng HttpURLConnection để gọi API Vertex AI
     * với cơ chế thử lại khi gặp lỗi và xử lý các loại lỗi khác nhau
     * 
     * @param endpoint Đường dẫn endpoint
     * @param requestData Dữ liệu request
     * @param retryCount Số lần retry hiện tại
     * @param maxRetries Số lần retry tối đa
     * @return Kết quả từ API
     */
    private String callVertexAPIWithRetry(String endpoint, String requestData, int retryCount, int maxRetries) {
        try {
            if (isAccessTokenExpired()) {
                refreshAccessToken();
            }

            long startTime = System.currentTimeMillis();
            URL url = new URL(endpoint);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + accessToken);
            connection.setDoOutput(true);

            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = requestData.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = connection.getResponseCode();
            String responseBody;

            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
                        response.append(responseLine.trim());
                    }
                    responseBody = response.toString();
                }
                
                long endTime = System.currentTimeMillis();
                logger.info("Gọi API Vertex AI thành công, mất " + (endTime - startTime) + "ms");
                
                return responseBody;
            } else {
                // Improved error handling to prevent NullPointerException
                InputStream errorStream = connection.getErrorStream();
                if (errorStream != null) {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(errorStream, StandardCharsets.UTF_8))) {
                        StringBuilder response = new StringBuilder();
                        String responseLine;
                        while ((responseLine = br.readLine()) != null) {
                            response.append(responseLine.trim());
                        }
                        responseBody = response.toString();
                    }
                } else {
                    // No error stream available, create a default error message
                    responseBody = "Không có chi tiết lỗi (error stream là null)";
                    logger.warning("Error stream là null cho mã phản hồi HTTP: " + responseCode);
                }
                
                logger.warning("Lỗi API Vertex AI: " + responseCode + " - " + responseBody);
                
                // Xử lý lỗi xác thực (401)
                if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    logger.warning("Lỗi xác thực (401): " + responseBody);
                    if (retryCount < maxRetries) {
                        logger.info("Làm mới token và thử lại API call (" + (retryCount + 1) + "/" + maxRetries + ")");
                        refreshAccessToken(); // Force token refresh
                        return callVertexAPIWithRetry(endpoint, requestData, retryCount + 1, maxRetries);
                    } else {
                        logger.severe("Đã đạt số lần thử lại tối đa cho lỗi xác thực");
                        throw new RuntimeException("Xác thực thất bại sau " + maxRetries + " lần thử");
                    }
                }
                
                // Xử lý lỗi quota hoặc rate limit (429 or 503)
                if (responseCode == 429 || responseCode == 503) {
                    if (retryCount < maxRetries) {
                        int sleepTime = Math.min(1000 * (int) Math.pow(2, retryCount), 32000);
                        logger.info("Hạn chế tỷ lệ hoặc vượt quota, thử lại sau " + sleepTime + "ms (" + (retryCount + 1) + "/" + maxRetries + ")");
                        Thread.sleep(sleepTime);
                        return callVertexAPIWithRetry(endpoint, requestData, retryCount + 1, maxRetries);
                    } else {
                        logger.severe("Đã đạt số lần thử lại tối đa cho lỗi rate limit/quota");
                    }
                }
                
                // Các lỗi khác
                throw new RuntimeException("Lỗi API Vertex AI: " + responseCode + " - " + responseBody);
            }
        } catch (Exception e) {
            if (e instanceof RuntimeException && e.getMessage() != null && e.getMessage().startsWith("Lỗi API Vertex AI:")) {
                throw (RuntimeException) e;
            }
            
            logger.severe("Lỗi khi gọi API Vertex AI: " + e.getMessage());
            if (retryCount < maxRetries) {
                try {
                    int sleepTime = Math.min(1000 * (int) Math.pow(2, retryCount), 32000);
                    logger.info("Thử lại API call sau lỗi (" + (retryCount + 1) + "/" + maxRetries + "), đợi " + sleepTime + "ms");
                    Thread.sleep(sleepTime);
                    return callVertexAPIWithRetry(endpoint, requestData, retryCount + 1, maxRetries);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("API call bị gián đoạn trong quá trình chờ thử lại", ie);
                }
            } else {
                logger.severe("Đã đạt số lần thử lại tối đa cho API call");
                // Log thêm stack trace đầy đủ
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                logger.severe("Stack trace lỗi đầy đủ: " + sw.toString());
                throw new RuntimeException("Không thể gọi API Vertex AI sau " + maxRetries + " lần thử", e);
            }
        }
    }

    /**
     * Kiểm tra xem token truy cập OAuth đã hết hạn chưa
     * Phương thức này kiểm tra thời gian hết hạn của token và
     * thêm buffer 5 phút để tránh sử dụng token gần hết hạn
     * 
     * @return true nếu token đã hết hạn hoặc sắp hết hạn, false nếu còn hiệu lực
     */
    private boolean isAccessTokenExpired() {
        long currentTimeSeconds = System.currentTimeMillis() / 1000;
        boolean isExpired = accessToken == null || currentTimeSeconds >= tokenExpirationTime;
        
        // Thêm buffer 5 phút để tránh sử dụng token gần hết hạn
        if (!isExpired && tokenExpirationTime - currentTimeSeconds < 300) {
            logger.info("Token sắp hết hạn (còn " + (tokenExpirationTime - currentTimeSeconds) + " giây), coi như đã hết hạn");
            return true;
        }
        
        return isExpired;
    }

    /**
     * Tạo yêu cầu JSON cho việc tạo vector nhúng dựa trên văn bản
     * Phương thức này xây dựng cấu trúc JSON phù hợp cho mô hình text-embedding
     * 
     * @param text Văn bản cần tạo vector nhúng
     * @return Đối tượng ObjectNode chứa yêu cầu đã được định dạng
     */
    private ObjectNode buildEmbeddingRequest(String text) {
        logger.info("Building embedding request for text: " + text);
        
        // Chuẩn hóa văn bản ngay từ đầu để đảm bảo xử lý UTF-8 nhất quán
        String normalizedText = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC);
        normalizedText = normalizedText.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "").trim();
        
        logger.info("Văn bản sau khi chuẩn hóa: " + normalizedText);
        
        // Đảm bảo có văn bản hợp lệ, sử dụng placeholder nếu rỗng
        if (normalizedText == null || normalizedText.isEmpty()) {
            normalizedText = "placeholder_text";
            logger.warning("Văn bản rỗng sau khi chuẩn hóa, sử dụng placeholder: " + normalizedText);
        }
        
        // Đảm bảo mã hóa UTF-8 chính xác cho văn bản
        String encodedText;
        try {
            // Mã hóa lại văn bản để đảm bảo xử lý UTF-8 chính xác
            byte[] bytes = normalizedText.getBytes(StandardCharsets.UTF_8);
            logger.info("Byte UTF-8 thô: " + bytesToHex(bytes));
            
            encodedText = new String(bytes, StandardCharsets.UTF_8);
            logger.info("Văn bản sau khi mã hóa UTF-8: " + encodedText);
            
            // Kiểm tra kết quả mã hóa
            if (encodedText.isEmpty() || !encodedText.equals(normalizedText)) {
                logger.warning("Mã hóa UTF-8 đã thay đổi văn bản! Gốc: '" + normalizedText + 
                             "', Đã mã hóa: '" + encodedText + "'");
                
                if (encodedText.isEmpty()) {
                    encodedText = "placeholder_text_after_encoding";
                    logger.warning("Sử dụng placeholder sau khi kết quả mã hóa rỗng: " + encodedText);
                }
            }
        } catch (Exception e) {
            logger.warning("Lỗi khi mã hóa văn bản thành UTF-8, sử dụng văn bản gốc: " + e.getMessage());
            encodedText = normalizedText;
        }
        
        // Tạo cấu trúc yêu cầu sử dụng ObjectNode của Jackson
        ObjectNode rootNode = objectMapper.createObjectNode();
        ArrayNode instancesArray = objectMapper.createArrayNode();
        
        if (embeddingModelName.contains("text-embedding-005")) {
            // Cho mô hình text-embedding-005 - sử dụng định dạng chính xác { "content": "..." }
            ObjectNode instance = objectMapper.createObjectNode();
            instance.put("content", encodedText);  // Định dạng nội dung trực tiếp
            instancesArray.add(instance);
            
            // Thiết lập tham số
            ObjectNode parameters = objectMapper.createObjectNode();
            parameters.put("dimension", 768);
            
            rootNode.set("instances", instancesArray);
            rootNode.set("parameters", parameters);
            
            logger.info("Sử dụng định dạng text-embedding-005 chính xác: { \"content\": \"...\" }");
        } else {
            // Cho các mô hình cũ hơn như textembedding-gecko
            ObjectNode instance = objectMapper.createObjectNode();
            instance.put("content", encodedText);
            instancesArray.add(instance);
            rootNode.set("instances", instancesArray);
        }
        
        // Ghi log body yêu cầu để gỡ lỗi
        try {
            // Cấu hình ObjectMapper cho UTF-8
            String jsonBody = objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(rootNode);
            logger.info("Body yêu cầu đã chuẩn bị:\n" + jsonBody);
            
            // Xác thực cấu trúc JSON (chỉ để gỡ lỗi)
            if (jsonBody.contains("\"content\":\"" + encodedText + "\"")) {
                logger.info("✓ KIỂM TRA ĐỊNH DẠNG ĐẠT: JSON chứa văn bản tiếng Việt dự kiến được mã hóa chính xác");
            } else {
                logger.warning("✗ KIỂM TRA ĐỊNH DẠNG THẤT BẠI: JSON không chứa văn bản dự kiến: " + encodedText);
                logger.warning("Nội dung JSON: " + jsonBody);
            }
        } catch (Exception e) {
            logger.severe("Lỗi chuyển đổi body yêu cầu thành JSON: " + e.getMessage());
        }
        
        return rootNode;
    }

    /**
     * Tạo embedding vector từ văn bản sử dụng Vertex AI
     * Phương thức này gửi văn bản đến API Vertex AI để tạo vector nhúng
     * phù hợp cho việc so sánh ngữ nghĩa
     * 
     * @param text Văn bản đầu vào
     * @return Vector embedding
     */
    public float[] createEmbedding(String text) {
        // Xử lý đầu vào null ngay lập tức
        if (text == null) {
            logger.warning("Không thể tạo embedding cho văn bản null - sử dụng phương thức dự phòng");
            return createFallbackEmbedding("placeholder text");
        }
        
        // Chuẩn hóa văn bản và xử lý mã hóa UTF-8 ngay lập tức
        String normalizedText = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC);
        // Loại bỏ các ký tự điều khiển ngoại trừ xuống dòng và tab
        normalizedText = normalizedText.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "").trim();
        
        // Kiểm tra xem văn bản có trống sau khi chuẩn hóa không
        if (normalizedText.isEmpty()) {
            logger.warning("Văn bản trống sau khi chuẩn hóa - sử dụng phương thức dự phòng");
            return createFallbackEmbedding("placeholder text");
        }
        
        // Đảm bảo mã hóa UTF-8 chính xác
        String finalText;
        try {
            byte[] bytes = normalizedText.getBytes(StandardCharsets.UTF_8);
            // Ghi lại các byte thô để gỡ lỗi
            logger.info("Raw UTF-8 bytes: " + bytesToHex(bytes));
            
            finalText = new String(bytes, StandardCharsets.UTF_8);
            if (finalText.isEmpty()) {
                logger.warning("Văn bản trống sau khi mã hóa UTF-8 - sử dụng phương thức dự phòng");
                return createFallbackEmbedding("placeholder text");
            }
            logger.info("Văn bản sau khi chuẩn hóa và mã hóa UTF-8: " + finalText);
        } catch (Exception e) {
            logger.severe("Lỗi trong quá trình mã hóa UTF-8: " + e.getMessage());
            return createFallbackEmbedding("placeholder text");
        }
        
        // Giới hạn độ dài văn bản cho giới hạn API
        if (finalText.length() > 2048) {
            finalText = finalText.substring(0, 2048);
            logger.info("Văn bản được cắt ngắn còn 2048 ký tự");
        }
        
        // Xóa cache nếu nó có thể chứa kích thước không nhất quán
        if (embeddingCache.size() > 0) {
            // Kiểm tra kích thước của vector đầu tiên trong cache
            float[] sampleVector = embeddingCache.values().iterator().next();
            int expectedDimension = embeddingModelName.contains("text-embedding-005") ? 768 : 128;
            
            if (sampleVector.length != expectedDimension) {
                logger.warning("Đang xóa cache embedding do thay đổi kích thước - cũ: " + sampleVector.length + 
                              ", dự kiến: " + expectedDimension);
                embeddingCache.clear();
            }
        }
        
        // Tạo cache key từ text đã chuẩn hóa
        String cacheKey = finalText.trim().toLowerCase().hashCode() + "";
        
        // Kiểm tra cache trước khi gọi API
        if (embeddingCache.containsKey(cacheKey)) {
            float[] cachedVector = embeddingCache.get(cacheKey);
            logger.info("Sử dụng embedding từ cache cho văn bản: " + finalText.substring(0, Math.min(30, finalText.length())) + 
                       "... (kích thước: " + cachedVector.length + ")");
            return cachedVector;
        }
        
        try {
            long startTime = System.currentTimeMillis();
            
            // Chuẩn bị request body
            logger.info("Đang chuẩn bị yêu cầu với mô hình: " + embeddingModelName);
            ObjectNode requestNode = buildEmbeddingRequest(finalText);
            
            // Luôn sử dụng project ID từ cấu hình
            String actualProjectId = projectId;
            
            // Xác định URL endpoint cho text-embedding-005
            String apiUrl = String.format(
                "https://%s-aiplatform.googleapis.com/v1/projects/%s/locations/%s/publishers/google/models/%s:predict",
                location, actualProjectId, location, embeddingModelName);
            
            logger.info("Đang gọi API embedding với URL: " + apiUrl);
            
            // Kiểm tra URL API
            logger.info("KIỂM TRA URL API: " + 
                        (apiUrl.contains("publishers/google/models/" + embeddingModelName + ":predict") ? 
                         "✓ OK - URL chứa đường dẫn mô hình chính xác" : 
                         "✗ LỖI - URL không chứa đường dẫn mô hình chính xác"));
            
            // Lấy token OAuth2 hiện tại
            String token = getAccessToken();
            if (token == null || token.isEmpty()) {
                logger.severe("Không thể lấy token OAuth2");
                throw new RuntimeException("Không có token OAuth2 hợp lệ");
            }
            logger.info("Đã lấy token OAuth2");
            
            // Gọi API sử dụng HttpURLConnection thay vì WebClient
            URL url = new URL(apiUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setDoOutput(true);
            connection.setConnectTimeout(20000); // 20 giây timeout
            connection.setReadTimeout(20000);

            // Chuyển đổi ObjectNode thành chuỗi JSON với mã hóa UTF-8 rõ ràng
            String jsonRequest;
            try {
                // Sử dụng định dạng đẹp để dễ nhìn khi gỡ lỗi
                jsonRequest = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(requestNode);
                logger.info("Yêu cầu JSON cuối cùng với định dạng đẹp:\n" + jsonRequest);
            } catch (Exception e) {
                logger.severe("Lỗi khi chuyển đổi request thành JSON: " + e.getMessage());
                throw new RuntimeException("Không thể chuyển đổi request", e);
            }
            
            // Ghi lại chi tiết request để gỡ lỗi
            byte[] requestBytes = jsonRequest.getBytes(StandardCharsets.UTF_8);
            logger.info("Đang gửi yêu cầu (độ dài=" + requestBytes.length + " byte)");
            
            // Ghi lại toàn bộ chi tiết request để gỡ lỗi
            logger.info("CHI TIẾT YÊU CẦU ĐẦY ĐỦ:");
            logger.info("URL: " + apiUrl);
            logger.info("Phương thức: POST");
            logger.info("Headers:");
            logger.info("  Content-Type: application/json; charset=UTF-8");
            logger.info("  Accept: application/json");
            logger.info("  Authorization: Bearer " + token.substring(0, 10) + "...");
            logger.info("Body (UTF-8):\n" + jsonRequest);
            
            // Ghi nội dung request với mã hóa UTF-8
            try (OutputStream os = connection.getOutputStream()) {
                os.write(requestBytes);
                os.flush();
            }

            int statusCode = connection.getResponseCode();
            logger.info("Mã trạng thái phản hồi API: " + statusCode);
            
            String responseBody;
            
            // Đọc response
            if (statusCode == HttpURLConnection.HTTP_OK) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    responseBody = response.toString();
                }
                logger.info("Phản hồi API thành công: " + responseBody.substring(0, Math.min(100, responseBody.length())) + "...");
            } else {
                // Đọc error response
                try (BufferedReader reader = new BufferedReader(
                         new InputStreamReader(
                             connection.getErrorStream() != null ? connection.getErrorStream() : new ByteArrayInputStream(new byte[0]), 
                             StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    responseBody = response.toString();
                }
                logger.severe("Trạng thái lỗi API: " + statusCode);
                logger.severe("Phản hồi lỗi API: " + responseBody);
                
                // Phân tích lỗi chi tiết và log thông tin request gây lỗi
                try {
                    JsonNode errorNode = objectMapper.readTree(responseBody);
                    if (errorNode.has("error") && errorNode.get("error").has("message")) {
                        String errorMessage = errorNode.get("error").get("message").asText();
                        logger.severe("Thông báo lỗi API: " + errorMessage);
                        
                        // Log the request that caused the error for debugging
                        logger.severe("Yêu cầu gây ra lỗi: " + jsonRequest);
                        
                        if (errorMessage.contains("format") || errorMessage.contains("schema")) {
                            logger.severe("Có vẻ là LỖI ĐỊNH DẠNG. Kiểm tra cấu trúc JSON.");
                        } else if (errorMessage.contains("empty") || errorMessage.contains("content")) {
                            logger.severe("Có vẻ là LỖI NỘI DUNG. Kiểm tra văn bản tiếng Việt có được mã hóa đúng không.");
                        } else if (errorMessage.contains("unauthorized") || errorMessage.contains("permission")) {
                            logger.severe("Có vẻ là LỖI XÁC THỰC. Kiểm tra thông tin đăng nhập của bạn.");
                        }
                    }
                } catch (Exception e) {
                    logger.warning("Không thể phân tích JSON phản hồi lỗi: " + e.getMessage());
                }
                
                throw new RuntimeException("Lỗi khi gọi API: " + statusCode + " - " + responseBody);
            }

            long endTime = System.currentTimeMillis();
            logger.info("Thời gian gọi API embedding: " + (endTime - startTime) + "ms");

            // Xử lý response JSON
            if (responseBody != null && !responseBody.isEmpty()) {
                logger.info("Đã nhận phản hồi: " + responseBody.substring(0, Math.min(100, responseBody.length())) + "...");
                
                ObjectMapper mapper = new ObjectMapper();
                JsonNode rootNode = mapper.readTree(responseBody);
                
                if (rootNode.has("predictions") && rootNode.get("predictions").size() > 0) {
                    JsonNode prediction = rootNode.get("predictions").get(0);
                    
                    // Định dạng phản hồi cho text-embedding-005
                    if (prediction.has("embeddings") && prediction.get("embeddings").has("values")) {
                        // Định dạng cho text-embedding-005
                        JsonNode valuesNode = prediction.get("embeddings").get("values");
                        float[] embedding = new float[valuesNode.size()];
                        
                        for (int i = 0; i < embedding.length; i++) {
                            embedding[i] = (float) valuesNode.get(i).asDouble();
                        }
                        
                        // Thêm kết quả vào cache
                        synchronized(embeddingCache) {
                            if (embeddingCache.size() >= EMBEDDING_CACHE_SIZE) {
                                String oldestKey = embeddingCache.keySet().iterator().next();
                                embeddingCache.remove(oldestKey);
                            }
                            embeddingCache.put(cacheKey, embedding);
                        }

                        logger.info("Đã tạo thành công vector nhúng với " + embedding.length + " chiều sử dụng định dạng embeddings.values");
                        return embedding;
                    }
                    else if (prediction.has("values")) {
                        // Format trực tiếp "values" cho các model khác
                        JsonNode valuesNode = prediction.get("values");
                        float[] embedding = new float[valuesNode.size()];

                        for (int i = 0; i < embedding.length; i++) {
                            embedding[i] = (float) valuesNode.get(i).asDouble();
                        }
                        
                        // Thêm kết quả vào cache
                        synchronized(embeddingCache) {
                            if (embeddingCache.size() >= EMBEDDING_CACHE_SIZE) {
                                String oldestKey = embeddingCache.keySet().iterator().next();
                                embeddingCache.remove(oldestKey);
                            }
                            embeddingCache.put(cacheKey, embedding);
                        }
                        
                        logger.info("Đã tạo thành công vector nhúng với " + embedding.length + " chiều sử dụng định dạng values");
                        return embedding;
                    }
                    // In ra cấu trúc JSON response để debug
                    logger.warning("Cấu trúc JSON phản hồi không mong đợi: " + prediction.toString());
                } else {
                    logger.warning("Thiếu predictions trong phản hồi - kiểm tra phản hồi đầy đủ: " + responseBody);
                }
                
                logger.warning("Định dạng phản hồi không hợp lệ từ API embedding: " + responseBody);
            } else {
                logger.warning("Nhận phản hồi null hoặc rỗng từ API embedding");
            }

            // Nếu không thành công, sử dụng embedding đơn giản
            logger.warning("Không thể tạo embedding qua API, sử dụng phương thức dự phòng");
            return createFallbackEmbedding(finalText);
            
        } catch (Exception e) {
            logger.severe("Lỗi khi tạo embedding: " + e.getMessage());
            if (e.getCause() != null) {
                logger.severe("Nguyên nhân gốc: " + e.getCause().getMessage());
            }
            // Ghi traceback đầy đủ vào log
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            logger.severe("Stack trace: " + sw.toString());
            
            return createFallbackEmbedding(finalText);
        }
    }

    /**
     * Phương thức tạo embedding dự phòng khi API không khả dụng
     * @param text Văn bản cần tạo embedding
     * @return Mảng float đại diện cho embedding vector
     */
    public float[] createFallbackEmbedding(String text) {
        logger.info("Creating fallback embedding for text");
        
        try {
            // Chuẩn hóa văn bản
            text = text.toLowerCase().replaceAll("[^a-z0-9\\s]", " ");
            String[] words = text.split("\\s+");
            
            // Tạo vector với kích thước cố định
            int vectorSize = 128;
            float[] embeddingVector = new float[vectorSize];

            // Tạo vector từ các từ trong văn bản
            for (String word : words) {
                if (word.trim().isEmpty()) continue;

                // Sử dụng hàm băm của từ để tạo giá trị
                int hash = word.hashCode();
                int position = Math.abs(hash % vectorSize);

                // Tạo giá trị từ hash
                float value = (float) Math.sin(hash) * 0.1f;

                // Cập nhật giá trị vào vector
                embeddingVector[position] += value;
            }

            // Chuẩn hóa vector
            float magnitude = 0;
            for (float val : embeddingVector) {
                magnitude += val * val;
            }
            magnitude = (float) Math.sqrt(magnitude);

            if (magnitude > 0) {
                for (int i = 0; i < embeddingVector.length; i++) {
                    embeddingVector[i] = embeddingVector[i] / magnitude;
                }
            }

            logger.info("Successfully created fallback embedding with " + vectorSize + " dimensions");
            return embeddingVector;

        } catch (Exception e) {
            logger.severe("Error creating fallback embedding: " + e.getMessage());
            return new float[128]; // Return empty vector in case of error
        }
    }

    /**
     * Lấy ID dự án
     * @return ID dự án đang được sử dụng
     */
    public String getProjectId() {
        return projectId;
    }
    
    /**
     * Lấy vị trí (location) của dịch vụ
     * @return Vị trí dịch vụ (region) đang được sử dụng
     */
    public String getLocation() {
        return location;
    }
    
    /**
     * Lấy tên của mô hình vector nhúng
     * @return Tên của mô hình vector nhúng hiện tại
     */
    public String getEmbeddingModelName() {
        return embeddingModelName;
    }
    
    /**
     * Lấy token truy cập hiện tại (chỉ dùng cho mục đích kiểm thử)
     * @return Token truy cập OAuth hiện tại
     */
    public String getCurrentAccessToken() {
        return accessToken;
    }

    /**
     * Chuyển đổi mảng byte thành chuỗi hex
     * Hữu ích cho việc gỡ lỗi khi làm việc với mã hóa UTF-8
     * 
     * @param bytes Mảng byte cần chuyển đổi
     * @return Chuỗi hex tương ứng với mảng byte
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Xóa bộ nhớ đệm vector nhúng để đảm bảo kích thước nhất quán
     * Hữu ích khi chuyển đổi giữa các mô hình có kích thước vector khác nhau
     */
    public void clearEmbeddingCache() {
        int size = embeddingCache.size();
        embeddingCache.clear();
        logger.info("Đã xóa bộ nhớ đệm vector nhúng (" + size + " phần tử)");
    }

    /**
     * Tạo nội dung văn bản từ prompt sử dụng Vertex AI nhưng có thời gian chờ tối đa
     * Phương thức này là bản mở rộng của generateText, thêm cơ chế timeout
     * để tránh chờ đợi quá lâu khi API không phản hồi
     * 
     * @param prompt Prompt để tạo văn bản
     * @param timeoutSeconds Số giây tối đa chờ phản hồi
     * @return Văn bản được tạo từ Vertex AI hoặc null nếu quá thời gian
     */
    public String generateTextWithTimeout(String prompt, int timeoutSeconds) {
        // Tạo CompletableFuture để gọi API bất đồng bộ
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            try {
                return generateText(prompt);
            } catch (Exception e) {
                logger.severe("Error generating text with Vertex AI: " + e.getMessage());
                e.printStackTrace();
                return null;
            }
        });
        
        try {
            // Chờ với timeout
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            logger.warning("Vertex AI request timed out after " + timeoutSeconds + " seconds");
            future.cancel(true); // Hủy task nếu đang chạy
            return null;
        } catch (InterruptedException | ExecutionException e) {
            logger.severe("Error while waiting for Vertex AI response: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

}