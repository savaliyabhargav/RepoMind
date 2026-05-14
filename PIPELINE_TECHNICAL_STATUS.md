# RepoMind 7-Stage Pipeline Technical Status (Current Implementation)

## 1. Document Purpose
This document is a deep technical handoff for the current RepoMind implementation, focused on:

- What is already implemented in code.
- How the 7-stage pipeline currently works.
- Which parts are production-ready vs partially implemented.
- Exact data flow, tables, APIs, and known gaps.
- What an incoming LLM/engineer should do next.

This is a code-grounded status from the current workspace at:

- `D:\Projects\RepoMind`

---

## 2. Repository Snapshot

### 2.1 Backend stack
- Java 21
- Spring Boot 4.0.4
- Spring MVC + Spring Data JPA + Flyway
- Spring WebClient (reactive client used with `.block()` in service layer)
- PostgreSQL 16
- Redis/Kafka/Qdrant containers defined in compose (not fully wired end-to-end for analysis streaming yet)

### 2.2 Frontend stack
- React 18 + Vite
- Zustand for auth state
- Axios with refresh-token interceptor

### 2.3 Important runtime conventions
- Backend context path is `/api` (from `application.yml`).
- Frontend calls `/api/*` through Vite proxy.
- DB schema governance is Flyway + `ddl-auto: validate`.

---

## 3. Implemented High-Level Features

## 3.1 Working now
- GitHub OAuth-based login flow exists (manual callback exchange).
- Repo ingestion endpoint fetches metadata + recursive tree and stores nodes.
- Repo tree retrieval endpoint returns stored file structure.
- Analysis endpoints exist:
  - start analysis
  - read analysis
  - read stage list
- 7-stage analysis pipeline class is implemented and persists outputs.
- AI provider abstraction implemented with NVIDIA backend client.
- AI health check endpoint implemented.
- AI call audit logging implemented (`ai_call_logs` table usage).

## 3.2 Partially complete / not fully proven live
- Analysis is currently synchronous HTTP-triggered execution (not yet Kafka async orchestration + SSE progress stream).
- Stage-7 embedding currently stores synthetic embedding IDs in DB and provider vectors are generated, but Qdrant persistence is not yet implemented in this code path.
- Stage logic uses metadata/path heuristics plus LLM summaries; deep source-code semantic parsing is limited.

---

## 4. Core Backend Configuration

File:
- `D:\Projects\RepoMind\backend\src\main\resources\application.yml`

Implemented AI config keys:
- `app.ai.provider` default: `NVIDIA_DEV`
- `app.ai.nvidia.base-url` default: `https://integrate.api.nvidia.com/v1`
- `app.ai.nvidia.api-key` from env `NVIDIA_API_KEY`

Other important backend settings:
- `server.servlet.context-path: /api`
- JPA `ddl-auto: validate`
- Flyway enabled
- Redis + Kafka hosts configurable by env

---

## 5. Analysis Domain Model (Implemented)

## 5.1 `analyses` entity
File:
- `D:\Projects\RepoMind\backend\src\main\java\com\repomind\backend\domain\analysis\Analysis.java`

Key fields in use:
- `id` UUID
- `repo` (FK)
- `user` (FK)
- `aiProvider`
- `status` (`PENDING/RUNNING/COMPLETED/FAILED`)
- `currentStage` int
- `scopeFileIds` mapped as PostgreSQL `uuid[]`
- `result` JSONB string payload
- `tokensUsed`
- `errorMsg`
- `completedAt`, `createdAt`

## 5.2 `analysis_stages` entity
Used to persist each stage:
- stage number
- stage name
- status
- stage JSON result
- tokens used
- timestamps

## 5.3 `file_nodes` extension fields leveraged by pipeline
- `roleSummary` (written in Stage 6)
- `embeddingId` (written in Stage 7)
- `sizeBytes`, `path`, `language`, `type`, `isInScope`

Repository methods used:
File:
- `D:\Projects\RepoMind\backend\src\main\java\com\repomind\backend\domain\repo\FileNodeRepository.java`

Notable methods:
- `findByRepoIdOrderByPathAsc`
- `updateRoleSummary(UUID id, String summary)` via `@Modifying`
- `updateEmbeddingId(UUID id, String embeddingId)` via `@Modifying`

## 5.4 AI call logging model
File:
- `D:\Projects\RepoMind\backend\src\main\java\com\repomind\backend\domain\ai\AiCallLog.java`

Captured fields:
- `provider`, `model`
- `inputTokens`, `outputTokens`
- `durationMs`
- `success`
- `errorMsg`
- links to `user` and `analysis`

---

## 6. 7-Stage Pipeline: Code-Level Behavior

Main file:
- `D:\Projects\RepoMind\backend\src\main\java\com\repomind\backend\service\analysis\AnalysisPipelineService.java`

## 6.1 Stage registry
Defined stages:
1. `OVERVIEW`
2. `ARCHITECTURE`
3. `MODULE_MAPPING`
4. `DATA_FLOW`
5. `BUG_DETECTION`
6. `FILE_ANNOTATION`
7. `EMBEDDING`

## 6.2 Pipeline execution lifecycle
Method:
- `runPipeline(UUID repoId, UUID userId, String aiProviderInput)`

Flow:
- Load repo + user.
- Normalize provider (`NVIDIA_DEV` fallback).
- Create `Analysis` row with `RUNNING`.
- Create all `AnalysisStage` rows initially as `PENDING`.
- Load all file nodes for repo.
- Split nodes into:
  - all files (`type == FILE`)
  - analyzable files filtered by extension + size
- For each stage:
  - mark stage `RUNNING` + set `startedAt`
  - set `analysis.currentStage`
  - execute stage handler
  - estimate tokens if stage doesn’t return explicit count
  - persist stage result JSON + token usage + completion metadata
- On success:
  - `analysis.status = COMPLETED`
  - persist full pipeline JSON in `analysis.result`
- On failure:
  - `analysis.status = FAILED`
  - persist `errorMsg`

## 6.3 Stage-by-stage implementation details

### Stage 1: OVERVIEW
Method:
- `buildOverview(...)`

Current logic:
- Build extension frequency map from ingested file paths.
- Map top extensions to high-level tech names.
- Guess entry point from filenames (`*Application.java`, `main.py`, `index.js`).
- Assemble overview JSON:
  - repoName, owner, defaultBranch
  - fileCount, directoryCount
  - techStack
  - entryPoint
- Calls LLM for short summary (`runStageSummary`).

### Stage 2: ARCHITECTURE
Method:
- `buildArchitecture(...)`

Current logic:
- Derive top-level module directories from file paths.
- Static style label currently hardcoded as `"Monolith"`.
- LLM generates concise architecture summary.

### Stage 3: MODULE_MAPPING
Method:
- `buildModuleMap(...)`

Current logic:
- Sort files by path, take first 30.
- For each file:
  - path
  - language (from existing metadata or inferred from extension)
  - role (heuristic `inferRole`)
- LLM provides short responsibility split summary.

### Stage 4: DATA_FLOW
Method:
- `buildDataFlow(...)`

Current logic:
- Filters filenames containing:
  - `controller`
  - `service`
  - `repository`
- Up to `MAX_DATAFLOW_FILES_PER_LAYER = 12` per category.
- LLM generates likely request lifecycle summary.

### Stage 5: BUG_DETECTION
Method:
- `buildRiskScan(...)`

Current logic:
- Sort files by descending size.
- Select top `MAX_RISK_FILES = 8`.
- Assign risk level:
  - `HIGH` if size > 40,000 bytes, else `MEDIUM`
- Attach generic reason text.
- LLM synthesizes top risk narrative.

### Stage 6: FILE_ANNOTATION
Method:
- `annotateFiles(...)`

Current logic:
- Filters candidates where size <= `250,000` bytes.
- Batch size = 20 files.
- For each batch:
  - request LLM JSON map `path -> one-line summary`
  - parse JSON via `ObjectMapper`
  - fallback to heuristic role if parsing/model fails
  - update each file node `role_summary` in DB
- Returns counts:
  - annotated files
  - skipped large files
  - threshold used

### Stage 7: EMBEDDING
Method:
- `buildEmbeddingStage(...)`

Current logic:
- Filters candidates where size <= `500,000` bytes.
- Uses placeholders:
  - `chunkSize = 1500`
  - `overlap = 200`
- For each candidate file:
  - create compact embedding input from `path + role`
  - call provider embedding API
  - accumulate token usage
  - write `embeddingId` string as `nvidia:<fileId>:<vectorDim>`
- Returns:
  - embedded file count
  - estimated chunk count
  - skipped large files
  - chunk settings
  - dependency stage list

Important:
- Qdrant upsert is not implemented in this method yet.
- This stage currently confirms provider embedding call path + DB linkage only.

---

## 7. AI Provider Layer (Implemented)

## 7.1 Abstraction
Interface file:
- `D:\Projects\RepoMind\backend\src\main\java\com\repomind\backend\service\ai\AiProviderClient.java`

Methods:
- `supports(String provider)`
- `generate(AiGenerationRequest request)`
- `embed(AiEmbeddingRequest request)`

Router:
- `AiProviderRouter` resolves first client where `supports(provider)` returns true.

## 7.2 NVIDIA client
File:
- `D:\Projects\RepoMind\backend\src\main\java\com\repomind\backend\service\ai\NvidiaAiProviderClient.java`

Implemented behaviors:
- Validates API key presence.
- Chat completion endpoint:
  - `POST /chat/completions`
  - model + temperature + max_tokens + messages
  - retry with backoff
  - timeout 90s
- Embedding endpoint:
  - `POST /embeddings`
  - model + input
  - retry with backoff
  - timeout 60s
- Extracts usage tokens from response JSON.
- Throws explicit exceptions when response structure invalid/empty.

---

## 8. AI Observability and Auditing

Within `AnalysisPipelineService`:
- every generation call goes through `executeGenerationCall`
- every embedding call goes through `executeEmbeddingCall`
- both call `persistAiCallLog(...)` with:
  - stage-associated error prefix
  - success/failure
  - duration
  - token counts

This gives analysis-level auditability per provider/model call.

---

## 9. Health and Runtime Verification Endpoints

File:
- `D:\Projects\RepoMind\backend\src\main\java\com\repomind\backend\api\health\HealthController.java`

Endpoints:
- `GET /api/health`
  - base service check
- `GET /api/health/ai`
  - active provider probe
  - sends minimal generation request
  - returns provider/model/reply/tokens
  - returns `DOWN` with error message on failure

Usefulness:
- Isolates provider config/API issues before running full analysis.

---

## 10. Repo Ingestion Path (Current Capability)

Main controller:
- `D:\Projects\RepoMind\backend\src\main\java\com\repomind\backend\api\repo\RepoController.java`

Endpoints:
- `POST /api/repo/ingest`
- `GET /api/repo/{repoId}`
- `GET /api/repo/{repoId}/tree`

Current confirmed result from user logs:
- Repo ingest succeeded for:
  - `savaliyabhargav/multi_threaded_web_server`
- Saved file nodes:
  - 36

This confirms ingestion and file-tree persistence are operational.

---

## 11. Auth Status and Known Runtime Issue

## 11.1 Current backend behavior
- OAuth code is exchanged for GitHub token.
- Backend fetches profile from `https://api.github.com/user`.
- If token is invalid/expired/code reused, GitHub returns `401`.

## 11.2 Observed issue
From logs:
- backend starts correctly
- login request fails with:
  - `401 Unauthorized from GET https://api.github.com/user`

## 11.3 Root cause observed in frontend callback behavior
File:
- `D:\Projects\RepoMind\frontend\src\screens\auth\LoginCallback.jsx`

Implemented fix:
- `useRef` guard (`hasProcessedRef`) ensures code exchange runs only once.
- This addresses duplicate exchange in React 18 dev strict-mode double effect execution.

Remaining auth caution:
- OAuth code is one-time-use and short-lived.
- Reusing old callback URL or rerender-triggered duplicate calls causes 401.

---

## 12. Serialization and ObjectMapper Fix Applied

File:
- `D:\Projects\RepoMind\backend\src\main\java\com\repomind\backend\config\JacksonConfig.java`

Reason:
- pipeline service depends on injected `ObjectMapper`.
- explicit bean added to avoid startup DI failure.

Current implementation:
- returns `new ObjectMapper()` bean.

---

## 13. API Contracts (Current)

## 13.1 Start analysis
`POST /api/analyses`

Request:
```json
{
  "repoId": "uuid",
  "userId": "uuid",
  "aiProvider": "NVIDIA_DEV"
}
```

Response:
- `AnalysisResponse` with analysis metadata/status.

## 13.2 Get analysis
`GET /api/analyses/{analysisId}`

Returns:
- current status
- stage pointer
- result/error/tokens metadata

## 13.3 Get stage list
`GET /api/analyses/{analysisId}/stages`

Returns:
- ordered list of stage results with per-stage status and token usage.

## 13.4 AI health
`GET /api/health/ai`

Returns on success:
```json
{
  "status": "UP",
  "provider": "NVIDIA_DEV",
  "model": "meta/llama-3.1-70b-instruct",
  "reply": "OK",
  "inputTokens": 0,
  "outputTokens": 0
}
```

Returns on failure:
```json
{
  "status": "DOWN",
  "provider": "NVIDIA_DEV",
  "error": "..."
}
```

---

## 14. Current Technical Gaps vs Target Architecture

## 14.1 Kafka/SSE orchestration gap
Target plan expects:
- async producer/consumer
- background stage progression
- SSE progress stream

Current:
- stage loop runs synchronously inside request thread in `runPipeline`.

## 14.2 Embedding persistence gap
Target plan expects:
- chunk-level vectors persisted to Qdrant
- retrievable for RAG

Current:
- provider embeddings are requested
- only synthetic `embeddingId` persisted in `file_nodes`
- no Qdrant upsert code in current stage method

## 14.3 Retrieval/chat integration depth
Target plan expects:
- query embeddings
- vector search by `repoId`
- prompt assembly + streaming answer

Current:
- foundational schema exists
- full retrieval + generation runtime path not fully integrated in code shown here

## 14.4 Analysis quality depth
Current stages use:
- path/extension/size heuristics
- bounded LLM summarization

Still needed for enterprise-grade depth:
- AST/language-aware parsing
- import dependency graph enrichment
- endpoint-to-query trace extraction with stronger evidence references

---

## 15. Verification Performed So Far

What was verified during recent work:
- backend compile success after pipeline and AI integration updates.
- backend startup success (multiple logs confirm app boot).
- ingestion operational (repo tree fetched and file nodes persisted).

What is still pending verification:
- complete successful full 7-stage run with live AI provider in current environment.
- DB inspection of:
  - `analyses`
  - `analysis_stages`
  - `file_nodes.role_summary`
  - `file_nodes.embedding_id`
  - `ai_call_logs`
- provider health endpoint runtime confirmation in user environment.

---

## 16. Practical Capability at Current Stage

The system can currently perform:
- repository structure understanding
- module inventory and role approximation
- architectural and flow summarization
- risk hotspot listing
- file role annotation (LLM with fallback)
- embedding API invocation + DB linkage placeholder

This means:
- architecture-level codebase understanding is available now.
- high-fidelity RAG answering pipeline is partially implemented and needs completion on vector persistence + retrieval loop.

---

## 17. Recommended Next Implementation Sequence

1. Stabilize auth runtime path in environment (fresh OAuth flow each login, validate callback single exchange).
2. Confirm `/api/health/ai` is `UP` with NVIDIA key.
3. Run one full analysis on a known repo and inspect all stage rows.
4. Implement true Stage-7 chunk extraction + Qdrant upsert.
5. Add retrieval endpoint:
   - query embedding
   - Qdrant search filtered by `repoId`
   - return top-k chunks with scores
6. Add SSE progress stream for analysis job updates.
7. Move analysis execution to Kafka-driven async workers.
8. Add deterministic retry and idempotency keys for analysis jobs.
9. Add structured error taxonomy for provider/network/auth failures.
10. Add integration tests for:
   - stage persistence
   - ai_call_logs writes
   - auth callback single-call behavior

---

## 18. Operational Notes for Another LLM/Engineer

When taking over this codebase:
- Do not assume pipeline is event-driven yet; it is synchronous now.
- Do not assume embeddings are in Qdrant; verify before using retrieval.
- Treat auth 401 during `/github/user` as token/code validity issue first, not app boot issue.
- Use `RepoController` tree endpoint and `AnalysisController` stage endpoint for deterministic validation.
- Keep Flyway migration-first discipline for any schema updates.

---

## 19. Key Files Index

Pipeline:
- `D:\Projects\RepoMind\backend\src\main\java\com\repomind\backend\service\analysis\AnalysisPipelineService.java`

Analysis API:
- `D:\Projects\RepoMind\backend\src\main\java\com\repomind\backend\api\analysis\AnalysisController.java`

AI provider abstraction:
- `D:\Projects\RepoMind\backend\src\main\java\com\repomind\backend\service\ai\AiProviderClient.java`
- `D:\Projects\RepoMind\backend\src\main\java\com\repomind\backend\service\ai\AiProviderRouter.java`
- `D:\Projects\RepoMind\backend\src\main\java\com\repomind\backend\service\ai\NvidiaAiProviderClient.java`

AI health:
- `D:\Projects\RepoMind\backend\src\main\java\com\repomind\backend\api\health\HealthController.java`

AI call logs:
- `D:\Projects\RepoMind\backend\src\main\java\com\repomind\backend\domain\ai\AiCallLog.java`
- `D:\Projects\RepoMind\backend\src\main\java\com\repomind\backend\domain\ai\AiCallLogRepository.java`

Repo ingestion and tree API:
- `D:\Projects\RepoMind\backend\src\main\java\com\repomind\backend\api\repo\RepoController.java`
- `D:\Projects\RepoMind\backend\src\main\java\com\repomind\backend\service\ingestion\IngestionService.java`

Auth path:
- `D:\Projects\RepoMind\backend\src\main\java\com\repomind\backend\api\auth\AuthController.java`
- `D:\Projects\RepoMind\backend\src\main\java\com\repomind\backend\service\auth\AuthService.java`
- `D:\Projects\RepoMind\backend\src\main\java\com\repomind\backend\service\auth\GitHubService.java`
- `D:\Projects\RepoMind\frontend\src\screens\auth\LoginCallback.jsx`

Config:
- `D:\Projects\RepoMind\backend\src\main\resources\application.yml`
- `D:\Projects\RepoMind\backend\src\main\java\com\repomind\backend\config\JacksonConfig.java`

---

## 20. Final Status Summary

RepoMind currently has a real, persisted, stage-driven analysis backbone in place with AI provider integration and audit logging. Ingestion and data persistence are functioning. The major remaining work is to complete asynchronous orchestration (Kafka/SSE in runtime), production-grade embedding storage/retrieval integration with Qdrant, and finalize auth/runtime hardening around OAuth exchange reliability.

This is no longer a blank prototype; it is a structured base that can be extended into full enterprise-grade pipeline behavior with focused next milestones.
