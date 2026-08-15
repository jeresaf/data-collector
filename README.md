# ODK-Compatible Spring Boot Platform

A Spring Boot 3 + JavaRosa 4.3.2 backend for ODK Collect, KoboCollect, and custom web form clients.

## Quick Start (Local)

### Prerequisites
- Java 21+
- Maven 3.9+
- PostgreSQL 15+

### Database Setup
```bash
# Create database and user
psql -U postgres -c "CREATE DATABASE odk_platform;"
psql -U postgres -c "CREATE USER odk_user WITH ENCRYPTED PASSWORD 'odk_pass';"
psql -U postgres -c "GRANT ALL PRIVILEGES ON DATABASE odk_platform TO odk_user;"
```

### Run Backend
```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Backend runs at `http://localhost:8080`

### Health Check
```bash
curl http://localhost:8080/actuator/health
```

### API Endpoints
- `POST /api/auth/login` - Web login (JWT)
- `POST /api/auth/logout` - Logout
- `GET /api/auth/me` - Current user
- `GET /actuator/health` - Health check

---

## Docker Compose (Recommended)

```bash
docker-compose up -d
```

Services:
- **backend**: `http://localhost:8080`
- **postgres**: `localhost:5432`
- **frontend**: `http://localhost:5173` (when implemented)

### Stop
```bash
docker-compose down
```

### Stop with volumes (reset DB)
```bash
docker-compose down -v
```

---

## Project Structure

```
data-collector-nvidia/
├── backend/                 # Spring Boot 3 backend
│   ├── src/main/java/org/odk/platform/
│   │   ├── auth/           # Authentication (JWT, OpenRosa Basic Auth)
│   │   ├── audit/          # Audit logging (AOP-based)
│   │   ├── config/         # Configuration (Async, WebMvc)
│   │   ├── domain/         # JPA entities
│   │   ├── exception/      # Exception handling
│   │   ├── repository/     # Spring Data repositories
│   │   └── security/       # Security config, JWT, filters
│   └── src/main/resources/
│       ├── application.yml
│       └── db/migration/   # Flyway migrations
├── frontend/               # Vue 3 + TypeScript (TODO)
├── docs/
│   └── reference/          # XLSForm reference docs
└── scripts/                # Utility scripts
```

---

## Configuration

### Profiles
- `local` - Local development (H2 or PostgreSQL)
- `test` - Test profile (H2 in-memory)
- `prod` - Production (PostgreSQL)

### Key Properties (application.yml)
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/odk_platform
    username: odk_user
    password: odk_pass
  flyway:
    enabled: true
  jpa:
    hibernate:
      ddl-auto: validate
```

---

## API Documentation

### Authentication
- **Web**: JWT Bearer token via `/api/auth/login`
- **Mobile (ODK/Kobo)**: HTTP Basic Auth via OpenRosa endpoints

### OpenRosa Endpoints (Mobile Clients)
- `GET /v1/projects/{projectId}/formList` - Form list
- `GET /v1/projects/{projectId}/forms/{formId}.xml` - Form XML
- `GET /v1/projects/{projectId}/forms/{formId}/manifest` - Media manifest
- `POST /v1/projects/{projectId}/submission` - Submit form

---

## Development

### Run Tests
```bash
cd backend
mvn test
```

### Build
```bash
cd backend
mvn clean package -DskipTests
```

### Database Migrations
Flyway migrations in `backend/src/main/resources/db/migration/`
- `V1__initial_schema.sql` - Core schema

---

## Tech Stack

| Layer | Technology |
|-------|------------|
| Backend | Spring Boot 3.2, Java 21 |
| Database | PostgreSQL 15, Flyway |
| Security | Spring Security, JWT, BCrypt |
| Validation | JavaRosa 4.3.2 (XForm) |
| Build | Maven |
| Frontend | Vue 3, TypeScript, Vite (planned) |

---

## License

Proprietary - Internal Use Only