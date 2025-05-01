# Bước 1: Dùng image base Java 17
FROM eclipse-temurin:17-jdk-alpine

# Bước 2: Thiết lập thư mục làm việc trong container
WORKDIR /app

# Bước 3: Copy toàn bộ mã nguồn vào container
COPY . .

# Bước 4: Cấp quyền thực thi cho Maven Wrapper (nếu chưa có)
RUN chmod +x mvnw

# Bước 5: Build project bằng Maven Wrapper, bỏ qua test để giảm thời gian
RUN ./mvnw clean package -DskipTests

# Bước 6: Mở cổng mặc định của Spring Boot
EXPOSE 8080

# Bước 7: Chạy file JAR được build trong thư mục target/
CMD ["java", "-jar", "target/*.jar"]
