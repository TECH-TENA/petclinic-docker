FROM eclipse-temurin:17-jdk-jammy
 
WORKDIR /app

COPY .mvn/ .mvn
COPY mvnw pom.xml ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -B dependency:resolve

COPY src ./src

CMD ["./mvnw", "spring-boot:run"]
