package aidhkm.dhkm16a1hn.service;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Service
public class OllamaService {
    
    private final String diemCuoiOllama = "http://localhost:11434/api/generate";
    private final RestTemplate restTemplate = new RestTemplate();
    
    public String generateResponse(String prompt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        
        Map<String, Object> request = new HashMap<>();
        request.put("model", "mistral"); // hoặc llama2 hoặc mô hình bạn đã cài đặt
        request.put("prompt", prompt);
        
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);
        
        try {
            Map<String, Object> response = restTemplate.postForObject(diemCuoiOllama, entity, Map.class);
            return (String) response.get("response");
        } catch (Exception e) {
            e.printStackTrace();
            return "Không thể kết nối với mô hình LLM cục bộ: " + e.getMessage();
        }
    }
} 