# Use OpenJDK 17 (supports both AMD64 and ARM64 architectures)
FROM eclipse-temurin:17-jdk-jammy

# Install Maven
RUN apt-get update && \
    apt-get install -y maven && \
    rm -rf /var/lib/apt/lists/*

# Set working directory
WORKDIR /app

# Copy pom.xml
COPY pom.xml ./

# Download dependencies (layer caching)
RUN mvn dependency:go-offline -B || true

# Copy source code
COPY src ./src

# Build the application
RUN mvn clean package -DskipTests

# Expose port
EXPOSE 8080

# Run the application
ENTRYPOINT ["java", "-jar", "target/signature-docx-poc-1.0.0-SNAPSHOT.jar"]
