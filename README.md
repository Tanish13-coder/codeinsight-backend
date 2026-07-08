# 🔧 CodeInsight Backend - Spring Boot Microservice

High-performance REST API backend for CodeInsight, built with **Spring Boot**, **MySQL**, and **Redis**. This microservice powers the AI-driven coding practice platform.

---

## 📋 Quick Links

- [Main Project](https://github.com/Tanish13-coder/codeinsight)
- [Frontend Repository](https://github.com/Tanish13-coder/codeinsight-frontend)
- [AI Service Setup](./ai-service/README.md)

---

## 🎯 Overview

The CodeInsight backend is a scalable microservice that handles:
- **RESTful APIs** for code analysis and insights
- **Database Operations** with MySQL and JPA/Hibernate
- **Caching Layer** with Redis for performance optimization
- **AI Integration** with local Ollama LLM
- **Security** with Spring Security (authentication/authorization ready)
- **Concurrent Request Handling** for production-grade reliability

---

## 🛠️ Tech Stack

| Component | Technology | Version |
|-----------|-----------|---------|
| **Framework** | Spring Boot | 2.x |
| **Language** | Java | 17+ |
| **Build Tool** | Maven | 3.8+ |
| **Database** | MySQL | 8.0+ |
| **ORM** | Hibernate/JPA | Latest |
| **Cache** | Redis | 6.0+ |
| **Container** | Docker | Optional |

---

## 📦 Dependencies

Key Maven dependencies in `pom.xml`:

- **spring-boot-starter-web** - REST API framework
- **spring-boot-starter-data-jpa** - Database ORM
- **mysql-connector-java** - MySQL driver
- **spring-data-redis** - Redis integration
- **json** - JSON processing

---

## 🚀 Getting Started

### Prerequisites
```bash
# Java 17+
java -version

# Maven 3.8+
mvn -version

# MySQL 8.0+
mysql --version

# Redis (optional, for caching)
redis-cli --version
```

### 1. Clone Repository
```bash
git clone https://github.com/Tanish13-coder/codeinsight-backend.git
cd codeinsight-backend
```

### 2. Database Setup

```sql
-- Create database
CREATE DATABASE codeinsight;
USE codeinsight;

-- Tables will be auto-created by Spring Boot JPA (set spring.jpa.hibernate.ddl-auto=update)
```

### 3. Configuration

Create or edit `src/main/resources/application.properties`:

```properties
# Server Configuration
server.port=8080
server.servlet.context-path=/api

# MySQL Database
spring.datasource.url=jdbc:mysql://localhost:3306/codeinsight
spring.datasource.username=root
spring.datasource.password=your_password
spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver

# JPA/Hibernate Configuration
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=false
spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQL8Dialect
spring.jpa.properties.hibernate.format_sql=true

# Redis Configuration (optional)
spring.redis.host=localhost
spring.redis.port=6379

# AI Service Configuration
ai.insight.url=http://localhost:8001/insight
ai.insight.timeout=30000

# Logging
logging.level.root=INFO
logging.level.com.codeinsight=DEBUG
```

### 4. Build Project

```bash
# Clean and build
mvn clean install

# Skip tests (optional)
mvn clean install -DskipTests
```

### 5. Run Application

```bash
# Using Maven
mvn spring-boot:run

# Or run JAR directly (after building)
mvn package
java -jar target/codeinsight-1.0.jar
```

✅ Backend API running on: `http://localhost:8080/api`

---

## 📁 Project Structure

```
codeinsight-backend/
│
├── src/main/java/com/codeinsight/
│   ├── Main.java                 # Application entry point
│   ├── controller/               # REST Controllers
│   │   ├── CodeController.java
│   │   └── InsightController.java
│   ├── service/                  # Business Logic
│   │   ├── CodeAnalysisService.java
│   │   └── AIInsightService.java
│   ├── repository/               # Data Access Layer
│   │   ├── CodeRepository.java
│   │   └── UserRepository.java
│   ├── model/                    # Entity Classes
│   │   ├── Code.java
│   │   ├── CodeInsight.java
│   │   └── User.java
│   ├── config/                   # Configuration Classes
│   │   ├── DatabaseConfig.java
│   │   └── CacheConfig.java
│   └── util/                     # Utility Classes
│       └── APIUtils.java
│
├── src/main/resources/
│   ├── application.properties    # Main configuration
│   └── application-dev.properties
│
├── ai-service/                   # Python AI Service
│   ├── app.py                    # Flask application
│   ├── requirements.txt          # Python dependencies
│   └── README.md                 # AI service setup
│
├── pom.xml                       # Maven configuration
├── Dockerfile                    # Docker containerization
└── README.md                     # This file
```

---

## 🔌 API Endpoints

### Code Analysis

#### Analyze Code
```http
POST /api/code/analyze
Content-Type: application/json

{
  "code": "public class Hello { public static void main(String[] args) { System.out.println(\"Hello\"); } }",
  "language": "java"
}

Response:
{
  "status": "success",
  "analysis": {
    "syntax_errors": [],
    "suggestions": [...],
    "complexity": "low"
  }
}
```

### AI Insights

#### Generate Code Insight
```http
POST /api/insight/generate
Content-Type: application/json

{
  "code": "...",
  "context": "Explain this Java code"
}

Response:
{
  "status": "success",
  "insight": "...",
  "explanation": "..."
}
```

### Health Check

#### Service Health
```http
GET /api/health

Response:
{
  "status": "UP",
  "database": "CONNECTED",
  "redis": "CONNECTED"
}
```

---

## 🔐 Authentication (Ready to Implement)

The application is ready to integrate Spring Security:

```java
// Example security configuration (implement as needed)
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeRequests()
                .antMatchers("/api/public/**").permitAll()
                .antMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            .and()
            .httpBasic();
        return http.build();
    }
}
```

---

## ⚡ Performance Optimization

### Redis Caching Example

```java
@Service
public class CodeAnalysisService {
    
    @Cacheable(value = "codeAnalysis", key = "#code")
    public CodeAnalysisResult analyzeCode(String code) {
        // Analysis logic - result cached automatically
        return performAnalysis(code);
    }
    
    @CacheEvict(value = "codeAnalysis", allEntries = true)
    public void clearCache() {
        // Clear cache when needed
    }
}
```

### Database Query Optimization

- Use JPA `@Query` annotations for optimized queries
- Implement proper indexing on frequently searched columns
- Use pagination for large result sets
- Consider database connection pooling

---

## 🐳 Docker Setup

### Build Docker Image
```bash
docker build -t codeinsight-backend:latest .
```

### Run Container
```bash
docker run -p 8080:8080 \
  -e SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/codeinsight \
  -e SPRING_DATASOURCE_USERNAME=root \
  -e SPRING_DATASOURCE_PASSWORD=password \
  codeinsight-backend:latest
```

### Docker Compose (with MySQL & Redis)
```yaml
version: '3.8'
services:
  backend:
    build: .
    ports:
      - "8080:8080"
    depends_on:
      - mysql
      - redis
    environment:
      SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/codeinsight
      SPRING_DATASOURCE_USERNAME: root
      SPRING_DATASOURCE_PASSWORD: password
      SPRING_REDIS_HOST: redis
  
  mysql:
    image: mysql:8.0
    environment:
      MYSQL_DATABASE: codeinsight
      MYSQL_ROOT_PASSWORD: password
    ports:
      - "3306:3306"
  
  redis:
    image: redis:latest
    ports:
      - "6379:6379"
```

---

## 🧪 Testing

```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=CodeAnalysisServiceTest

# Run with coverage
mvn test jacoco:report
```

---

## 🔧 Troubleshooting

### Connection Issues

**MySQL Connection Failed**
```bash
# Check MySQL is running
mysql -u root -p

# Update connection string
# Ensure database exists: CREATE DATABASE codeinsight;
```

**Redis Connection Failed**
```bash
# Check Redis is running
redis-cli ping

# Ensure Redis service started
redis-server
```

### Build Issues

**Maven Clean Build**
```bash
mvn clean install -U
```

**Clear Maven Cache**
```bash
rm -rf ~/.m2/repository
mvn clean install
```

---

## 📊 Performance Metrics

Monitor application performance:

- **Response Time:** Track API response times
- **Database Query Time:** Monitor query execution
- **Cache Hit Ratio:** Monitor Redis cache effectiveness
- **Concurrent Connections:** Monitor active connections
- **Memory Usage:** Monitor JVM memory consumption

---

## 🚀 Deployment Guide

### Production Checklist

- [ ] Database backups configured
- [ ] Redis persistence enabled
- [ ] Logging properly configured
- [ ] API rate limiting implemented
- [ ] Security headers configured
- [ ] CORS properly configured
- [ ] SSL/TLS certificates installed
- [ ] Environment variables secured
- [ ] Load balancer configured
- [ ] Monitoring and alerting setup

---

## 📚 Key Features Implemented

### ✅ Core Features
- RESTful API design with consistent response formats
- MySQL database with JPA/Hibernate ORM
- Redis caching for performance
- Comprehensive error handling
- CORS configuration for frontend integration
- Environment-based configuration

### 🚀 Advanced Features
- Microservices-ready architecture
- AI service integration
- Concurrent request handling
- Database connection pooling
- Request/response logging
- Health check endpoints

---

## 🔐 Security Considerations

- Use environment variables for sensitive data (passwords, API keys)
- Validate all user inputs
- Implement HTTPS in production
- Use Spring Security for authentication
- Implement rate limiting
- Add CSRF protection
- Sanitize output to prevent XSS

---

## 📞 Support & Documentation

- [Spring Boot Docs](https://spring.io/projects/spring-boot)
- [Spring Data JPA](https://spring.io/projects/spring-data-jpa)
- [Spring Cache](https://spring.io/guides/gs/caching/)
- [MySQL Documentation](https://dev.mysql.com/doc/)
- [Redis Documentation](https://redis.io/documentation)

---

## 🤝 Contributing

Contributions welcome! Please:
1. Fork the repository
2. Create a feature branch
3. Add tests for new features
4. Ensure all tests pass
5. Submit a pull request

---

## 📄 License

MIT License - See LICENSE file for details

---

## 👨‍💻 Author

**Tanish Tambe**
- GitHub: [@Tanish13-coder](https://github.com/Tanish13-coder)
- LinkedIn: [Tanish Tambe](https://linkedin.com/in/tanish-tambe-0319242a8)

---

**Made with ❤️ by Tanish**

Last Updated: 2026
