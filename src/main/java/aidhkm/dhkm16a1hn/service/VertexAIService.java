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

    public VertexAIService() {
        this.webClient = WebClient.builder()
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
                
        // Configure ObjectMapper for proper UTF-8 handling
        this.objectMapper = new ObjectMapper();
        // Ensure proper UTF-8 serialization
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
            logger.severe("Hãy đảm bảo tệp service-account.json hợp lệ và có quyền truy cập Vertex AI API");
            logger.severe("Vui lòng kiểm tra biến môi trường GOOGLE_APPLICATION_CREDENTIALS hoặc file service-account.json");
        }
    }
    
    /**
     * Khởi tạo GoogleCredentials thủ công nếu không được inject
     */
    private void initializeGoogleCredentials() throws IOException {
        logger.info("Đang khởi tạo GoogleCredentials thủ công...");
        
        try {
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
     * @param prompt Nội dung prompt
     * @return Văn bản được tạo
     */
    public String generateText(String prompt) {
        long startTime = System.currentTimeMillis();
        try {
            logger.info("Processing text generation request");
            
            // Kiểm tra cache
            String cacheKey = String.valueOf(prompt.hashCode());
            if (responseCache.containsKey(cacheKey)) {
                logger.info("Cache hit for prompt");
                logger.info("Cache retrieval time: " + (System.currentTimeMillis() - startTime) + "ms");
                return responseCache.get(cacheKey);
            }
            
            // Xây dựng yêu cầu
            String requestBody = buildVertexAIRequest(prompt);
            logger.info("Text generation request body (truncated): " + 
                requestBody.substring(0, Math.min(requestBody.length(), 500)) + 
                (requestBody.length() > 500 ? "..." : ""));
            
            // Gọi API với retry logic
            long apiCallStart = System.currentTimeMillis();
            String response = callVertexAPIWithRetry(requestBody);
            logger.info("API call time: " + (System.currentTimeMillis() - apiCallStart) + "ms");
            
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
                    logger.severe("Error parsing JSON response: " + e.getMessage());
                    logger.severe("JSON parsing stack trace: " + sw.toString());
                    logger.severe("Raw response (truncated): " + 
                        (response != null ? response.substring(0, Math.min(response.length(), 1000)) + "..." : "null"));
                }
            }
            logger.info("Response parsing time: " + (System.currentTimeMillis() - parseStart) + "ms");
            
            // Lưu vào cache
            if (generatedText != null) {
                responseCache.put(cacheKey, generatedText);
            }
            
            logger.info("Total text generation time: " + (System.currentTimeMillis() - startTime) + "ms");
            return generatedText != null ? generatedText : "Xin lỗi, tôi không thể tạo phản hồi lúc này.";
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            logger.severe("Error generating text: " + e.getMessage());
            logger.severe("Generation error stack trace: " + sw.toString());
            return "Xin lỗi, tôi không thể tạo phản hồi lúc này.";
        }
    }

    /**
     * Trích xuất văn bản từ phản hồi JSON
     */
    private String extractTextFromResponse(JsonNode jsonResponse) {
        try {
            logger.info("Parsing response: " + jsonResponse.toString().substring(0, Math.min(200, jsonResponse.toString().length())) + "...");
            
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
                    
                    logger.info("Response received from Gemini 1.5 Pro");
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
                    
                    logger.info("Response received from Gemini 1.5 Pro (old format)");
                    return generatedText;
                }
            }
            
            logger.warning("Invalid response format from Gemini 1.5 Pro API: " + jsonResponse.toString().substring(0, Math.min(500, jsonResponse.toString().length())));
            return null;
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            logger.severe("Error extracting text from response: " + e.getMessage());
            logger.severe("Full stack trace: " + sw.toString());
            return null;
        }
    }

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
            logger.info("Generated JSON Request: " + json);
            return json;
        } catch (JsonProcessingException e) {
            logger.severe("Error serializing request: " + e.getMessage());
            return "{}";
        }
    }

    /**
     * Gọi API Vertex AI với cơ chế retry - phương thức đơn giản hóa
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
            
            logger.info("Using Gemini API endpoint with :generateContent suffix");
        } else {
            // Các model khác dùng định dạng URL thông thường với ":predict"
            endpoint = String.format(
                "https://%s-aiplatform.googleapis.com/v1/projects/%s/locations/%s/publishers/google/models/%s:predict",
                location, actualProjectId, location, modelNameForUrl);
        }
        
        logger.info("Calling Vertex AI API at endpoint: " + endpoint);
        logger.info("Using project ID: " + actualProjectId + " for Vertex AI API call");
        
        // Gọi phương thức đầy đủ với giá trị mặc định
        return callVertexAPIWithRetry(endpoint, requestData, 0, MAX_RETRIES);
    }

    /**
     * Lấy project ID thực tế từ GoogleCredentials
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
                logger.info("Vertex AI API call successful, took " + (endTime - startTime) + "ms");
                
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
                    responseBody = "No error details available (error stream was null)";
                    logger.warning("Error stream was null for HTTP response code: " + responseCode);
                }
                
                logger.warning("Vertex AI API error: " + responseCode + " - " + responseBody);
                
                // Xử lý lỗi xác thực (401)
                if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    logger.warning("Authentication error (401): " + responseBody);
                    if (retryCount < maxRetries) {
                        logger.info("Refreshing token and retrying API call (" + (retryCount + 1) + "/" + maxRetries + ")");
                        refreshAccessToken(); // Force token refresh
                        return callVertexAPIWithRetry(endpoint, requestData, retryCount + 1, maxRetries);
                    } else {
                        logger.severe("Maximum retry attempts reached for authentication error");
                        throw new RuntimeException("Authentication failed after " + maxRetries + " attempts");
                    }
                }
                
                // Xử lý lỗi quota hoặc rate limit (429 or 503)
                if (responseCode == 429 || responseCode == 503) {
                    if (retryCount < maxRetries) {
                        int sleepTime = Math.min(1000 * (int) Math.pow(2, retryCount), 32000);
                        logger.info("Rate limited or quota exceeded, retrying after " + sleepTime + "ms (" + (retryCount + 1) + "/" + maxRetries + ")");
                        Thread.sleep(sleepTime);
                        return callVertexAPIWithRetry(endpoint, requestData, retryCount + 1, maxRetries);
                    } else {
                        logger.severe("Maximum retry attempts reached for rate limit/quota");
                    }
                }
                
                // Các lỗi khác
                throw new RuntimeException("Vertex AI API error: " + responseCode + " - " + responseBody);
            }
        } catch (Exception e) {
            if (e instanceof RuntimeException && e.getMessage() != null && e.getMessage().startsWith("Vertex AI API error:")) {
                throw (RuntimeException) e;
            }
            
            logger.severe("Error calling Vertex AI API: " + e.getMessage());
            if (retryCount < maxRetries) {
                try {
                    int sleepTime = Math.min(1000 * (int) Math.pow(2, retryCount), 32000);
                    logger.info("Retrying API call after error (" + (retryCount + 1) + "/" + maxRetries + "), waiting " + sleepTime + "ms");
                    Thread.sleep(sleepTime);
                    return callVertexAPIWithRetry(endpoint, requestData, retryCount + 1, maxRetries);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("API call interrupted during retry backoff", ie);
                }
            } else {
                logger.severe("Maximum retry attempts reached for API call");
                // Log thêm stack trace đầy đủ
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                logger.severe("Full error stack trace: " + sw.toString());
                throw new RuntimeException("Failed to call Vertex AI API after " + maxRetries + " attempts", e);
            }
        }
    }

    /**
     * Builds the request for embedding generation based on the text provided.
     * This method constructs the appropriate JSON structure for the text-embedding model.
     * 
     * @param text The text to create an embedding for
     * @return A properly formatted request as ObjectNode
     */
    private ObjectNode buildEmbeddingRequest(String text) {
        logger.info("Building embedding request for text: " + text);
        
        // Normalize the text early to ensure consistent UTF-8 handling
        String normalizedText = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC);
        normalizedText = normalizedText.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "").trim();
        
        logger.info("Text after normalization: " + normalizedText);
        
        // Ensure we have valid text, use a placeholder if empty
        if (normalizedText == null || normalizedText.isEmpty()) {
            normalizedText = "placeholder_text";
            logger.warning("Text is empty after normalization, using placeholder: " + normalizedText);
        }
        
        // Ensure proper UTF-8 encoding for the text
        String encodedText;
        try {
            // Re-encode the text to ensure proper UTF-8 handling
            byte[] bytes = normalizedText.getBytes(StandardCharsets.UTF_8);
            logger.info("Raw UTF-8 bytes: " + bytesToHex(bytes));
            
            encodedText = new String(bytes, StandardCharsets.UTF_8);
            logger.info("Text after UTF-8 encoding: " + encodedText);
            
            // Double-check encoding result
            if (encodedText.isEmpty() || !encodedText.equals(normalizedText)) {
                logger.warning("UTF-8 encoding changed the text! Original: '" + normalizedText + 
                             "', Encoded: '" + encodedText + "'");
                
                if (encodedText.isEmpty()) {
                    encodedText = "placeholder_text_after_encoding";
                    logger.warning("Using placeholder after empty encoding result: " + encodedText);
                }
            }
        } catch (Exception e) {
            logger.warning("Error encoding text to UTF-8, using original text: " + e.getMessage());
            encodedText = normalizedText;
        }
        
        // Create the request structure using Jackson's ObjectNode
        ObjectNode rootNode = objectMapper.createObjectNode();
        ArrayNode instancesArray = objectMapper.createArrayNode();
        
        if (embeddingModelName.contains("text-embedding-005")) {
            // For text-embedding-005 model - using the correct format { "content": "..." }
            ObjectNode instance = objectMapper.createObjectNode();
            instance.put("content", encodedText);  // Direct content format
            instancesArray.add(instance);
            
            // Set parameters
            ObjectNode parameters = objectMapper.createObjectNode();
            parameters.put("dimension", 768);
            
            rootNode.set("instances", instancesArray);
            rootNode.set("parameters", parameters);
            
            logger.info("Using correct text-embedding-005 format: { \"content\": \"...\" }");
        } else {
            // For older models like textembedding-gecko
            ObjectNode instance = objectMapper.createObjectNode();
            instance.put("content", encodedText);
            instancesArray.add(instance);
            rootNode.set("instances", instancesArray);
        }
        
        // Log the request body for debugging
        try {
            // Configure ObjectMapper for UTF-8
            String jsonBody = objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(rootNode);
            logger.info("Request body prepared:\n" + jsonBody);
            
            // Validate the JSON structure (for logging purposes only)
            if (jsonBody.contains("\"content\":\"" + encodedText + "\"")) {
                logger.info("✓ FORMAT CHECK PASSED: JSON contains expected Vietnamese text correctly encoded");
            } else {
                logger.warning("✗ FORMAT CHECK FAILED: JSON does not contain expected text: " + encodedText);
                logger.warning("JSON content: " + jsonBody);
            }
        } catch (Exception e) {
            logger.severe("Error serializing request body to JSON: " + e.getMessage());
        }
        
        return rootNode;
    }

    /**
     * Tạo embedding vector từ văn bản sử dụng Vertex AI
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
            logger.info("Text after normalization and UTF-8 encoding: " + finalText);
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
            logger.info("Preparing request with model: " + embeddingModelName);
            ObjectNode requestNode = buildEmbeddingRequest(finalText);
            
            // Luôn sử dụng project ID từ cấu hình
            String actualProjectId = projectId;
            
            // Xác định URL endpoint cho text-embedding-005
            String apiUrl = String.format(
                "https://%s-aiplatform.googleapis.com/v1/projects/%s/locations/%s/publishers/google/models/%s:predict",
                location, actualProjectId, location, embeddingModelName);
            
            logger.info("Calling embedding API with URL: " + apiUrl);
            
            // Kiểm tra URL API
            logger.info("API URL CHECK: " + 
                        (apiUrl.contains("publishers/google/models/" + embeddingModelName + ":predict") ? 
                         "✓ OK - URL contains correct model path" : 
                         "✗ ERROR - URL does not contain correct model path"));
            
            // Lấy token OAuth2 hiện tại
            String token = getAccessToken();
            if (token == null || token.isEmpty()) {
                logger.severe("Không thể lấy token OAuth2");
                throw new RuntimeException("Không có token OAuth2 hợp lệ");
            }
            logger.info("OAuth2 token obtained");
            
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
                logger.info("Final JSON request with pretty printing:\n" + jsonRequest);
            } catch (Exception e) {
                logger.severe("Lỗi khi chuyển đổi request thành JSON: " + e.getMessage());
                throw new RuntimeException("Không thể chuyển đổi request", e);
            }
            
            // Ghi lại chi tiết request để gỡ lỗi
            byte[] requestBytes = jsonRequest.getBytes(StandardCharsets.UTF_8);
            logger.info("Sending request bytes (length=" + requestBytes.length + ")");
            
            // Ghi lại toàn bộ chi tiết request để gỡ lỗi
            logger.info("COMPLETE REQUEST DETAILS:");
            logger.info("URL: " + apiUrl);
            logger.info("Method: POST");
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
            logger.info("API response status: " + statusCode);
            
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
                logger.info("API Success Response: " + responseBody.substring(0, Math.min(100, responseBody.length())) + "...");
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
                logger.severe("API Error Status: " + statusCode);
                logger.severe("API Error Response: " + responseBody);
                
                // Phân tích lỗi chi tiết và log thông tin request gây lỗi
                try {
                    JsonNode errorNode = objectMapper.readTree(responseBody);
                    if (errorNode.has("error") && errorNode.get("error").has("message")) {
                        String errorMessage = errorNode.get("error").get("message").asText();
                        logger.severe("API Error Message: " + errorMessage);
                        
                        // Log the request that caused the error for debugging
                        logger.severe("Request that caused error: " + jsonRequest);
                        
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
            logger.info("Embedding API call took: " + (endTime - startTime) + "ms");

            // Xử lý response JSON
            if (responseBody != null && !responseBody.isEmpty()) {
                logger.info("Response received: " + responseBody.substring(0, Math.min(100, responseBody.length())) + "...");
                
                ObjectMapper mapper = new ObjectMapper();
                JsonNode rootNode = mapper.readTree(responseBody);
                
                if (rootNode.has("predictions") && rootNode.get("predictions").size() > 0) {
                    JsonNode prediction = rootNode.get("predictions").get(0);
                    
                    // Format réponse pour text-embedding-005
                    if (prediction.has("embeddings") && prediction.get("embeddings").has("values")) {
                        // Format pour text-embedding-005
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

                        logger.info("Successfully created embedding with " + embedding.length + " dimensions using embeddings.values format");
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
                        
                        logger.info("Successfully created embedding with " + embedding.length + " dimensions using values format");
                        return embedding;
                    }
                    // In ra cấu trúc JSON response để debug
                    logger.warning("Unexpected JSON response structure: " + prediction.toString());
                } else {
                    logger.warning("Missing predictions in response - checking full response: " + responseBody);
                }
                
                logger.warning("Invalid response format from embedding API: " + responseBody);
            } else {
                logger.warning("Received null or empty response from embedding API");
            }

            // Nếu không thành công, sử dụng embedding đơn giản
            logger.warning("Failed to create embedding via API, using fallback method");
            return createFallbackEmbedding(finalText);
            
        } catch (Exception e) {
            logger.severe("Error creating embedding: " + e.getMessage());
            if (e.getCause() != null) {
                logger.severe("Root cause: " + e.getCause().getMessage());
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
     * Get project ID
     */
    public String getProjectId() {
        return projectId;
    }
    
    /**
     * Get location
     */
    public String getLocation() {
        return location;
    }
    
    /**
     * Get embedding model name
     * @return The current embedding model name
     */
    public String getEmbeddingModelName() {
        return embeddingModelName;
    }
    
    /**
     * Get access token (for test purposes only)
     */
    public String getCurrentAccessToken() {
        return accessToken;
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Clear the embedding cache to ensure consistent dimensions
     */
    public void clearEmbeddingCache() {
        int size = embeddingCache.size();
        embeddingCache.clear();
        logger.info("Cleared embedding cache (" + size + " entries)");
    }

}