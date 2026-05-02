# 🧠 AGENTS.md — The RepoMind Master Context & Engineering Standard

> **READ THIS BEFORE WRITING A SINGLE LINE OF CODE.**
> This document defines the exact, uncompromising engineering standard for RepoMind. We do not write "quick scripts" or "prototypes". Every feature must be built for production from day one. If it takes thousands of lines of planning, we plan it. If it requires 3 retry loops with exponential backoff, we build it. Zero shortcuts.

---

## 🛑 1. CORE ENGINEERING MANDATES

1.  **Production-Grade Execution:** Every implementation must handle edge cases, serialization traps (e.g., Jackson stripping the `is` prefix to output `private`), network timeouts, and exact JSON schema mapping.
2.  **Database Integrity is God:** NEVER use Hibernate `ddl-auto: update` or `create`. The database schema is exclusively owned and governed by **Flyway migrations**. If an entity does not perfectly match the database schema, the app must crash on startup (`ddl-auto: validate`). All primary keys are `UUID`s (`gen_random_uuid()`). All timestamps are timezone-aware (`TIMESTAMPTZ`).
3.  **Asynchronous by Default:** For any I/O bound or network-heavy task (GitHub API, AI Providers), use the non-blocking `WebClient`. For long-running jobs (AI Analysis), use **Kafka** to decouple the HTTP request from the processing layer.
4.  **Security Architecture:** 
    *   JWTs are signed with **RSA-256 asymmetric keys**.
    *   Refresh tokens are UUIDs stored in the database as **SHA-256 hashes**.
    *   The raw refresh token is sent to the client strictly via an **HttpOnly, Secure, SameSite=Strict Cookie**.
5.  **Empirical Verification:** Never assume a JSON response structure based solely on Java class field names. Always verify the serialized output.

---

## 🏗️ 2. SYSTEM ARCHITECTURE & INFRASTRUCTURE

RepoMind operates as a **containerized monolith** designed for a future microservice split. It runs on a custom Docker bridge network (`repomind-network`).

### The 10-Service Stack
1.  **Backend (`repomind-backend`):** Java 21, Spring Boot 4.0, Port 8080 (Remote Debug Port 5005).
2.  **Frontend (`repomind-frontend`):** React 18, Vite, Port 5173. Uses Vite proxy to forward `/api` requests to backend to bypass CORS natively.
3.  **Database (`repomind-postgres`):** PostgreSQL 16, Port 5432.
4.  **Cache/Session (`repomind-redis`):** Redis 7, Port 6379. Used for high-speed API caching and rate limiting.
5.  **Vector DB (`repomind-qdrant`):** Qdrant v1.11.0, Port 6333. Stores 1536-dimensional semantic code chunks.
6.  **Message Queue (`repomind-kafka`):** Apache Kafka 7.6.0, Port 9092 (Internal: 29092). Drives the 7-stage analysis pipeline.
7.  **Queue Coordinator (`repomind-zookeeper`):** Zookeeper 7.6.0, Port 2181.
8.  **Object Storage (`repomind-minio`):** MinIO, Ports 9000/9001. S3-compatible local storage.
9.  **DB UI (`repomind-pgadmin`):** pgAdmin 4, Port 5050.
10. **Kafka UI (`repomind-kafka-ui`):** Provectus Kafka UI, Port 8090.

---

## 🔐 3. AUTHENTICATION & SECURITY IMPLEMENTATION

**Flow:** GitHub OAuth2 -> Backend DB Check -> JWT + Cookie Generation

*   **OAuth2 Disable:** Default Spring Security OAuth2 auto-configuration is explicitly disabled (`oauth2Login.disable()`). We manually handle the OAuth callback in the frontend (`LoginCallback.jsx`).
*   **JWT Access Token:** Valid for 15 minutes. Validated via `JwtAuthFilter` using `public_key.pem`.
*   **Refresh Token Rotation:** Valid for 7 days. Upon refresh request, the old token is instantly deleted from DB.

---

## 📥 4. REPOSITORY INGESTION ENGINE

The data bridge between GitHub's Cloud and RepoMind PostgreSQL.

### Exact Workflow (`POST /api/repo/ingest`)
1.  **Validation:** Regex checks the GitHub URL format.
2.  **Metadata:** Calls `https://api.github.com/repos/{owner}/{repo}` to get `size`, `default_branch`, and `private` status.
3.  **Recursive Tree Fetch:** Calls `https://api.github.com/repos/{owner}/{repo}/git/trees/{branch}?recursive=1`. This fetches the full tree in **one single API call**.
4.  **Batch Persistence:** Persists all `FileNode` entities to PostgreSQL using Spring Data JPA batching (`spring.jpa.properties.hibernate.jdbc.batch_size=50`).
5.  **Response Schema:**
    ```json
    {
        "id": "uuid",
        "user": { "id": "uuid", "username": "string", "avatarUrl": "string", "plan": "FREE" },
        "url": "string",
        "name": "string",
        "owner": "string",
        "provider": "GITHUB",
        "defaultBranch": "main",
        "fileCount": 176,
        "sizeKb": 10865,
        "status": "READY",
        "private": false,  // Jackson explicitly outputs 'private'
        "createdAt": "iso-8601-string"
    }
    ```

---

## 🧠 5. THE 7-STAGE AI ANALYSIS PIPELINE (Kafka + SSE)

This is the core intelligence engine. It is completely decoupled from the HTTP request thread.

### Event-Driven Architecture
1.  **Trigger:** `POST /api/analyses` publishes `AnalysisPipelineMessage` to Kafka topic `repo-analysis-jobs` (3 partitions).
2.  **Streaming:** The HTTP request instantly returns the `analysisId`. The frontend connects to `GET /api/analyses/{id}/stream` using SSE (`SseEmitter`).
3.  **Progress Updates:** The Kafka consumer pushes `ANALYSIS_PROGRESS` and `STAGE_RESULT` events through a thread-safe `SseEmitterRegistry` back to the browser.

### The Stages (Execute sequentially, passing context forward)
*   **Stage 1: OVERVIEW.** Reads `README.md`, configs. Extracts tech stack, purpose, and entry points.
*   **Stage 2: ARCHITECTURE.** Reads routers based on Stage 1. Maps architectural patterns.
*   **Stage 3: MODULE MAPPING.** Selects max 30 key files. Maps responsibility boundaries.
*   **Stage 4: DATA FLOW.** Traces request lifecycle and DB persistence paths.
*   **Stage 5: BUG DETECTION.** Identifies technical debt and security risks based on full context.
*   **Stage 6: FILE ANNOTATION (Heaviest Stage).** Batches files in chunks of 20. Calls AI to write a one-line `role_summary` for EVERY single file.
*   **Stage 7: EMBEDDING (Vectorization).** Uses OpenAI `text-embedding-3-small`. Chunking Strategy: 1500 chars per chunk, **200 char overlap**. Stored in Qdrant `repomind-embeddings` collection.

---

## 🗄️ 6. DATABASE SCHEMA (Empirically Verified via Flyway Migrations)

Every table definition below exactly matches the SQL execution.

1.  **`users`**: 
    `id` UUID PK, `github_id` BIGINT UNIQUE NOT NULL, `email` VARCHAR(255), `username` VARCHAR(100) NOT NULL, `avatar_url` TEXT, `github_token` TEXT, `plan` VARCHAR(20) DEFAULT 'FREE', `created_at` TIMESTAMPTZ, `updated_at` TIMESTAMPTZ. 
    *(Indexes on `github_id`, `email`)*
2.  **`refresh_tokens`**: 
    `id` UUID PK, `user_id` UUID FK(users) ON DELETE CASCADE, `token_hash` VARCHAR(64) UNIQUE NOT NULL, `expires_at` TIMESTAMPTZ NOT NULL, `revoked` BOOLEAN DEFAULT FALSE, `created_at` TIMESTAMPTZ. 
    *(Indexes on `user_id`, `token_hash`)*
3.  **`repos`**: 
    `id` UUID PK, `user_id` UUID FK(users) ON DELETE CASCADE, `url` TEXT NOT NULL, `name` VARCHAR(255) NOT NULL, `owner` VARCHAR(255) NOT NULL, `provider` VARCHAR(20) DEFAULT 'GITHUB', `is_private` BOOLEAN DEFAULT FALSE, `default_branch` VARCHAR(100) DEFAULT 'main', `file_count` INT DEFAULT 0, `size_kb` BIGINT DEFAULT 0, `status` VARCHAR(20) DEFAULT 'PENDING', `error_msg` TEXT, `created_at` TIMESTAMPTZ. 
    *(Indexes on `user_id`, `status`)*
4.  **`file_nodes`**: 
    `id` UUID PK, `repo_id` UUID FK(repos) ON DELETE CASCADE, `path` TEXT NOT NULL, `name` VARCHAR(255) NOT NULL, `type` VARCHAR(10) NOT NULL, `depth` INT DEFAULT 0, `size_bytes` BIGINT DEFAULT 0, `language` VARCHAR(50), `role_summary` TEXT, `embedding_id` VARCHAR(100), `is_in_scope` BOOLEAN DEFAULT TRUE, `created_at` TIMESTAMPTZ. 
    *(UNIQUE on `repo_id, path`. Indexes on `repo_id`, `language`)*
5.  **`file_node_dependencies`**: 
    `id` UUID PK, `source_file_id` UUID FK(file_nodes) ON DELETE CASCADE, `target_file_id` UUID FK(file_nodes) ON DELETE CASCADE, `relationship_type` VARCHAR(20) NOT NULL. 
    *(UNIQUE on `source_file_id, target_file_id, relationship_type`. Indexes on `source_file_id`, `target_file_id`)*
6.  **`analyses`**: 
    `id` UUID PK, `repo_id` UUID FK(repos) ON DELETE CASCADE, `user_id` UUID FK(users) ON DELETE CASCADE, `ai_provider` VARCHAR(20) DEFAULT 'Codex', `status` VARCHAR(20) DEFAULT 'PENDING', `current_stage` INT DEFAULT 0, `scope_file_ids` UUID[], `result` JSONB, `error_msg` TEXT, `tokens_used` INT DEFAULT 0, `created_at` TIMESTAMPTZ, `completed_at` TIMESTAMPTZ. 
    *(Indexes on `repo_id`, `user_id`, `status`)*
7.  **`analysis_stages`**: 
    `id` UUID PK, `analysis_id` UUID FK(analyses) ON DELETE CASCADE, `stage_number` INT NOT NULL, `stage_name` VARCHAR(50) NOT NULL, `status` VARCHAR(20) DEFAULT 'PENDING', `result` JSONB, `tokens_used` INT DEFAULT 0, `started_at` TIMESTAMPTZ, `completed_at` TIMESTAMPTZ. 
    *(UNIQUE on `analysis_id, stage_number`. Index on `analysis_id`)*
8.  **`chat_sessions`**: 
    `id` UUID PK, `user_id` UUID FK(users) ON DELETE CASCADE, `analysis_id` UUID FK(analyses) ON DELETE CASCADE, `title` VARCHAR(255), `mode` VARCHAR(30) DEFAULT 'SUBSYSTEM_EXPLAINER', `message_count` INT DEFAULT 0, `created_at` TIMESTAMPTZ, `last_active` TIMESTAMPTZ. 
    *(Indexes on `user_id`, `analysis_id`)*
9.  **`chat_messages`**: 
    `id` UUID PK, `session_id` UUID FK(chat_sessions) ON DELETE CASCADE, `role` VARCHAR(10) NOT NULL, `content` TEXT NOT NULL, `tokens_used` INT DEFAULT 0, `context_chunks` JSONB, `created_at` TIMESTAMPTZ. 
    *(Index on `session_id`)*
10. **`prompt_templates`**: 
    `id` UUID PK, `name` VARCHAR(100) UNIQUE NOT NULL, `system_prompt` TEXT NOT NULL, `user_prompt` TEXT NOT NULL, `provider` VARCHAR(20) DEFAULT 'Codex', `version` INT DEFAULT 1, `is_active` BOOLEAN DEFAULT TRUE, `created_at` TIMESTAMPTZ.
11. **`ai_call_logs`**: 
    `id` UUID PK, `user_id` UUID FK(users) ON DELETE SET NULL, `analysis_id` UUID FK(analyses) ON DELETE SET NULL, `session_id` UUID FK(chat_sessions) ON DELETE SET NULL, `provider` VARCHAR(20) NOT NULL, `model` VARCHAR(50) NOT NULL, `input_tokens` INT DEFAULT 0, `output_tokens` INT DEFAULT 0, `duration_ms` BIGINT DEFAULT 0, `success` BOOLEAN DEFAULT TRUE, `error_msg` TEXT, `created_at` TIMESTAMPTZ. 
    *(Indexes on `user_id`, `analysis_id`, `created_at`)*
12. **`share_links`**: 
    `id` UUID PK, `analysis_id` UUID FK(analyses) ON DELETE CASCADE, `created_by` UUID FK(users) ON DELETE CASCADE, `token` VARCHAR(64) UNIQUE NOT NULL, `expires_at` TIMESTAMPTZ, `view_count` INT DEFAULT 0, `is_active` BOOLEAN DEFAULT TRUE, `created_at` TIMESTAMPTZ. 
    *(Indexes on `token`, `analysis_id`)*

---

## 🛠️ 7. FRONTEND STATE & RAG CHAT

### Zustand Stores
*   `authStore.js`: Holds `user`, `token` (JWT), `isAuthenticated`. Interceptors in Axios handle the silent 401 refresh seamlessly.
*   `analysisStore.js`: Holds currently active `Repo` and `Analysis` objects to populate UI trees.

### RAG Chat System
1.  **Embed Query:** Convert user question to 1536-dim vector via OpenAI.
2.  **Semantic Search:** Query Qdrant `repomind-embeddings`, filtering strictly by `repoId`.
3.  **Context Assembly:** Inject top-K results into the DB-loaded Prompt Template.
4.  **Streaming:** Send to AI Provider. Stream response back to React frontend via SSE token-by-token.
5.  **Audit:** Save message to DB, storing the exact `context_chunks` JSON array alongside it for debugging.

---

## 🎯 FINAL RULE
**"Good enough" is not acceptable.** If implementing a feature, consider the DB transaction boundaries, the Kafka retry mechanisms, the Jackson JSON serialization, the Docker network resolution, and the React render cycles. **We build for the Enterprise.**
