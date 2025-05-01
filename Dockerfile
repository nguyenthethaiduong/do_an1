# 1. Dùng image Java 17 có sẵn Maven Wrapper (không cần cài Maven hệ thống)
FROM eclipse-temurin:17-jdk-alpine

# 2. Set thư mục làm việc
WORKDIR /app

# 3. Copy pom.xml trước (để cache dependencies)
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw mvnw

# 4. Tải dependencies để cache
RUN chmod +x mvnw && ./mvnw dependency:go-offline

# 5. Copy toàn bộ source vào container
COPY . .

# 6. Build project bằng Maven (bắt buộc phải có dòng này)
RUN ./mvnw clean package -DskipTests

# 7. Chạy file jar (thay tên jar bằng đúng tên tạo ra trong target/)
CMD ["java", "-jar", "target/dhkm16a1hn-0.0.1-SNAPSHOT.jar"]
