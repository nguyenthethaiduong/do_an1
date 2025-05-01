package aidhkm.dhkm16a1hn.util;

import org.postgresql.util.PGobject;
import java.sql.SQLException;
import java.util.logging.Logger;

public class VectorUtil {
    
    private static final Logger LOGGER = Logger.getLogger(VectorUtil.class.getName());
    
    /**
     * Chuyển đổi float[] thành PGobject để lưu trữ trong PostgreSQL
     */
    public static PGobject toPGVector(float[] vector) throws SQLException {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            sb.append(vector[i]);
            if (i < vector.length - 1) sb.append(",");
        }
        sb.append("]");
        
        PGobject pgObject = new PGobject();
        pgObject.setType("vector");
        pgObject.setValue(sb.toString());
        
        return pgObject;
    }
    
    /**
     * Tính cosine similarity giữa hai vector
     * @return giá trị từ -1 đến 1, với 1 là hoàn toàn giống nhau
     */
    public static float cosineSimilarity(float[] vectorA, float[] vectorB) {
        if (vectorA.length != vectorB.length) {
            int minDimension = Math.min(vectorA.length, vectorB.length);
            // Xử lý trường hợp kích thước khác nhau bằng cách sử dụng kích thước nhỏ hơn
            if (vectorA.length != vectorB.length) {
                // Ghi nhật ký về sự không khớp kích thước
                LOGGER.warning("Kích thước vector không khớp: " + vectorA.length + " và " + vectorB.length + 
                              ", sử dụng " + minDimension + " phần tử đầu tiên");
            }
            
            // Tính toán độ tương đồng chỉ sử dụng các chiều chung
            float dotProduct = 0.0f;
            float normA = 0.0f;
            float normB = 0.0f;
            
            for (int i = 0; i < minDimension; i++) {
                dotProduct += vectorA[i] * vectorB[i];
                normA += vectorA[i] * vectorA[i];
                normB += vectorB[i] * vectorB[i];
            }
            
            if (normA == 0 || normB == 0) {
                return 0;
            }
            
            return dotProduct / (float) (Math.sqrt(normA) * Math.sqrt(normB));
        }
        
        float dotProduct = 0.0f;
        float normA = 0.0f;
        float normB = 0.0f;
        
        for (int i = 0; i < vectorA.length; i++) {
            dotProduct += vectorA[i] * vectorB[i];
            normA += vectorA[i] * vectorA[i];
            normB += vectorB[i] * vectorB[i];
        }
        
        if (normA == 0 || normB == 0) {
            return 0;
        }
        
        return dotProduct / (float) (Math.sqrt(normA) * Math.sqrt(normB));
    }
    
    /**
     * Chuẩn hóa vector (đưa độ dài về 1)
     */
    public static float[] normalize(float[] vector) {
        float[] normalized = new float[vector.length];
        
        // Tính độ dài vector
        float length = 0.0f;
        for (float v : vector) {
            length += v * v;
        }
        length = (float) Math.sqrt(length);
        
        // Chia mỗi phần tử cho độ dài
        if (length > 0) {
            for (int i = 0; i < vector.length; i++) {
                normalized[i] = vector[i] / length;
            }
        }
        
        return normalized;
    }
} 