# RepoMind — Complete Project Documentation
### Every decision, every plan, every detail from day one

---

## Table of Contents

1. [What is RepoMind?](#1-what-is-repomind)
2. [The Problem We Are Solving](#2-the-problem-we-are-solving)
3. [The 5 Core Features — AI Chat Modes](#3-the-5-core-features)
4. [Complete Tech Stack — Every Technology Explained](#4-complete-tech-stack)
5. [Architecture Decision — Monolith First](#5-architecture-decision)
6. [Complete Backend Package Structure](#6-complete-backend-package-structure)
7. [Complete Frontend Structure](#7-complete-frontend-structure)
8. [Database — All 11 Tables With Every Column](#8-database)
9. [How the Core Features Work Internally](#9-how-core-features-work)
10. [Project Folder Structure on Disk](#10-project-folder-structure)
11. [How Docker Works in This Project](#11-how-docker-works)
12. [All Running Services — Ports, URLs, Credentials](#12-all-running-services)
13. [Dashboard Login Guide](#13-dashboard-login-guide)
14. [Every File — What It Is and Why We Created It](#14-every-file)
15. [Problems We Hit and How We Solved Them](#15-problems-we-hit)
16. [How to Start and Stop Everything](#16-how-to-start-and-stop)
17. [Current Status of the Code](#17-current-status)
18. [Complete Planned Implementation Roadmap](#18-roadmap)
19. [All Dependencies Reference](#19-dependencies)
20. [What You Need to Learn at This Stage](#20-what-to-learn)

---

## 1. What is RepoMind?

RepoMind is an **AI-powered GitHub repository analyzer**.

The idea is simple: as a developer, when you join a new project or look at an open source library, understanding the entire codebase takes days or weeks. You have to read every file, figure out how things connect, understand the architecture — it is exhausting and time consuming.

RepoMind solves this. You paste a GitHub repository URL, and within minutes the AI analyzes the entire codebase and delivers a complete understanding of it.

---

## 2. The Problem We Are Solving

When a developer encounters an unfamiliar codebase they face these problems:

- No idea where the entry point is or how the app boots
- Cannot tell which files are important and which are boilerplate
- Do not understand how modules connect to each other
- Cannot find where a specific feature is implemented
- Do not know what will break if they change something
- Cannot identify existing bugs or risky code patterns

RepoMind solves all of these with AI analysis and a specialized chat system.

---

## 3. The 5 Core Features

### What RepoMind delivers after analyzing a repo:

**Feature 1 — Full Project Overview**
- What the project does in plain English
- Complete tech stack detection
- Architecture description and design patterns used
- Entry point identification

**Feature 2 — Annotated File Tree**
- Every single file in the repo listed
- Each file has a one-line AI-generated role summary
- Example: `App.java` → "Entry point, bootstraps Spring context and starts embedded Tomcat"

**Feature 3 — On-Demand File Deep Dive**
- Click any file to get a full deep dive
- Internal logic explanation
- All dependencies of that file
- Potential bugs
- How to safely modify it

**Feature 4 — AI Chat with 5 Specialized Modes**

| Mode | What you do | What you get |
|---|---|---|
| Bug Search | Describe a bug symptom | Exact files and root cause |
| Enhancement Guide | Describe a feature to add | File-by-file change plan |
| Subsystem Explainer | Select any folder or flow | Full narrative explanation |
| Code Search | Ask in plain English | Where it is implemented |
| Impact Analysis | Describe a change | Everything that would break |

**Feature 5 — Live Analysis Progress**
- Analysis streams live to the browser via SSE (Server-Sent Events)
- User sees each of the 7 stages completing in real time
- No blind waiting — always knows what is happening

---

## 4. Complete Tech Stack

### Every technology, what it is, and why we chose it

| Layer | Technology | Version | What it is | Why we use it |
|---|---|---|---|---|
| Backend Language | Java | 21 | Programming language | Latest LTS, modern features — records, sealed classes, virtual threads |
| Backend Framework | Spring Boot | 4.x | Java web framework | Industry standard REST API framework, handles boilerplate automatically |
| Build Tool | Maven | 3.9 | Dependency manager and build tool | Like npm for Java — manages all Java libraries |
| Primary Database | PostgreSQL | 16 | Relational database | Stores all structured data permanently with full ACID guarantees |
| Cache + Sessions | Redis | 7 | In-memory data store | 10-100x faster than PostgreSQL — used for token validation on every request |
| Vector Database | Qdrant | v1.11.0 | Vector/embedding database | Stores AI embeddings for semantic code search — no normal DB can do this |
| Message Queue | Kafka | 7.6.0 | Distributed message queue | Handles long-running async analysis jobs — browser can't wait 10 minutes |
| Kafka Coordinator | Zookeeper | 7.6.0 | Distributed coordinator | Internal Kafka management — never touched directly |
| Object Storage | MinIO | latest | Self-hosted file storage | Stores uploaded ZIP files — like Amazon S3 but running locally |
| AI Provider Primary | Anthropic Claude | claude-opus-4-5 | Large language model | Primary AI for all analysis and chat |
| AI Provider Fallback | OpenAI GPT-4o | gpt-4o | Large language model | Fallback if Claude is unavailable or rate-limited |
| Auth | GitHub OAuth2 + JWT RS256 | - | Authentication system | Login with GitHub, stateless JWT tokens for every request |
| Frontend Library | React | 19.x | UI component library | Build interactive interfaces with reusable components |
| Frontend Build Tool | Vite | 6.x | Dev server and bundler | Extremely fast dev server with instant hot reload |
| Frontend Language | JavaScript JSX | ES2024 | Language | JSX is JavaScript with HTML-like syntax for React |
| Routing | React Router | 7.x | Client-side routing | Navigate between pages without full browser reload |
| HTTP Client | Axios | 1.x | HTTP request library | Make API calls from browser to backend |
| State Management | Zustand | 5.x | Global state library | Store logged-in user, current analysis in simple global state |
| Frontend Server (prod) | Nginx | latest | Web server and reverse proxy | Serves built React app, proxies /api/ calls to backend |
| Containerization | Docker | latest | Container platform | Run all services in isolated containers |
| Orchestration | Docker Compose | latest | Multi-container manager | Start all 10 services with one command |
| DB Migrations | Flyway | included | Database migration tool | Manages all schema changes through versioned SQL files |
| ORM | Hibernate | included | Object Relational Mapper | Maps Java classes to database tables |
| Security | Spring Security | included | Security framework | Authentication filters, endpoint protection |
| Code Reduction | Lombok | included | Annotation processor | Generates getters, setters, builders at compile time |

---

## 5. Architecture Decision — Monolith First

### Stage 1 — What we are building now: Monolith

One Spring Boot application. One codebase. One database. All features in one place.

**Why monolith first:**
- Faster to build and iterate
- Easier to debug — everything is in one process
- No network calls between services
- One deployment, one Docker container for the backend
- Can always split into microservices later once the domain is understood

### Stage 2 — Future: Microservices (NOT being built now)

The planned future split into 6 microservices:

| Microservice | Responsibility |
|---|---|
| Auth Service | GitHub OAuth2, JWT issuance, token refresh |
| Repo Ingestion Service | GitHub API client, file tree fetching, ZIP processing |
| Analysis Service | 7-stage AI pipeline orchestration |
| AI Gateway Service | All AI provider calls, prompt management, cost tracking |
| Chat Service | RAG pipeline, session management, streaming responses |
| User Service | User profiles, plans, share links |

**Important:** We do NOT build microservices now. This is future planning only.

---

## 6. Complete Backend Package Structure

This is the full planned Java package structure for the monolith. Currently only the root package exists — the rest is built incrementally as features are implemented.

```
com.repomind.backend
│
├── BackendApplication.java              # Main entry point — @SpringBootApplication
│
├── api/                                 # Controllers and DTOs — HTTP layer ONLY
│   ├── auth/
│   │   └── AuthController.java          # POST /api/auth/github, POST /api/auth/refresh, POST /api/auth/logout
│   ├── repo/
│   │   └── RepoController.java          # POST /api/repos, GET /api/repos/{id}, GET /api/repos/{id}/tree
│   ├── analysis/
│   │   └── AnalysisController.java      # POST /api/analyses, GET /api/analyses/{id}, GET /api/analyses/{id}/stream (SSE)
│   └── chat/
│       └── ChatController.java          # POST /api/chat/sessions, POST /api/chat/sessions/{id}/messages
│
├── config/                              # All Spring configuration beans
│   ├── SecurityConfig.java              # Spring Security filter chain, CORS, OAuth2, JWT
│   ├── RedisConfig.java                 # Redis connection factory, cache manager, serializers
│   ├── WebClientConfig.java             # WebClient beans for GitHub API and AI providers
│   ├── AsyncConfig.java                 # Thread pool configuration for @Async methods
│   └── AppProperties.java               # @ConfigurationProperties — typed config from application.yml
│
├── domain/                              # JPA Entities and Spring Data Repositories
│   ├── user/
│   │   ├── User.java                    # @Entity — maps to users table
│   │   └── UserRepository.java          # JpaRepository<User, UUID>
│   ├── repo/
│   │   ├── Repo.java                    # @Entity — maps to repos table
│   │   ├── FileNode.java                # @Entity — maps to file_nodes table
│   │   ├── FileDependency.java          # @Entity — maps to file_node_dependencies table
│   │   ├── RepoRepository.java
│   │   ├── FileNodeRepository.java
│   │   └── FileDependencyRepository.java
│   ├── analysis/
│   │   ├── Analysis.java                # @Entity — maps to analyses table
│   │   ├── AnalysisStage.java           # @Entity — maps to analysis_stages table
│   │   ├── AnalysisRepository.java
│   │   └── AnalysisStageRepository.java
│   └── chat/
│       ├── ChatSession.java             # @Entity — maps to chat_sessions table
│       ├── ChatMessage.java             # @Entity — maps to chat_messages table
│       ├── ChatSessionRepository.java
│       └── ChatMessageRepository.java
│
├── service/                             # All business logic lives here
│   ├── auth/
│   │   ├── JwtService.java              # Generate and validate JWT tokens (RS256)
│   │   ├── OAuth2UserService.java       # Process GitHub OAuth2 user info, create/update User record
│   │   └── RefreshTokenService.java     # Issue, validate, rotate, revoke refresh tokens
│   ├── ingestion/
│   │   ├── RepoIngestionService.java    # Orchestrates the full ingestion flow
│   │   ├── GitHubApiClient.java         # Calls GitHub API — fetch file tree, file contents
│   │   ├── FileTreeBuilder.java         # Builds hierarchical file tree from flat GitHub API response
│   │   ├── FileChunker.java             # Splits large files into chunks for embedding
│   │   └── ZipIngestionService.java     # Handles ZIP file upload path via MinIO
│   ├── analysis/
│   │   ├── AnalysisPipelineService.java # Orchestrates all 7 stages in sequence
│   │   └── stages/
│   │       ├── OverviewStage.java       # Stage 1 — reads README + config files → project summary
│   │       ├── ArchitectureStage.java   # Stage 2 — reads entry points → architecture description
│   │       ├── ModuleMappingStage.java  # Stage 3 — reads controllers/services → module map
│   │       ├── DataFlowStage.java       # Stage 4 — reads route handlers → request lifecycle
│   │       ├── BugDetectionStage.java   # Stage 5 — reads file summaries → risk areas
│   │       ├── FileAnnotationStage.java # Stage 6 — sends 20 files per call → role_summary for each
│   │       └── EmbeddingStage.java      # Stage 7 — embeds all chunks → stores in Qdrant
│   ├── ai/
│   │   ├── AiGatewayService.java        # Single entry point for all AI calls — routes to provider
│   │   ├── PromptTemplateService.java   # Loads prompt templates from DB, fills placeholders
│   │   ├── AiCallLogService.java        # Records every AI call to ai_call_logs table
│   │   └── providers/
│   │       ├── AiProvider.java          # Interface — every provider must implement this
│   │       ├── ClaudeProvider.java      # Anthropic Claude implementation
│   │       └── OpenAiProvider.java      # OpenAI GPT-4o implementation (fallback)
│   └── chat/
│       ├── ChatService.java             # Manages sessions and messages
│       ├── RagService.java              # RAG pipeline — embed query → search Qdrant → return chunks
│       └── ContextAssembler.java        # Assembles retrieved chunks into AI prompt context
│
├── infrastructure/                      # Adapters for external systems
│   ├── qdrant/
│   │   └── QdrantClient.java            # HTTP client for Qdrant REST API — upsert, search vectors
│   ├── redis/
│   │   └── RedisTokenStore.java         # Store and retrieve refresh tokens from Redis
│   ├── github/
│   │   └── GitHubProperties.java        # GitHub API configuration properties
│   └── storage/
│       └── MinioStorageService.java     # Upload/download files to/from MinIO buckets
│
├── security/                            # Security filter classes
│   ├── JwtAuthFilter.java               # Intercepts every request, validates JWT bearer token
│   ├── OAuth2SuccessHandler.java        # Called after successful GitHub login — issues JWT
│   └── CustomOAuth2User.java            # Wraps GitHub OAuth2 user info into our domain model
│
├── exception/                           # Error handling
│   ├── GlobalExceptionHandler.java      # @ControllerAdvice — catches all exceptions, returns clean JSON errors
│   ├── RepoNotFoundException.java
│   ├── AnalysisNotFoundException.java
│   ├── UnauthorizedException.java
│   └── AiProviderException.java
│
└── aop/                                 # Cross-cutting concerns
    ├── LogExecutionTimeAspect.java      # @Around every service method — logs how long it took
    └── AiCallAuditAspect.java           # @Around AI calls — automatically logs to ai_call_logs
```

---

## 7. Complete Frontend Structure

```
frontend/src/
│
├── components/
│   ├── common/                          # Reusable UI components used across multiple screens
│   │   ├── Button.jsx
│   │   ├── Input.jsx
│   │   ├── Spinner.jsx
│   │   └── ErrorMessage.jsx
│   ├── layout/
│   │   ├── AppLayout.jsx                # Main layout wrapper with nav
│   │   └── Navbar.jsx
│   └── screens/
│       ├── landing/
│       │   └── LandingScreen.jsx        # Route: / — URL input, submit for analysis
│       ├── loading/
│       │   └── LoadingScreen.jsx        # Route: /analyze/:repoId/loading — SSE live progress
│       ├── overview/
│       │   └── OverviewScreen.jsx       # Route: /analyze/:repoId/overview — project summary
│       ├── explorer/
│       │   └── ExplorerScreen.jsx       # Route: /analyze/:repoId/explorer — file tree + deep dive
│       └── chat/
│           └── ChatScreen.jsx           # Route: /analyze/:repoId/chat — AI chat with 5 modes
│
├── hooks/                               # Custom React hooks
│   ├── useAnalysis.js                   # Fetch and manage analysis state
│   ├── useSSE.js                        # Subscribe to SSE stream for live progress
│   └── useAuth.js                       # Auth state helpers
│
├── services/                            # Axios API call functions
│   ├── api.js                           # Axios instance with base URL and auth interceptor
│   ├── authService.js                   # login, logout, refreshToken
│   ├── repoService.js                   # submitRepo, getRepo
│   ├── analysisService.js               # startAnalysis, getAnalysis, getFileDeepDive
│   └── chatService.js                   # createSession, sendMessage
│
├── store/                               # Zustand global state
│   ├── authStore.js                     # currentUser, accessToken, isLoggedIn, login(), logout()
│   └── analysisStore.js                 # currentAnalysis, currentRepo, setAnalysis()
│
├── types/                               # JSDoc type definitions (since we use JS not TS)
│   └── index.js                         # @typedef for User, Repo, Analysis, FileNode, ChatMessage
│
└── utils/
    ├── formatters.js                    # Date formatting, file size formatting
    └── constants.js                     # API base URL, route constants, chat mode constants
```

### React Router Routes

| Route | Screen | Purpose |
|---|---|---|
| `/` | LandingScreen | Paste GitHub URL, start analysis |
| `/analyze/:repoId/loading` | LoadingScreen | Live SSE progress of 7-stage analysis |
| `/analyze/:repoId/overview` | OverviewScreen | Project summary, tech stack, architecture |
| `/analyze/:repoId/explorer` | ExplorerScreen | Annotated file tree, file deep dive |
| `/analyze/:repoId/chat` | ChatScreen | AI chat with 5 modes |

---

## 8. Database

### Design Principles
- Every table uses UUID primary keys (not auto-increment integers) — safe for distributed systems, no sequential ID guessing
- All timestamps use TIMESTAMPTZ (timezone-aware) not TIMESTAMP
- Flyway owns all schema changes — Hibernate is set to `validate` only
- Foreign keys with ON DELETE CASCADE — deleting a repo deletes all its file_nodes automatically
- Indexes on every foreign key column and every column used in WHERE clauses

### All 13 Tables

---

**flyway_schema_history** — Auto-created by Flyway
Records every migration that has run. Contains version, description, script name, checksum, execution time, success flag. Flyway reads this on every startup to decide which migrations to skip. Never manually modify this table — Flyway uses checksums to detect tampering.

---

**users** — V1__create_users.sql

```sql
CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    github_id     BIGINT UNIQUE NOT NULL,
    email         VARCHAR(255),
    username      VARCHAR(100) NOT NULL,
    avatar_url    TEXT,
    github_token  TEXT,
    plan          VARCHAR(20) NOT NULL DEFAULT 'FREE',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

| Column | Purpose |
|---|---|
| id | UUID primary key — auto generated |
| github_id | GitHub's own numeric user ID — stable even if username changes |
| email | May be null — GitHub users can hide their email |
| username | GitHub login name e.g. "johndoe" |
| avatar_url | GitHub profile picture URL |
| github_token | OAuth access token stored ENCRYPTED — used to fetch private repos |
| plan | FREE, PRO, or ENTERPRISE — for future paid tiers |

---

**refresh_tokens** — V2__create_refresh_tokens.sql

```sql
CREATE TABLE refresh_tokens (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash    VARCHAR(64) UNIQUE NOT NULL,
    expires_at    TIMESTAMPTZ NOT NULL,
    revoked       BOOLEAN NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

| Column | Purpose |
|---|---|
| user_id | Foreign key to users — cascade delete removes tokens when user deleted |
| token_hash | SHA-256 hash of raw token — NEVER store raw tokens in DB |
| expires_at | 7 day expiry |
| revoked | Set true on logout — keeps audit trail |

Why hash tokens: If database is compromised, attacker cannot use hashed tokens to authenticate — they need the original raw token.

---

**repos** — V3__create_repos.sql

```sql
CREATE TABLE repos (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    url             TEXT NOT NULL,
    name            VARCHAR(255) NOT NULL,
    owner           VARCHAR(255) NOT NULL,
    provider        VARCHAR(20) NOT NULL DEFAULT 'GITHUB',
    is_private      BOOLEAN NOT NULL DEFAULT FALSE,
    default_branch  VARCHAR(100) DEFAULT 'main',
    file_count      INT DEFAULT 0,
    size_kb         BIGINT DEFAULT 0,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    error_msg       TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

| Column | Purpose |
|---|---|
| url | Full URL as user pasted it |
| name / owner | Parsed from URL — owner=facebook, name=react |
| provider | GITHUB — designed to support GitLab and Bitbucket later |
| status | PENDING → INGESTING → READY or FAILED |
| error_msg | If FAILED, why it failed |

---

**file_nodes** — V4__create_file_nodes.sql

```sql
CREATE TABLE file_nodes (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    repo_id         UUID NOT NULL REFERENCES repos(id) ON DELETE CASCADE,
    path            TEXT NOT NULL,
    name            VARCHAR(255) NOT NULL,
    type            VARCHAR(10) NOT NULL,
    depth           INT NOT NULL DEFAULT 0,
    size_bytes      BIGINT DEFAULT 0,
    language        VARCHAR(50),
    role_summary    TEXT,
    embedding_id    VARCHAR(100),
    is_in_scope     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(repo_id, path)
);
```

Most important table. One row per file and folder in the repo.

| Column | Purpose |
|---|---|
| path | Full path: "src/main/java/com/example/App.java" |
| type | FILE or DIRECTORY |
| depth | 0 = root level, 1 = one level deep etc |
| language | Detected: Java, TypeScript, Python, YAML etc |
| role_summary | AI fills this in Stage 6: "Entry point, bootstraps Spring context" |
| embedding_id | Qdrant point ID — filled in Stage 7 |
| is_in_scope | User can exclude folders — false means excluded from analysis |

---

**file_node_dependencies** — V5__create_file_node_dependencies.sql

```sql
CREATE TABLE file_node_dependencies (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_file_id      UUID NOT NULL REFERENCES file_nodes(id) ON DELETE CASCADE,
    target_file_id      UUID NOT NULL REFERENCES file_nodes(id) ON DELETE CASCADE,
    relationship_type   VARCHAR(20) NOT NULL,
    UNIQUE(source_file_id, target_file_id, relationship_type)
);
```

Powers the Impact Analysis chat mode. If you change FileA, find everything that depends on FileA.

| relationship_type | Meaning |
|---|---|
| IMPORTS | file imports/requires target |
| EXTENDS | class extends target class |
| IMPLEMENTS | class implements target interface |
| CALLS | file calls a function in target |
| USES | general usage relationship |

---

**analyses** — V6__create_analyses.sql

```sql
CREATE TABLE analyses (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    repo_id         UUID NOT NULL REFERENCES repos(id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    ai_provider     VARCHAR(20) NOT NULL DEFAULT 'CLAUDE',
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    current_stage   INT NOT NULL DEFAULT 0,
    scope_file_ids  UUID[],
    result          JSONB,
    error_msg       TEXT,
    tokens_used     INT DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMPTZ
);
```

One row per analysis run. User can re-analyze same repo multiple times.

| Column | Purpose |
|---|---|
| ai_provider | CLAUDE or OPENAI — which AI ran this |
| status | PENDING → RUNNING → COMPLETED or FAILED |
| current_stage | 0-7 — which stage is running — shown on loading screen |
| scope_file_ids | PostgreSQL UUID array — files user chose to include |
| result | JSONB — final merged output of all 7 stages |
| tokens_used | Total AI tokens across all stages — for cost tracking |

---

**analysis_stages** — V7__create_analysis_stages.sql

```sql
CREATE TABLE analysis_stages (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    analysis_id     UUID NOT NULL REFERENCES analyses(id) ON DELETE CASCADE,
    stage_number    INT NOT NULL,
    stage_name      VARCHAR(50) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    result          JSONB,
    tokens_used     INT DEFAULT 0,
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    UNIQUE(analysis_id, stage_number)
);
```

7 rows per analysis. Allows resuming from failed stage instead of restarting from Stage 1.

| stage_number | stage_name | What it does |
|---|---|---|
| 1 | OVERVIEW | Reads README + pom.xml/package.json → project summary + tech stack |
| 2 | ARCHITECTURE | Reads entry points + routers → architecture + design patterns |
| 3 | MODULE_MAPPING | Reads controllers + services → maps key modules |
| 4 | DATA_FLOW | Reads route handlers + middleware → request lifecycle |
| 5 | BUG_DETECTION | Reads all file summaries → identifies risk areas |
| 6 | FILE_ANNOTATION | Sends 20 files per AI call → one-line role_summary for every file |
| 7 | EMBEDDING | Embeds every file chunk → stores vectors in Qdrant |

---

**chat_sessions** — V8__create_chat_sessions.sql

```sql
CREATE TABLE chat_sessions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    analysis_id     UUID NOT NULL REFERENCES analyses(id) ON DELETE CASCADE,
    title           VARCHAR(255),
    mode            VARCHAR(30) NOT NULL DEFAULT 'SUBSYSTEM_EXPLAINER',
    message_count   INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_active     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

One conversation thread. A user can have multiple sessions per analysis in different modes.

---

**chat_messages** — V9__create_chat_messages.sql

```sql
CREATE TABLE chat_messages (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id      UUID NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    role            VARCHAR(10) NOT NULL,
    content         TEXT NOT NULL,
    tokens_used     INT DEFAULT 0,
    context_chunks  JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

| Column | Purpose |
|---|---|
| role | USER or ASSISTANT |
| content | Full message text |
| context_chunks | JSON array of Qdrant results used as context — stored for debugging. Shows exactly which files the AI was given |

---

**prompt_templates** — V10__create_ai_gateway_tables.sql

```sql
CREATE TABLE prompt_templates (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(100) UNIQUE NOT NULL,
    system_prompt   TEXT NOT NULL,
    user_prompt     TEXT NOT NULL,
    provider        VARCHAR(20) NOT NULL DEFAULT 'CLAUDE',
    version         INT NOT NULL DEFAULT 1,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

Prompts in DB not in code — improves prompts without redeploying.

---

**ai_call_logs** — V10__create_ai_gateway_tables.sql

```sql
CREATE TABLE ai_call_logs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID REFERENCES users(id) ON DELETE SET NULL,
    analysis_id     UUID REFERENCES analyses(id) ON DELETE SET NULL,
    session_id      UUID REFERENCES chat_sessions(id) ON DELETE SET NULL,
    provider        VARCHAR(20) NOT NULL,
    model           VARCHAR(50) NOT NULL,
    input_tokens    INT DEFAULT 0,
    output_tokens   INT DEFAULT 0,
    duration_ms     BIGINT DEFAULT 0,
    success         BOOLEAN NOT NULL DEFAULT TRUE,
    error_msg       TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

Every AI call logged. Answers: How much did this cost? Why did this fail? Which model is faster?

---

**share_links** — V11__create_share_links.sql

```sql
CREATE TABLE share_links (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    analysis_id     UUID NOT NULL REFERENCES analyses(id) ON DELETE CASCADE,
    created_by      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token           VARCHAR(64) UNIQUE NOT NULL,
    expires_at      TIMESTAMPTZ,
    view_count      INT NOT NULL DEFAULT 0,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

Share analysis with people who don't have accounts. URL: `/share/{token}`. Null expires_at = never expires.

---

## 9. How Core Features Work Internally

### The 7-Stage Analysis Pipeline

When a user submits a GitHub URL, here is the exact flow:

**Step 1 — Repo Ingestion**
- Backend calls GitHub API to fetch the complete file tree
- Every file and folder is saved as a `file_node` record in PostgreSQL
- Repo status set to READY

**Step 2 — Scope Selection**
- User sees the file tree and can deselect folders to exclude from analysis
- Selected file IDs are saved as `scope_file_ids` in the analyses table

**Step 3 — Pipeline Start**
- Backend publishes a message to Kafka topic `repo-analysis-jobs`
- Immediately responds to browser with analysis ID
- Browser navigates to loading screen and opens SSE connection

**Step 4 — 7 Stages Run Asynchronously**

Each stage:
1. Sets its status to RUNNING in `analysis_stages`
2. Fetches relevant file content from GitHub API or DB
3. Calls AI (Claude primary, OpenAI fallback)
4. Saves result as JSONB in `analysis_stages.result`
5. Sets status to COMPLETED
6. Publishes SSE event to browser with progress update

**Stage 6 special detail:** Sends 20 files per AI call (batching) to stay within token limits. Saves role_summary to each `file_node` record.

**Stage 7 special detail:** 
- Each file is chunked (split into overlapping pieces for large files)
- Each chunk is sent to embedding API (converts text to vector)
- Vector stored in Qdrant with file metadata as payload
- `embedding_id` saved to `file_node` record

**Step 5 — Pipeline Complete**
- All stage results merged into final JSON
- Saved to `analyses.result`
- Status set to COMPLETED
- SSE event sent to close loading screen
- Browser navigates to overview screen

---

### The RAG Chat System

RAG = Retrieval Augmented Generation. Every AI chat query works like this:

**Step 1** — User types a question in the chat

**Step 2** — Backend embeds the question
- Question text is sent to embedding API
- Returns a vector (list of numbers representing meaning)

**Step 3** — Qdrant semantic search
- Backend sends question vector to Qdrant
- Qdrant finds top-K most similar file chunk vectors from that repo
- Returns the actual file content chunks

**Step 4** — Context assembly
- Retrieved chunks are assembled into a structured prompt
- Each chunk includes: file path, content, similarity score

**Step 5** — AI call with context
- System prompt sets the chat mode (Bug Search, Enhancement Guide, etc.)
- Retrieved chunks are injected as context
- User's question is included
- AI generates answer with precise file references

**Step 6** — Streaming response
- AI response streams back token by token via SSE
- User sees words appearing in real time
- Feels like ChatGPT

**Step 7** — Message saved
- Both user message and AI response saved to `chat_messages`
- `context_chunks` field records which files were used as context

---

## 10. Project Folder Structure on Disk

```
D:\Projects\RepoMind\
│
├── backend\
│   ├── src\
│   │   ├── main\
│   │   │   ├── java\com\repomind\backend\
│   │   │   │   ├── BackendApplication.java
│   │   │   │   ├── HealthController.java
│   │   │   │   └── SecurityConfig.java
│   │   │   └── resources\
│   │   │       ├── application.yml
│   │   │       └── db\migration\
│   │   │           ├── V1__create_users.sql
│   │   │           ├── V2__create_refresh_tokens.sql
│   │   │           ├── V3__create_repos.sql
│   │   │           ├── V4__create_file_nodes.sql
│   │   │           ├── V5__create_file_node_dependencies.sql
│   │   │           ├── V6__create_analyses.sql
│   │   │           ├── V7__create_analysis_stages.sql
│   │   │           ├── V8__create_chat_sessions.sql
│   │   │           ├── V9__create_chat_messages.sql
│   │   │           ├── V10__create_ai_gateway_tables.sql
│   │   │           └── V11__create_share_links.sql
│   │   └── test\java\com\repomind\backend\
│   │       └── BackendApplicationTests.java
│   ├── pom.xml
│   └── Dockerfile
│
├── frontend\
│   ├── src\
│   │   ├── App.jsx
│   │   ├── main.jsx
│   │   └── assets\
│   ├── public\
│   ├── index.html
│   ├── package.json
│   ├── vite.config.js
│   └── Dockerfile
│
├── docker-compose.yml
├── .env
└── ProjectReadme.md
```

---

## 11. How Docker Works in This Project

### Core Concepts

**Image vs Container**
A Docker image is a read-only blueprint — like a recipe. A container is a running instance of that image — like a baked cake. You can run many containers from one image.

**Layer Caching**
Every instruction in a Dockerfile creates a layer. Docker caches each layer. On rebuild it only re-runs layers that changed. This is why we copy `pom.xml` before source code — dependencies change rarely, so that layer is almost always cached.

**Docker Compose**
Defines all services in one YAML file. One command to start everything. One command to stop everything.

**Docker Network**
All 10 containers share `repomind-network`. On this network containers find each other by service name — Docker handles DNS automatically. The backend reaches PostgreSQL at hostname `postgres`, not `localhost`.

**Port Mapping**
Format: `HOST_PORT:CONTAINER_PORT`
- `5432:5432` → Your Windows machine port 5432 → PostgreSQL container port 5432
- `5050:80` → Your Windows machine port 5050 → PgAdmin container port 80

**Volumes**
Persist data on your Windows machine even when containers are stopped or deleted. PostgreSQL data survives restarts because of named volumes.

**Health Checks**
Docker periodically runs a command inside the container to check if it is healthy. Other containers can wait for a service to be healthy before starting — prevents startup race conditions.

**env_file**
Docker Compose reads `.env` file and injects every variable as an environment variable into the container. Spring Boot reads them with `${VARIABLE_NAME}` syntax.

---

### Why Kafka has Two Listener Addresses

```yaml
KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092,PLAINTEXT_INTERNAL://kafka:29092
```

- `localhost:9092` — For connections from your Windows machine (developer tools, testing)
- `kafka:29092` — For connections from other containers on Docker network (backend uses this)

That is why `.env` has `KAFKA_BOOTSTRAP_SERVERS=kafka:29092` not `localhost:9092`.

---

## 12. All Running Services

| Service | Container | URL | Port | Credentials |
|---|---|---|---|---|
| Spring Boot Backend | repomind-backend | http://localhost:8080 | 8080 | none |
| React Frontend | repomind-frontend | http://localhost:5173 | 5173 | none |
| PostgreSQL | repomind-postgres | localhost:5432 | 5432 | user: `repomind` pass: `repomind123` db: `repomind` |
| Redis | repomind-redis | localhost:6379 | 6379 | pass: `redis123` |
| Qdrant | repomind-qdrant | http://localhost:6333 | 6333 | none |
| Qdrant Dashboard | repomind-qdrant | http://localhost:6333/dashboard | 6333 | none |
| Kafka | repomind-kafka | localhost:9092 | 9092 | none |
| Zookeeper | repomind-zookeeper | localhost:2181 | 2181 | none — internal only |
| MinIO Console | repomind-minio | http://localhost:9001 | 9001 | user: `minioadmin` pass: `minioadmin123` |
| MinIO API | repomind-minio | http://localhost:9000 | 9000 | same as above |
| PgAdmin | repomind-pgadmin | http://localhost:5050 | 5050 | email: `admin@repomind.com` pass: `admin123` |
| Kafka UI | repomind-kafka-ui | http://localhost:8090 | 8090 | none |
| JVM Debugger | repomind-backend | localhost:5005 | 5005 | attach IDE debugger |

---

## 13. Dashboard Login Guide

### PgAdmin — http://localhost:5050

**Login:**
- Email: `admin@repomind.com`
- Password: `admin123`

**Connect to database:**
- Right click Servers → Register → Server
- General tab → Name: `RepoMind`
- Connection tab:
  - Host: `postgres`
  - Port: `5432`
  - Database: `repomind`
  - Username: `repomind`
  - Password: `repomind123`
  - Save password: ON
- Click Save

**Browse tables:**
RepoMind → Databases → repomind → Schemas → public → Tables

**View table data:**
Right click table → View/Edit Data → All Rows

**Run SQL:**
Tools → Query Tool → type SQL → press ▶

---

### Kafka UI — http://localhost:8090
No login required. Shows brokers, topics, consumers. Currently no topics — created when pipeline is built.

---

### MinIO Console — http://localhost:9001
- Username: `minioadmin`
- Password: `minioadmin123`

Create buckets, upload files, set policies. Bucket `repomind-repos` will be created when upload feature is built.

---

### Qdrant Dashboard — http://localhost:6333/dashboard
No login required. Shows collections, points, search. Currently empty — collections created when embedding pipeline is built.

---

## 14. Every File — What It Is and Why We Created It

### Generated by Spring Initializer (start.spring.io)

**`backend/pom.xml`**
Maven project descriptor. Like package.json for Java. Lists all dependencies, Java version, build plugins. Generated by selecting dependencies on Spring Initializer website.

**`backend/src/main/java/com/repomind/backend/BackendApplication.java`**
The main class. Contains `public static void main()`. `@SpringBootApplication` = `@Configuration` + `@EnableAutoConfiguration` + `@ComponentScan` combined. Tells Spring to scan all classes in this package and auto-configure everything. Also has `@EnableAsync` to enable `@Async` annotation on service methods — required for the async analysis pipeline.

**`backend/src/test/.../BackendApplicationTests.java`**
Basic smoke test — just checks the Spring context loads without errors. Auto-generated.

---

### Generated by Vite (npm create vite@latest frontend -- --template react)

**`frontend/src/App.jsx`**
Root React component. Currently shows default Vite template. Will be replaced with React Router setup and RepoMind screens.

**`frontend/src/main.jsx`**
JavaScript entry point. Imports React and App, renders into `<div id="root">` in index.html. Bridge between HTML and React.

**`frontend/index.html`**
The one and only HTML page. Everything renders inside `<div id="root"></div>`. Vite injects compiled JS bundle here at build time.

**`frontend/package.json`**
Node dependencies and scripts. Generated by Vite. We added `axios`, `react-router-dom`, `zustand` by running `npm install axios react-router-dom zustand`.

---

### Created Manually

**`backend/Dockerfile`**

Why: Spring Initializer never generates Docker files.

```dockerfile
FROM maven:3.9-eclipse-temurin-21
```
Start from official Maven + Java 21 image. No need to install Java ourselves.

```dockerfile
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
```
Copy pom.xml FIRST. Download all dependencies as a separate cached layer. `-B` = batch mode. This layer is skipped on rebuild if pom.xml unchanged — saves 2-3 minutes.

```dockerfile
COPY src ./src
```
Copy source code AFTER dependencies. Changing Java files does not break the dependency cache.

```dockerfile
EXPOSE 8080 5005
CMD ["mvn", "spring-boot:run", "-Dspring-boot.run.jvmArguments=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"]
```
Start app with remote debug enabled on port 5005. Lets you attach IDE debugger to the running container.

---

**`frontend/Dockerfile`**

```dockerfile
FROM node:20-alpine
WORKDIR /app
COPY package.json .
RUN npm install
COPY . .
EXPOSE 5173
CMD ["npm", "run", "dev", "--", "--host"]
```

`node:20-alpine` — Alpine Linux keeps image tiny (~5MB base).
`--host` flag — Critical. Without it Vite only listens on 127.0.0.1 inside container. Docker cannot forward traffic to 127.0.0.1 — needs 0.0.0.0.

---

**`frontend/vite.config.js`** — Modified from generated

Original problem: `docker run -p 3000:5173` → browser said "site can't be reached".

Root cause: Vite bound to 127.0.0.1 not 0.0.0.0.

Discovery: Tried `docker run -p 5173:5173` — worked. Confirmed Vite was on port 5173 but not accessible.

Fix:
```javascript
server: {
    host: '0.0.0.0',  // all interfaces — required for Docker
    port: 5173,        // explicit port
}
```

Lesson: Always set `host: '0.0.0.0'` for any dev server in Docker.

---

**`backend/src/main/resources/application.yml`** — Replaced application.properties

Why replaced: Spring Initializer generates flat `application.properties`. YAML is more readable for nested config.

Key decisions:

`context-path: /api` — All endpoints under /api prefix. Clean Nginx proxy rule in production.

`ddl-auto: validate` — Hibernate checks schema but NEVER creates or alters tables. Flyway owns the schema. If entity does not match table, app fails fast on startup — better than silent data corruption.

`flyway.locations: classpath:db/migration` — Flyway looks here for SQL files.

`${VARIABLE:default}` syntax — All secrets from env vars. Same config file works in Docker (vars from .env) and locally (uses defaults).

---

**`backend/src/main/java/com/repomind/backend/HealthController.java`**

Why: Spring Initializer does not generate any controllers.

```java
@RestController
@RequestMapping("/health")
public class HealthController {
    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "service", "repomind-backend",
            "timestamp", Instant.now().toString()
        ));
    }
}
```

Returns `{"status":"UP","service":"repomind-backend","timestamp":"..."}`. Used by Docker health checks, load balancers, and manual verification.

---

**`backend/src/main/java/com/repomind/backend/SecurityConfig.java`**

Why: Default Spring Security was broken — everything redirected to GitHub login.

Problem: Including `spring-boot-starter-oauth2-client` in pom.xml triggered Spring Boot auto-configuration to enable GitHub OAuth2 login and protect ALL endpoints. First visit to `/api/health` redirected to GitHub.

Fix:
```java
.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
.oauth2Login(oauth2 -> oauth2.disable())
```

Current state: Fully permissive. All endpoints open. Intentional for scaffolding stage.

Future state: Will be rewritten with JWT filter, OAuth2 handler, protected endpoints.

---

**`docker-compose.yml`**

Defines all 10 services. Key decisions:

Health checks on postgres and redis — backend `depends_on` with `condition: service_healthy`. Prevents backend starting before DB is ready.

Two Kafka listeners — `localhost:9092` for host machine, `kafka:29092` for Docker network.

Named volumes — persist data across container restarts.

`env_file: .env` — inject all secrets into backend container.

---

**`.env`**

All secrets outside of code. Never committed to Git. Chain: `.env` → Docker injects as environment variables → Spring Boot reads with `${VAR}`.

---

**Flyway Migration Files V1–V11**

Why separate files: Each change is versioned and trackable. Failed migration at V7 means V1-V6 already done. Add V12 later to ALTER a column without touching existing files.

Naming: `V{number}__{description}.sql` — double underscore is required by Flyway.

Never edit committed migrations: Flyway stores checksum of each file. Edit causes checksum mismatch → app refuses to start → protects production data.

---

## 15. Problems We Hit and How We Solved Them

### Problem 1 — Brace expansion not working in shell
**Symptom:** `mkdir -p backend/{api,config}` created a folder literally named `{api,config}`.
**Cause:** Shell did not support brace expansion.
**Fix:** Created each directory with a separate explicit `mkdir` command.

### Problem 2 — Backend container crashing immediately
**Symptom:** All other containers started but `repomind-backend` was missing from `docker ps`.
**Cause:** `application.yml` did not exist — Spring Initializer generates `application.properties` not `application.yml`. Backend could not find database URL and crashed.
**Fix:** Deleted `application.properties`, created `application.yml` with full configuration.

### Problem 3 — /api/health redirecting to GitHub
**Symptom:** Opening `http://localhost:8080/api/health` in browser redirected to GitHub OAuth login page.
**Cause:** `spring-boot-starter-oauth2-client` dependency caused Spring Boot to auto-configure GitHub OAuth2 and protect all endpoints.
**Fix:** Created `SecurityConfig.java` with `permitAll()` and `oauth2Login.disable()`.

### Problem 4 — Frontend not accessible at localhost:3000
**Symptom:** `docker run -p 3000:5173 repomind-frontend` ran but browser showed "site can't be reached".
**Cause:** Vite dev server bound to `127.0.0.1` inside container. Docker port forwarding needs `0.0.0.0`.
**Fix 1:** Added `host: '0.0.0.0'` to vite.config.js.
**Fix 2:** Changed port mapping to `5173:5173` so host and container ports match.

### Problem 5 — Flyway ran but only created flyway_schema_history
**Symptom:** PgAdmin showed only 1 table instead of 13.
**Cause:** The 11 migration SQL files existed in the container I was working in but were never placed in the actual project folder `D:\Projects\RepoMind\backend\src\main\resources\db\migration\`.
**Fix:** Created all 11 SQL migration files manually in the correct project folder, rebuilt backend.

### Problem 6 — TLS timeout pulling Qdrant image
**Symptom:** `docker-compose up` failed with TLS handshake timeout on Qdrant image pull.
**Cause:** Network dropped during large image download.
**Fix:** Ran `docker pull qdrant/qdrant:v1.11.0` separately to download, then reran compose.

### Problem 7 — curl opening GitHub in browser on Windows
**Symptom:** Running `curl http://localhost:8080/api/health` opened GitHub website.
**Cause:** On Windows, `curl` is aliased to `Invoke-WebRequest` in PowerShell, and the URL without quotes triggered something else.
**Fix:** Used browser directly to open `http://localhost:8080/api/health`.

---

## 16. How to Start and Stop Everything

### First time or after code changes:
```bash
cd D:\Projects\RepoMind
docker-compose up --build -d
```

### Start without rebuilding:
```bash
docker-compose up -d
```

### Check all containers:
```bash
docker-compose ps
```

### View logs:
```bash
docker logs repomind-backend
docker logs repomind-postgres
docker logs repomind-frontend
docker logs repomind-kafka
```

### Follow logs live:
```bash
docker logs -f repomind-backend
```

### Rebuild only backend:
```bash
docker-compose up --build -d backend
```

### Stop everything (keep data):
```bash
docker-compose down
```

### Stop and delete all data:
```bash
docker-compose down -v
```
⚠️ Deletes all volumes — all PostgreSQL data, Redis cache, Qdrant vectors, MinIO files permanently gone.

### Terminal inside container:
```bash
docker exec -it repomind-backend bash
docker exec -it repomind-postgres psql -U repomind -d repomind
```

---

## 17. Current Status of the Code

### ✅ Fully working

| What | Detail |
|---|---|
| All 10 Docker containers | Running and verified |
| PostgreSQL | Healthy, accepting connections |
| Redis | Healthy, password protected |
| Qdrant | Running, dashboard accessible |
| Kafka + Zookeeper | Running |
| MinIO | Running, console accessible |
| PgAdmin | Running, connected to postgres |
| Kafka UI | Running, showing broker |
| All 11 database tables | Created by Flyway, verified in PgAdmin |
| GET /api/health | Returns `{"status":"UP"}` |
| Frontend | Loads at http://localhost:5173 |

### ❌ Not implemented yet

| What | Notes |
|---|---|
| RepoMind UI | Frontend shows default Vite template |
| React Router | No routes configured yet |
| GitHub OAuth2 login | Dependency present, not wired |
| JWT authentication | Not implemented |
| JPA entities | No User.java, Repo.java etc. |
| Any API endpoints | Only /api/health exists |
| Repo ingestion | GitHub API client not built |
| 7-stage analysis pipeline | Not started |
| Kafka topics/producers/consumers | Not created |
| AI integration | API keys empty in .env |
| RAG chat system | Not started |
| Qdrant collections | Empty |
| MinIO buckets | Not created |
| JWT RSA key pair | Not generated |

---

## 18. Complete Planned Implementation Roadmap

### Phase 1 — Frontend Skeleton (Next)
- Set up React Router with all 5 routes
- Create skeleton screen components (Landing, Loading, Overview, Explorer, Chat)
- Configure Vite proxy — `/api/` calls proxy to `http://localhost:8080`
- Build Landing page UI — URL input, submit button

### Phase 2 — Authentication
- Generate RSA-2048 key pair for JWT signing
- Create GitHub OAuth2 App (requires GitHub account settings)
- Fill GITHUB_CLIENT_ID and GITHUB_CLIENT_SECRET in .env
- Implement OAuth2SuccessHandler — issues JWT after GitHub login
- Implement JwtAuthFilter — validates token on every request
- Implement JwtService — generate and validate RS256 tokens
- Implement RefreshTokenService — issue, rotate, revoke
- Update SecurityConfig with real auth rules
- Create User JPA entity and UserRepository
- Frontend: login button, store token in Zustand, attach to Axios headers

### Phase 3 — JPA Entities
- Create Java entity classes for all 11 tables
- User, RefreshToken, Repo, FileNode, FileDependency
- Analysis, AnalysisStage, ChatSession, ChatMessage
- PromptTemplate, AiCallLog, ShareLink
- All repositories

### Phase 4 — Repo Ingestion
- GitHubApiClient — fetch file tree using GitHub Trees API
- Parse response, build FileNode records
- Save all file_nodes to database
- Create RepoController — POST /api/repos
- Handle private repos using user's github_token

### Phase 5 — Analysis Pipeline
- Create Kafka topic `repo-analysis-jobs`
- AnalysisPipelineService — Kafka consumer, orchestrates 7 stages
- Implement all 7 stage classes
- SSE endpoint — GET /api/analyses/{id}/stream
- Frontend LoadingScreen subscribes to SSE stream

### Phase 6 — AI Integration
- AiGatewayService — single entry point for all AI calls
- ClaudeProvider — Anthropic API integration
- OpenAiProvider — OpenAI API integration (fallback)
- PromptTemplateService — load from DB, fill placeholders
- AiCallLogService + AiCallAuditAspect — auto-log all calls
- Fill ANTHROPIC_API_KEY and OPENAI_API_KEY in .env

### Phase 7 — RAG Chat System
- EmbeddingStage — chunk files, embed, store in Qdrant
- QdrantClient — HTTP client for Qdrant API
- RagService — embed query, search Qdrant, return top-K chunks
- ContextAssembler — format chunks into AI prompt
- ChatService — manage sessions and messages
- ChatController — POST /api/chat/sessions/{id}/messages
- Streaming SSE response from AI
- Frontend ChatScreen with 5 mode switcher

### Phase 8 — Production Setup
- Dockerfile.backend — multi-stage: Maven build → slim JRE runtime, non-root user
- Dockerfile.frontend — multi-stage: Vite build → Nginx serving /dist
- Nginx config — serve SPA with try_files, proxy /api/ with SSE support (proxy_buffering off)
- docker-compose.prod.yml — no dev tools, proper restart policies
- Environment variable documentation for production deployment

---

## 19. All Dependencies Reference

### Backend — pom.xml

| Dependency | What it does in RepoMind |
|---|---|
| spring-boot-starter-web | @RestController, @GetMapping, embedded Tomcat on port 8080 |
| spring-boot-starter-webflux | WebClient for async HTTP calls to GitHub API and AI providers |
| spring-boot-starter-security | SecurityFilterChain, authentication filters, endpoint protection |
| spring-boot-starter-oauth2-client | GitHub OAuth2 login — authorization code flow, token exchange |
| spring-boot-starter-oauth2-resource-server | Validate JWT bearer tokens on every incoming request |
| spring-boot-starter-data-jpa | JpaRepository, @Entity, EntityManager for PostgreSQL |
| postgresql | JDBC driver — actual TCP connector between Java and PostgreSQL |
| flyway-core | Runs SQL migration files on startup in version order |
| flyway-database-postgresql | PostgreSQL-specific Flyway support — required since Flyway 10 |
| spring-boot-starter-data-redis | RedisTemplate, @Cacheable for Redis operations |
| spring-kafka | KafkaTemplate.send() for producing, @KafkaListener for consuming |
| spring-boot-starter-validation | @Valid on controllers, @NotNull @Size on request DTOs |
| spring-boot-starter-actuator | /actuator/health and /actuator/metrics out of the box |
| spring-boot-starter-aop | @Aspect, @Around for execution time logging and AI call auditing |
| lombok | @Data, @Builder, @Slf4j — eliminates boilerplate code |
| jackson-datatype-jsr310 | Serializes Java LocalDateTime and Instant as ISO 8601 strings |
| spring-boot-devtools | Watches for file changes, auto-restarts app in dev mode |

### Frontend — package.json

| Dependency | What it does in RepoMind |
|---|---|
| react | Core library — useState, useEffect, component model |
| react-dom | Renders React tree to browser DOM |
| vite | Dev server with HMR, production build tool |
| @vitejs/plugin-react | JSX transformation and React Fast Refresh |
| react-router-dom | BrowserRouter, Routes, Route, useNavigate, useParams |
| axios | HTTP requests to backend, auth header interceptor, error handling |
| zustand | Global store — logged in user, current analysis, UI state |

---

## 20. What You Need to Learn at This Stage

To understand everything we have done so far, learn these topics in this order:

### 1. Docker (most important)
- Images vs containers
- Dockerfile line by line
- Layer caching and why pom.xml is copied before source
- Docker Compose — services, networks, volumes, health checks
- Port mapping HOST:CONTAINER
- How containers find each other by name on a network
- env_file and environment variables

### 2. PostgreSQL and SQL
- Tables, rows, columns, data types
- Primary key and foreign key
- UUID vs integer primary keys
- ON DELETE CASCADE
- Indexes and why they matter
- JSONB and UUID arrays
- Basic SQL — CREATE TABLE, SELECT, INSERT

### 3. Flyway
- What a migration tool is
- How Flyway finds and runs SQL files
- flyway_schema_history table
- Why you never edit a committed migration
- ddl-auto validate vs create

### 4. Environment Variables
- What an environment variable is
- Why no hardcoded secrets in code
- How .env file works
- ${VARIABLE:default} syntax in application.yml
- Why .env is in .gitignore

### 5. Spring Boot Basics
- What @SpringBootApplication does
- application.yml — how Spring reads it
- @RestController, @GetMapping — enough to understand HealthController
- context-path setting
- Why SecurityConfig was needed

### 6. Maven
- What pom.xml is
- Dependencies — groupId, artifactId, version
- mvn spring-boot:run

### 7. Redis, Kafka, Qdrant, MinIO
- Just conceptual understanding — what each is and why it is in this project
- No deep implementation knowledge needed yet
- Learn each deeply when we implement the feature that uses it
