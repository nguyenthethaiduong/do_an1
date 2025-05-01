package aidhkm.dhkm16a1hn.controller;

import aidhkm.dhkm16a1hn.service.VertexAIService;
import aidhkm.dhkm16a1hn.service.VectorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Arrays;
import java.util.logging.Logger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;

/**
 * Test Controller - Chỉ sử dụng trong môi trường phát triển
 */
@RestController
@RequestMapping("/api/test")
public class TestController {
    private static final Logger logger = Logger.getLogger(TestController.class.getName());

    @Autowired
    private VertexAIService vertexAIService;
    
    @Autowired
    private VectorService vectorService;

    /**
     * Test tạo embedding từ text
     */
    @GetMapping("/embedding")
    public ResponseEntity<?> testEmbedding(@RequestParam String text) {
        try {
            logger.info("Testing embedding API with text: " + text);
            
            Map<String, Object> response = new HashMap<>();
            
            // Test embedding từ VertexAIService trước
            long startTime1 = System.currentTimeMillis();
            float[] vertexEmbedding = vertexAIService.createEmbedding(text);
            long endTime1 = System.currentTimeMillis();
            
            // Test qua VectorService sau
            long startTime2 = System.currentTimeMillis();
            float[] vectorEmbedding = vectorService.createEmbedding(text);
            long endTime2 = System.currentTimeMillis();
            
            response.put("text", text);
            response.put("textLength", text.length());
            response.put("vertexAIEmbeddingLength", vertexEmbedding.length);
            response.put("vertexAIEmbeddingTime", (endTime1 - startTime1) + "ms");
            response.put("vertexAIEmbeddingSample", Arrays.copyOfRange(vertexEmbedding, 0, Math.min(5, vertexEmbedding.length)));
            
            response.put("vectorServiceEmbeddingLength", vectorEmbedding.length);
            response.put("vectorServiceEmbeddingTime", (endTime2 - startTime2) + "ms");
            response.put("vectorServiceEmbeddingSample", Arrays.copyOfRange(vectorEmbedding, 0, Math.min(5, vectorEmbedding.length)));
            
            logger.info("Embedding test completed successfully");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.severe("Error testing embedding: " + e.getMessage());
            if (e.getCause() != null) {
                logger.severe("Root cause: " + e.getCause().getMessage());
            }
            
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            error.put("text", text);
            return ResponseEntity.status(500).body(error);
        }
    }

    /**
     * Test tạo embedding với văn bản tiếng Anh (ASCII)
     */
    @GetMapping("/ascii-embedding")
    public ResponseEntity<?> testAsciiEmbedding() {
        try {
            // Sử dụng text tiếng Anh đơn giản chỉ có ký tự ASCII
            String text = "This is a simple test in English";
            logger.info("Testing embedding API with ASCII text: " + text);
            
            Map<String, Object> response = new HashMap<>();
            
            // Test embedding từ VertexAIService
            long startTime = System.currentTimeMillis();
            float[] embedding = vertexAIService.createEmbedding(text);
            long endTime = System.currentTimeMillis();
            
            response.put("text", text);
            response.put("textLength", text.length());
            response.put("embeddingLength", embedding.length);
            response.put("processingTime", (endTime - startTime) + "ms");
            response.put("embeddingSample", Arrays.copyOfRange(embedding, 0, Math.min(5, embedding.length)));
            
            logger.info("ASCII embedding test completed successfully");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.severe("Error testing ASCII embedding: " + e.getMessage());
            if (e.getCause() != null) {
                logger.severe("Root cause: " + e.getCause().getMessage());
            }
            
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }

    /**
     * Test tạo embedding với direct JSON API call
     */
    @GetMapping("/direct-embedding")
    public ResponseEntity<?> testDirectEmbedding(@RequestParam String text) {
        try {
            logger.info("Testing direct JSON embedding with text: " + text);
            
            // Xây dựng JSON trực tiếp để tránh vấn đề chuyển đổi Map
            String directJsonTemplate = 
                "{\"instances\":[{\"content\":{\"text\":\"%s\"}}],\"parameters\":{\"dimension\":768}}";
            
            // Thay thế chuỗi text vào JSON template với escape
            String escapedText = text.replace("\\", "\\\\").replace("\"", "\\\"");
            String directJson = String.format(directJsonTemplate, escapedText);
            
            logger.info("Direct JSON request: " + directJson);
            
            Map<String, Object> response = new HashMap<>();
            response.put("text", text);
            response.put("jsonRequest", directJson);
            
            // Gọi trực tiếp API với JSON đã xây dựng
            String resultJson = callEmbeddingApiDirectly(directJson);
            response.put("apiResponse", resultJson);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.severe("Error in direct embedding test: " + e.getMessage());
            if (e.getCause() != null) {
                logger.severe("Root cause: " + e.getCause().getMessage());
            }
            
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            error.put("text", text);
            return ResponseEntity.status(500).body(error);
        }
    }
    
    /**
     * Gọi API embedding trực tiếp với JSON request
     */
    private String callEmbeddingApiDirectly(String jsonRequestBody) throws Exception {
        // Lấy các tham số từ VertexAIService
        String projectId = vertexAIService.getProjectId();
        String location = vertexAIService.getLocation();
        String embeddingModel = vertexAIService.getEmbeddingModelName();
        String accessToken = vertexAIService.getCurrentAccessToken();
        
        // Xây dựng URL endpoint
        String apiUrl = String.format(
            "https://%s-aiplatform.googleapis.com/v1/projects/%s/locations/%s/publishers/google/models/%s:predict",
            location, projectId, location, embeddingModel);
        
        logger.info("Direct API call to: " + apiUrl);
        
        // Setup HTTP connection
        URL url = new URL(apiUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + accessToken);
        connection.setDoOutput(true);
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(20000);
        
        // Ghi JSON request
        try (OutputStream os = connection.getOutputStream()) {
            byte[] input = jsonRequestBody.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
            os.flush();
        }
        
        // Đọc response
        int statusCode = connection.getResponseCode();
        logger.info("Direct API call status: " + statusCode);
        
        StringBuilder response = new StringBuilder();
        try (BufferedReader br = statusCode >= 400 
                ? new BufferedReader(new InputStreamReader(
                    connection.getErrorStream() != null ? connection.getErrorStream() : new ByteArrayInputStream(new byte[0]), 
                    StandardCharsets.UTF_8))
                : new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                response.append(line);
            }
        }
        
        String responseBody = response.toString();
        logger.info("Direct API response: " + responseBody);
        
        if (statusCode >= 400) {
            throw new RuntimeException("API error: " + statusCode + " - " + responseBody);
        }
        
        return responseBody;
    }

    /**
     * Test tạo văn bản với Gemini API trực tiếp
     */
    @GetMapping("/direct-gemini")
    public ResponseEntity<?> testDirectGemini(@RequestParam String prompt) {
        try {
            logger.info("Testing direct Gemini API with prompt: " + prompt);
            
            // Lấy các tham số từ VertexAIService
            String projectId = vertexAIService.getProjectId();
            String location = vertexAIService.getLocation();
            String accessToken = vertexAIService.getCurrentAccessToken();
            
            // Xây dựng URL endpoint cho Gemini 1.5 Pro, sử dụng định dạng chính xác với publishers/google/models
            String apiUrl = String.format(
                "https://%s-aiplatform.googleapis.com/v1/projects/%s/locations/%s/publishers/google/models/gemini-1.5-pro:generateContent",
                location, projectId, location);
            
            logger.info("Direct Gemini API call to: " + apiUrl);
            
            // Xây dựng JSON request
            String requestBody = String.format(
                "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"%s\"}]}],\"generationConfig\":{\"temperature\":0.1,\"maxOutputTokens\":500,\"topP\":0.95,\"topK\":40}}",
                prompt.replace("\\", "\\\\").replace("\"", "\\\""));
            
            // Setup HTTP connection
            URL url = new URL(apiUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + accessToken);
            connection.setDoOutput(true);
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(20000);
            
            // Ghi JSON request
            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
                os.flush();
            }
            
            // Đọc response
            int statusCode = connection.getResponseCode();
            logger.info("Direct Gemini API call status: " + statusCode);
            
            StringBuilder response = new StringBuilder();
            try (BufferedReader br = statusCode >= 400 
                    ? new BufferedReader(new InputStreamReader(
                        connection.getErrorStream() != null ? connection.getErrorStream() : new ByteArrayInputStream(new byte[0]), 
                        StandardCharsets.UTF_8))
                    : new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    response.append(line);
                }
            }
            
            String responseBody = response.toString();
            
            Map<String, Object> responseMap = new HashMap<>();
            responseMap.put("prompt", prompt);
            responseMap.put("requestUrl", apiUrl);
            responseMap.put("requestBody", requestBody);
            responseMap.put("statusCode", statusCode);
            responseMap.put("responseBody", responseBody);
            
            return ResponseEntity.ok(responseMap);
            
        } catch (Exception e) {
            logger.severe("Error in direct Gemini test: " + e.getMessage());
            if (e.getCause() != null) {
                logger.severe("Root cause: " + e.getCause().getMessage());
            }
            
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            error.put("prompt", prompt);
            return ResponseEntity.status(500).body(error);
        }
    }
} 