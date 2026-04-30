# RepoMind — Analysis API Complete Blueprint
### The most detailed technical specification for the 7-stage AI analysis pipeline
### Use this file as the complete instruction set to build this feature

---

## IMPORTANT — HOW TO USE THIS DOCUMENT

This is a blueprint file. You are building the RepoMind analysis API based on this specification.

**Project context:**
- Backend: Spring Boot 4.x, Java 21, Maven
- Database: PostgreSQL 16 (all tables already exist via Flyway migrations)
- Message Queue: Apache Kafka (running in Docker)
- Vector DB: Qdrant v1.11.0 (running in Docker)
- AI Primary: Anthropic Claude (claude-opus-4-5)
- AI Fallback: OpenAI GPT-4o
- Auth: Already implemented — GitHub OAuth2 + JWT. Every protected endpoint receives a validated JWT and can get the current user.
- All infrastructure is running in Docker. Connection details come from environment variables.

**What this document covers:**
Building the complete repository analysis system — from accepting a GitHub URL to producing a full AI-powered codebase understanding with 7 specialized analysis stages, stored in PostgreSQL, vectorized in Qdrant, and streamable via SSE.

**What makes this different from anything else in the market:**
- Not just a README summarizer — analyzes every single file
- Not generic AI chat — 5 specialized modes with RAG grounding
- Not a one-shot analysis — 7 stages each building on the previous
- Not static output — streams live to browser as it runs
- Not guessing — every AI answer is grounded in actual retrieved code chunks
- Dependency graph — knows which files import which other files
- Resumable pipeline — crash at stage 4, resume from stage 4

---

## Table of Contents

1. [What the Analysis API Does — Big Picture](#1-big-picture)
2. [The Complete Data Flow](#2-complete-data-flow)
3. [Phase 1 — Repository Ingestion](#3-phase-1-repository-ingestion)
4. [Phase 2 — The 7-Stage Analysis Pipeline](#4-phase-2-analysis-pipeline)
5. [Phase 3 — SSE Live Streaming](#5-phase-3-sse-streaming)
6. [All API Endpoints to Build](#6-api-endpoints)
7. [All Java Classes to Create](#7-java-classes)
8. [AI Prompt Templates — Every Prompt Written Out](#8-prompt-templates)
9. [Qdrant Integration — Embedding and Search](#9-qdrant-integration)
10. [Kafka Integration — Async Pipeline](#10-kafka-integration)
11. [Database Operations — Every Query](#11-database-operations)
12. [Error Handling and Resilience](#12-error-handling)
13. [The Unique Features That Set RepoMind Apart](#13-unique-features)
14. [Complete Implementation Order](#14-implementation-order)
15. [All Environment Variables Required](#15-environment-variables)

---

## 1. Big Picture

### What happens when a user submits a GitHub URL

The user pastes `https://github.com/facebook/react` and clicks Analyze.

What RepoMind does:

1. Fetches every file path and metadata from the GitHub API
2. Saves every file as a record in the database
3. User optionally narrows scope (exclude node_modules, test files etc)
4. A 7-stage AI pipeline runs asynchronously
5. Every stage streams progress to the browser in real time
6. Each stage builds on the previous — later stages use results of earlier stages
7. Every file gets a role summary written by AI
8. Every file gets embedded as a vector and stored in Qdrant
9. Final output is a complete JSON understanding of the codebase
10. User can then chat with their codebase using 5 specialized AI modes

### What the final output looks like

```json
{
  "overview": {
    "name": "React",
    "description": "A JavaScript library for building user interfaces...",
    "purpose": "Declarative, component-based UI development for web and native apps",
    "techStack": ["JavaScript", "TypeScript", "Flow", "Jest", "Rollup"],
    "entryPoint": "packages/react/index.js",
    "designPatterns": ["Component Pattern", "Observer Pattern", "Reconciliation Algorithm"],
    "targetAudience": "Frontend developers building interactive UIs"
  },
  "architecture": {
    "type": "Monorepo with multiple packages",
    "description": "React uses a monorepo structure with Yarn workspaces...",
    "layers": [
      { "name": "react", "role": "User-facing API surface — createElement, hooks, context" },
      { "name": "react-dom", "role": "Browser DOM renderer — reconciler implementation" },
      { "name": "react-reconciler", "role": "Core reconciliation algorithm — fiber architecture" },
      { "name": "scheduler", "role": "Cooperative scheduling — prioritizes UI updates" }
    ],
    "dataFlow": "User action → State update → Reconciler diffing → Commit phase → DOM mutation"
  },
  "modules": [
    {
      "name": "Fiber Reconciler",
      "files": ["packages/react-reconciler/src/ReactFiber.js"],
      "role": "Core algorithm that decides what changed and what DOM updates to make",
      "complexity": "HIGH"
    }
  ],
  "riskAreas": [
    {
      "file": "packages/react-reconciler/src/ReactFiberWorkLoop.js",
      "risk": "HIGH",
      "reason": "Complex concurrent mode scheduling — easy to introduce subtle priority inversion bugs"
    }
  ],
  "fileAnnotations": {
    "packages/react/index.js": "Public API surface — exports createElement, Component, hooks",
    "packages/react-dom/src/client/ReactDOM.js": "Browser entry point — createRoot, hydrateRoot"
  }
}
```

---

## 2. Complete Data Flow

```
User submits GitHub URL
        │
        ▼
POST /api/repos
  - Validate URL format
  - Extract owner and repo name
  - Check if repo already ingested for this user
  - Call GitHub API to verify repo exists and is accessible
  - Create repos record with status=PENDING
  - Return repo ID to frontend
        │
        ▼
POST /api/analyses
  - Create analyses record with status=PENDING
  - Create 7 analysis_stages records (all status=PENDING)
  - Publish message to Kafka topic: repo-analysis-jobs
  - Return analysis ID to frontend
  - Frontend opens SSE connection to GET /api/analyses/{id}/stream
        │
        ▼
Kafka Consumer picks up the job
        │
        ▼
INGESTION PHASE (before stage 1)
  - Call GitHub Trees API to get complete file tree
  - Save every file as file_node record
  - Detect language for each file
  - Mark binary/generated files as is_in_scope=false
  - Update repos.status = READY
  - Update repos.file_count and repos.size_kb
  - Send SSE event: { stage: "INGESTION", status: "COMPLETED", fileCount: 847 }
        │
        ▼
STAGE 1 — OVERVIEW
  - Fetch README.md content from GitHub API
  - Fetch package.json or pom.xml or build.gradle content
  - Fetch any additional config files (pyproject.toml, Cargo.toml etc)
  - Build prompt with these files
  - Call Claude API
  - Parse response into structured JSON
  - Save to analysis_stages[1].result
  - Send SSE event with progress
        │
        ▼
STAGE 2 — ARCHITECTURE
  - Use Stage 1 result to know the tech stack
  - Fetch entry point files identified in Stage 1
  - Fetch router files, main configuration files
  - Fetch top-level directory structure
  - Call Claude API with Stage 1 result + these files
  - Parse architecture JSON
  - Save to analysis_stages[2].result
        │
        ▼
STAGE 3 — MODULE MAPPING
  - Use Stage 2 result to understand architecture
  - Fetch controller files, service files, key module files
  - Limited to 30 most important files based on depth and name patterns
  - Call Claude API with Stage 1+2 results + these files
  - Parse module map JSON
  - Save to analysis_stages[3].result
        │
        ▼
STAGE 4 — DATA FLOW
  - Fetch route handler files, middleware files, API gateway files
  - Trace request path from entry to response
  - Call Claude API with Stage 1+2+3 results + handler files
  - Parse data flow JSON
  - Save to analysis_stages[4].result
        │
        ▼
STAGE 5 — BUG DETECTION
  - Use all previous results
  - Read all file_node records (path, language, depth, size)
  - Identify files that are large, complex, or in critical paths
  - Fetch content of top 20 most risky files
  - Call Claude API to identify risk areas and potential bugs
  - Save to analysis_stages[5].result
        │
        ▼
STAGE 6 — FILE ANNOTATION
  - For every file_node where is_in_scope=true and type=FILE
  - Batch into groups of 20 files
  - For each batch: fetch file content, call Claude
  - Claude returns one-line role_summary for each file
  - Save role_summary to each file_node record
  - This is the most token-intensive stage
  - Send SSE progress events per batch: "Annotating files 120/450..."
        │
        ▼
STAGE 7 — EMBEDDING
  - For every file_node where is_in_scope=true and type=FILE
  - Chunk each file into overlapping segments (512 tokens, 50 token overlap)
  - For each chunk: call embedding API (OpenAI text-embedding-3-small)
  - Store vector in Qdrant with payload:
    { repoId, analysisId, fileId, filePath, language, chunkIndex, chunkText }
  - Save Qdrant point ID to file_node.embedding_id
  - Send SSE progress events per file
        │
        ▼
PIPELINE COMPLETE
  - Merge all stage results into final JSON
  - Save to analyses.result
  - Set analyses.status = COMPLETED
  - Set analyses.completed_at = NOW()
  - Send SSE event: { stage: "COMPLETE", analysisId: "..." }
  - Frontend navigates to overview screen
```

---

## 3. Phase 1 — Repository Ingestion

### GitHub API Integration

**Base URL:** `https://api.github.com`

**Authentication:** Use `Authorization: token {githubToken}` header.
- If the repo is public, use the app's own GitHub personal token (from env: GITHUB_TOKEN)
- If the repo is private, use the user's own github_token from the users table (stored encrypted)

**Rate limits:**
- Unauthenticated: 60 requests per hour
- Authenticated: 5000 requests per hour
- Always authenticate — never make unauthenticated calls

---

### API Call 1 — Verify Repository

```
GET https://api.github.com/repos/{owner}/{repo}
```

Response fields we care about:
```json
{
  "id": 10270722,
  "name": "react",
  "full_name": "facebook/react",
  "private": false,
  "default_branch": "main",
  "size": 185432,
  "language": "JavaScript",
  "description": "The library for web and native user interfaces."
}
```

Use this to:
- Confirm repo exists (404 = not found, 403 = no access)
- Get the default_branch
- Get size_kb (size field is in KB)
- Update repos record with this data

---

### API Call 2 — Fetch Complete File Tree

```
GET https://api.github.com/repos/{owner}/{repo}/git/trees/{branch}?recursive=1
```

The `recursive=1` parameter returns the ENTIRE tree in one call — every file and folder at every depth.

Response:
```json
{
  "sha": "abc123",
  "truncated": false,
  "tree": [
    { "path": "src", "type": "tree", "size": 0 },
    { "path": "src/index.js", "type": "blob", "size": 1234, "sha": "def456" },
    { "path": "src/components/Button.jsx", "type": "blob", "size": 567, "sha": "ghi789" }
  ]
}
```

**IMPORTANT — Handle truncation:**
If `truncated: true`, the repo has more than 100,000 files and GitHub truncated the response. In this case, fetch each directory separately. Most repos will not hit this limit.

**What to save for each tree entry:**
- `path` → file_nodes.path
- `type` → tree = DIRECTORY, blob = FILE
- `size` → file_nodes.size_bytes
- Depth = count of `/` characters in path
- Name = last segment of path after final `/`
- Language = detect from file extension (see language detection below)

---

### Language Detection

Detect language from file extension. Map:

```java
Map<String, String> EXTENSION_TO_LANGUAGE = Map.ofEntries(
    Map.entry(".java", "Java"),
    Map.entry(".kt", "Kotlin"),
    Map.entry(".scala", "Scala"),
    Map.entry(".js", "JavaScript"),
    Map.entry(".jsx", "JavaScript"),
    Map.entry(".ts", "TypeScript"),
    Map.entry(".tsx", "TypeScript"),
    Map.entry(".py", "Python"),
    Map.entry(".rb", "Ruby"),
    Map.entry(".go", "Go"),
    Map.entry(".rs", "Rust"),
    Map.entry(".cpp", "C++"),
    Map.entry(".c", "C"),
    Map.entry(".cs", "C#"),
    Map.entry(".php", "PHP"),
    Map.entry(".swift", "Swift"),
    Map.entry(".dart", "Dart"),
    Map.entry(".yml", "YAML"),
    Map.entry(".yaml", "YAML"),
    Map.entry(".json", "JSON"),
    Map.entry(".xml", "XML"),
    Map.entry(".sql", "SQL"),
    Map.entry(".md", "Markdown"),
    Map.entry(".html", "HTML"),
    Map.entry(".css", "CSS"),
    Map.entry(".scss", "SCSS"),
    Map.entry(".sh", "Shell"),
    Map.entry(".dockerfile", "Docker"),
    Map.entry(".toml", "TOML"),
    Map.entry(".gradle", "Gradle"),
    Map.entry(".tf", "Terraform")
);
```

---

### Scope Filtering — Mark Files Out of Scope

Automatically set `is_in_scope = false` for:

```java
// Directories to always exclude
Set<String> EXCLUDED_DIRECTORIES = Set.of(
    "node_modules", ".git", ".gradle", "build", "dist", "target",
    ".next", ".nuxt", "out", "coverage", "__pycache__", ".pytest_cache",
    "vendor", "venv", ".venv", "env", ".env", "site-packages",
    ".idea", ".vscode", ".vs", "bin", "obj", "packages",
    "Pods", "DerivedData", ".dart_tool", "pubspec.lock"
);

// File patterns to always exclude
List<String> EXCLUDED_PATTERNS = List.of(
    "*.min.js",     // minified files
    "*.min.css",    // minified CSS
    "*.map",        // source maps
    "*.lock",       // lock files (package-lock.json etc)
    "*.log",        // log files
    "*.png", "*.jpg", "*.jpeg", "*.gif", "*.svg", "*.ico",  // images
    "*.woff", "*.woff2", "*.ttf", "*.eot",  // fonts
    "*.mp4", "*.mp3", "*.wav",  // media
    "*.zip", "*.tar", "*.gz",   // archives
    "*.pdf", "*.doc", "*.docx", // documents
    "*.class", "*.jar", "*.war" // compiled Java
);
```

---

### API Call 3 — Fetch File Content

When we need to read a specific file during analysis stages:

```
GET https://api.github.com/repos/{owner}/{repo}/contents/{path}?ref={branch}
```

Response:
```json
{
  "content": "base64encodedcontent...",
  "encoding": "base64",
  "size": 1234
}
```

Decode base64 to get raw file content.

**IMPORTANT — Size limit:** Do not fetch files larger than 500KB. Mark them for chunked processing instead.

**IMPORTANT — Rate limiting:** Cache fetched file contents in Redis with TTL of 1 hour. If the same file is needed in multiple stages, serve from cache. Key: `file-content:{repoId}:{filePath}`

---

### Dependency Graph Building

After ingesting file tree, build the dependency graph by scanning import statements.

For each FILE type file_node, if it is a supported language:

**JavaScript/TypeScript — detect:**
```
import X from './path'
import { X } from './path'
require('./path')
export { X } from './path'
```

**Java — detect:**
```
import com.example.ClassName;
import com.example.*;
```

**Python — detect:**
```
from module import something
import module
```

For each detected import:
1. Resolve the import path to an actual file_node in the database
2. Create a file_node_dependencies record:
   - source_file_id = the file with the import
   - target_file_id = the imported file
   - relationship_type = IMPORTS

This graph is used by Impact Analysis chat mode — when you ask "what breaks if I change X?", we traverse this graph.

---

## 4. Phase 2 — The 7-Stage Analysis Pipeline

### Pipeline Architecture

The pipeline is driven by Kafka. When an analysis is created, a message is published to `repo-analysis-jobs` topic. A Kafka consumer picks it up and runs all 7 stages sequentially.

Each stage:
1. Loads its input (previous stage results + selected files from GitHub)
2. Builds an AI prompt
3. Calls the AI provider (Claude primary, OpenAI fallback)
4. Parses the structured response
5. Saves result to analysis_stages table
6. Publishes SSE progress event
7. Hands off to next stage

**Retry logic:** If an AI call fails, retry up to 3 times with exponential backoff (2s, 4s, 8s). If all retries fail, mark stage as FAILED, mark analysis as FAILED, send SSE error event.

**Provider fallback:** If Claude returns an error or timeout, automatically switch to OpenAI for that call. Log the switch in ai_call_logs.

---

### STAGE 1 — OVERVIEW

**Purpose:** Understand what this project is, what it does, who it is for, and what technologies it uses.

**Input files to fetch:**
1. README.md (or README.rst or README.txt — try all three)
2. package.json (Node.js projects)
3. pom.xml (Maven Java projects)
4. build.gradle (Gradle Java projects)
5. pyproject.toml or setup.py (Python projects)
6. Cargo.toml (Rust projects)
7. go.mod (Go projects)
8. composer.json (PHP projects)
9. Gemfile (Ruby projects)
10. pubspec.yaml (Dart/Flutter)
11. .github/CODEOWNERS (if exists)
12. Top-level directory listing (just paths, no content)

**System Prompt:**
```
You are an expert software architect analyzing a GitHub repository.
Your job is to produce a precise, accurate project overview based on the files provided.
Be specific — mention actual library names, actual version numbers, actual framework names.
Never make assumptions — only state what is explicitly present in the provided files.
Respond ONLY with valid JSON matching the schema provided. No markdown, no explanation, just JSON.
```

**User Prompt:**
```
Analyze this repository and produce a complete overview.

Repository: {owner}/{repoName}

Files provided:
{fileContents}

Respond with this exact JSON structure:
{
  "projectName": "exact name from package.json or pom.xml or README",
  "oneLiner": "one sentence description of what this project does",
  "fullDescription": "3-5 sentence detailed description of the project purpose and what problem it solves",
  "projectType": "one of: WEB_APP | MOBILE_APP | LIBRARY | FRAMEWORK | CLI_TOOL | API_SERVICE | DATA_PIPELINE | INFRASTRUCTURE | GAME | OTHER",
  "techStack": {
    "languages": ["list of programming languages detected"],
    "frameworks": ["list of frameworks detected"],
    "buildTools": ["list of build tools"],
    "testingFrameworks": ["list of testing tools"],
    "databases": ["any database dependencies detected in config"],
    "infrastructure": ["Docker, Kubernetes, AWS etc if mentioned"]
  },
  "entryPoint": "the main entry file path relative to repo root",
  "configFiles": ["list of important config file paths found"],
  "targetAudience": "who is this project for",
  "maturityLevel": "one of: PROTOTYPE | ACTIVE_DEVELOPMENT | STABLE | MATURE | ARCHIVED",
  "hasTests": true or false,
  "hasDocumentation": true or false,
  "hasCI": true or false,
  "license": "license name or null"
}
```

**Output stored in:** `analysis_stages[1].result` as JSONB

---

### STAGE 2 — ARCHITECTURE

**Purpose:** Understand HOW the project is structured — the architecture pattern, layers, and how data moves through the system.

**Input:**
- Stage 1 result (to know the tech stack and entry point)
- Entry point file content
- Top-level directory names and structure
- Router files (routes.js, router.java, urls.py, main.go etc — detected by name patterns)
- Main configuration files (application.yml, config.py, settings.py, .env.example)
- Any architecture documents (ARCHITECTURE.md, DESIGN.md)

**System Prompt:**
```
You are a senior software architect performing codebase analysis.
You have deep knowledge of architectural patterns: MVC, Clean Architecture, Hexagonal, Microservices,
Event-Driven, CQRS, Repository Pattern, Domain-Driven Design, and more.
Based on the provided files, identify the exact architecture pattern used.
Be specific about layers, their responsibilities, and how data flows between them.
Respond ONLY with valid JSON. No markdown, no explanation.
```

**User Prompt:**
```
Analyze the architecture of this {projectType} project.

Project Overview from Stage 1:
{stage1Result}

Files provided:
{fileContents}

Respond with this exact JSON structure:
{
  "architecturePattern": "exact pattern name: MVC | MVP | MVVM | Clean Architecture | Hexagonal | Layered | Microservices | Monolith | Event-Driven | CQRS | Repository Pattern | Component-Based | Other",
  "description": "3-5 sentences describing how this architecture works in THIS specific project",
  "layers": [
    {
      "name": "layer name",
      "role": "what this layer does",
      "keyFiles": ["list of representative file paths in this layer"],
      "communicatesWith": ["names of other layers this one talks to"]
    }
  ],
  "requestLifecycle": "step by step description of how a typical request flows through the system from entry to response",
  "stateManagement": "how state is managed in this project",
  "dataAccessPattern": "how the project accesses data: ORM | Raw SQL | Repository | Active Record | etc",
  "authPattern": "how authentication works if detectable",
  "designPatterns": ["list of design patterns observed: Singleton, Factory, Observer, Strategy etc"],
  "strengths": ["architectural strengths of this codebase"],
  "concerns": ["potential architectural concerns or technical debt observed"]
}
```

---

### STAGE 3 — MODULE MAPPING

**Purpose:** Map out the key modules, components, and services — what each one does and how they connect.

**Input:**
- Stage 1 + Stage 2 results
- Controller files / route handler files
- Service files / use case files / business logic files
- Key component files
- Interface/abstract class files

**File selection strategy:**
Select files using these priority rules:
1. Files at depth 2-4 (not too shallow, not too deep)
2. Files with names containing: controller, service, handler, manager, repository, component, module, provider, factory, store
3. Index files of major directories
4. Maximum 30 files total

**System Prompt:**
```
You are analyzing the module structure of a software project.
Your job is to create a comprehensive map of every significant module, 
component, service, and utility — explaining what each does and how they connect.
Think of this as creating a map that any developer can use to navigate the codebase.
Respond ONLY with valid JSON. No markdown, no explanation.
```

**User Prompt:**
```
Map all modules and components in this project.

Previous analysis:
Overview: {stage1Result}
Architecture: {stage2Result}

Key files provided:
{fileContents}

Respond with this exact JSON structure:
{
  "modules": [
    {
      "name": "module or component name",
      "type": "CONTROLLER | SERVICE | REPOSITORY | COMPONENT | UTILITY | MIDDLEWARE | MODEL | CONFIG | TEST | OTHER",
      "filePath": "path to main file of this module",
      "responsibility": "1-2 sentence description of what this module does",
      "publicInterface": ["list of main functions/methods/endpoints this module exposes"],
      "dependencies": ["names of other modules this one depends on"],
      "complexity": "LOW | MEDIUM | HIGH",
      "isCore": true if this is a critical module, false otherwise
    }
  ],
  "moduleGroups": [
    {
      "name": "group name e.g. Authentication, Data Access, UI Components",
      "modules": ["module names in this group"],
      "description": "what this group of modules is responsible for"
    }
  ],
  "centralModules": ["names of modules that most other modules depend on — high impact if changed"],
  "isolatedModules": ["names of modules with few dependencies — safest to modify"]
}
```

---

### STAGE 4 — DATA FLOW

**Purpose:** Trace exactly how data moves through the system — from user input to database and back.

**Input:**
- All previous stage results
- Request handler files
- Middleware files
- Data transformation files
- Database query files / repository implementations
- Event files / message handler files

**System Prompt:**
```
You are tracing data flow through a software system.
Your job is to document exactly how data enters the system, how it is transformed,
validated, persisted, and returned — for the main use cases of this application.
Include every transformation, every validation, every database operation.
Respond ONLY with valid JSON. No markdown, no explanation.
```

**User Prompt:**
```
Trace the data flow for the main operations in this project.

Previous analysis:
{allPreviousStageResults}

Request handling files:
{fileContents}

Respond with this exact JSON structure:
{
  "primaryFlows": [
    {
      "name": "name of this flow e.g. User Authentication, Create Order, Fetch Dashboard",
      "trigger": "what initiates this flow: HTTP_REQUEST | EVENT | SCHEDULED | WEBSOCKET | MESSAGE",
      "steps": [
        {
          "stepNumber": 1,
          "layer": "which layer this happens in",
          "action": "what happens in this step",
          "dataIn": "what data comes in",
          "dataOut": "what data goes out",
          "filePath": "which file handles this step"
        }
      ],
      "errorPaths": ["what happens when this flow fails at each critical point"]
    }
  ],
  "dataTransformations": [
    {
      "from": "data format coming in",
      "to": "data format going out",
      "where": "file or layer where this transformation happens",
      "purpose": "why this transformation is needed"
    }
  ],
  "externalCalls": [
    {
      "service": "name of external service called",
      "purpose": "why it is called",
      "filePath": "which file makes this call",
      "isCached": true or false
    }
  ],
  "stateChanges": [
    {
      "trigger": "what causes this state change",
      "entity": "which database entity changes",
      "from": "state before",
      "to": "state after"
    }
  ]
}
```

---

### STAGE 5 — BUG DETECTION AND RISK ANALYSIS

**Purpose:** Identify the most likely locations for bugs, security vulnerabilities, performance problems, and technical debt. This is NOT about finding actual bugs — it is about identifying risk areas that deserve special attention.

**Input:**
- All previous stage results
- List of all file_nodes (path, language, size, depth) — NOT content, just metadata
- Content of files identified as HIGH risk:
  - Files larger than 200KB
  - Files at high depth (depth > 6) with many dependents
  - Files named with patterns: auth, security, payment, password, token, crypto
  - Core modules identified in Stage 3

**System Prompt:**
```
You are a senior security engineer and code quality expert reviewing a codebase for risks.
Your job is to identify the highest-risk areas — not necessarily bugs, but places where:
- Complexity makes bugs likely
- Security mistakes are easy to make
- Performance problems are likely
- The code is doing something unconventional that could confuse maintainers
Be specific about WHY each area is risky. Reference actual file names and patterns you observe.
Respond ONLY with valid JSON. No markdown, no explanation.
```

**User Prompt:**
```
Analyze this codebase for risk areas, potential bugs, and quality concerns.

Full project analysis so far:
{allPreviousStageResults}

Complete file list (path, size, language):
{fileList}

High-risk file contents:
{highRiskFileContents}

Respond with this exact JSON structure:
{
  "overallRiskLevel": "LOW | MEDIUM | HIGH | CRITICAL",
  "riskSummary": "2-3 sentence summary of the main risk profile of this codebase",
  "riskAreas": [
    {
      "filePath": "path to risky file",
      "riskLevel": "LOW | MEDIUM | HIGH | CRITICAL",
      "riskType": "SECURITY | PERFORMANCE | COMPLEXITY | MAINTAINABILITY | DATA_INTEGRITY | CONCURRENCY",
      "description": "specific description of the risk",
      "likelyCause": "why this risk exists",
      "recommendation": "what to do about it"
    }
  ],
  "securityConcerns": [
    {
      "type": "INJECTION | XSS | AUTH | AUTHORIZATION | CRYPTO | DATA_EXPOSURE | OTHER",
      "description": "specific concern",
      "filePath": "where this concern is",
      "severity": "LOW | MEDIUM | HIGH | CRITICAL"
    }
  ],
  "performanceConcerns": [
    {
      "description": "specific performance concern",
      "filePath": "where this concern is",
      "impact": "expected performance impact"
    }
  ],
  "technicalDebt": [
    {
      "description": "description of technical debt",
      "filePath": "where it is",
      "effort": "LOW | MEDIUM | HIGH to fix"
    }
  ],
  "positives": ["things this codebase does particularly well"]
}
```

---

### STAGE 6 — FILE ANNOTATION

**Purpose:** Write a one-line role summary for EVERY file in the repository. This is the most token-intensive stage.

**Strategy:** Batch files in groups of 20. For each batch, send all file paths + contents to Claude and get a role_summary for each.

**File selection:** All file_nodes where:
- type = FILE
- is_in_scope = true
- size_bytes < 500000 (skip files over 500KB)

**Batching algorithm:**
```
Sort files by depth ASC (process top-level files first)
Group into batches of 20
For each batch:
  Fetch content of each file from GitHub API (or Redis cache)
  Build prompt with all 20 files
  Call Claude
  Parse response
  Update file_node.role_summary for each file in batch
  Send SSE event: { stage: "FILE_ANNOTATION", progress: X, total: Y }
```

**System Prompt:**
```
You are annotating files in a software repository.
For each file provided, write exactly ONE sentence describing:
- What this file IS (its type/category)
- What it DOES (its specific responsibility in this project)
Be precise and specific. Reference actual class names, function names, or concepts from the code.
Never write generic descriptions like "contains utility functions" — be specific about WHICH utilities.
Respond ONLY with valid JSON. No markdown, no explanation.
```

**User Prompt:**
```
Write a one-line role summary for each of these files.

Project context: {projectName} — {oneLiner}
Architecture: {architecturePattern}

Files to annotate:
{
  files: [
    { path: "src/auth/JwtService.java", content: "..." },
    { path: "src/auth/OAuth2Handler.java", content: "..." },
    ... (up to 20 files)
  ]
}

Respond with this exact JSON structure:
{
  "annotations": [
    { "path": "src/auth/JwtService.java", "roleSummary": "Generates and validates RS256 JWT access tokens with 15-minute expiry for stateless authentication" },
    { "path": "src/auth/OAuth2Handler.java", "roleSummary": "Handles GitHub OAuth2 callback — exchanges code for token, fetches user profile, creates or updates user record" }
  ]
}
```

**After this stage:** Every file_node should have a role_summary. Update `analysis_stages[6].result` with stats: `{ totalFiles: 450, annotated: 447, skipped: 3 }`.

---

### STAGE 7 — EMBEDDING (VECTOR INDEXING)

**Purpose:** Convert every file into vectors and store in Qdrant so the AI chat can find relevant code using semantic search.

**This stage does NOT use Claude.** It uses an embedding model.

**Embedding model to use:** OpenAI `text-embedding-3-small`
- 1536 dimensions
- Cost: $0.020 per 1 million tokens — very cheap
- High quality semantic embeddings

**Chunking strategy:**

Large files need to be split into chunks because embedding models have token limits and we want granular search results.

```
For each file:
  content = fetch file content
  If content.length < 2000 characters:
    Create 1 chunk = entire file
  Else:
    Split into chunks of 1500 characters
    With 200 character overlap between chunks
    (overlap ensures no code context is lost at boundaries)
  
  For each chunk:
    Call OpenAI embeddings API
    Get vector (array of 1536 floats)
    Store in Qdrant
```

**Qdrant collection setup:**
```
Collection name: repomind-embeddings
Vector config:
  size: 1536
  distance: Cosine  (cosine similarity for semantic search)
```

**Qdrant point structure:**
```json
{
  "id": "uuid",
  "vector": [0.123, 0.456, ...],
  "payload": {
    "repoId": "uuid",
    "analysisId": "uuid",
    "fileNodeId": "uuid",
    "filePath": "src/auth/JwtService.java",
    "language": "Java",
    "chunkIndex": 0,
    "totalChunks": 3,
    "chunkText": "actual code content of this chunk",
    "roleSummary": "AI summary from Stage 6",
    "fileSize": 4521,
    "depth": 3
  }
}
```

**After storing each file's chunks:**
- Save the first chunk's Qdrant ID to `file_nodes.embedding_id`
- For files with multiple chunks, all chunks have the same fileNodeId in payload — search returns all relevant chunks

**SSE progress:** Send event after every 10 files: `{ stage: "EMBEDDING", progress: 30, total: 450, currentFile: "src/auth/JwtService.java" }`

---

## 5. Phase 3 — SSE Live Streaming

### What is SSE?

Server-Sent Events (SSE) is a technology where the server pushes data to the browser over a persistent HTTP connection. The browser opens one connection and the server keeps sending events until analysis is complete.

Unlike WebSockets, SSE is one-directional (server to browser only) and uses standard HTTP — simpler and more reliable for this use case.

### SSE Endpoint

```
GET /api/analyses/{analysisId}/stream
Authorization: Bearer {jwt}
Accept: text/event-stream
```

Response headers:
```
Content-Type: text/event-stream
Cache-Control: no-cache
Connection: keep-alive
X-Accel-Buffering: no  (tells Nginx not to buffer SSE)
```

### SSE Event Format

Each event follows this structure:
```
event: ANALYSIS_PROGRESS
data: {"stage":"INGESTION","status":"RUNNING","message":"Fetching file tree...","progress":0,"total":0}

event: ANALYSIS_PROGRESS
data: {"stage":"INGESTION","status":"COMPLETED","message":"Found 847 files","progress":847,"total":847}

event: ANALYSIS_PROGRESS
data: {"stage":"OVERVIEW","status":"RUNNING","message":"Analyzing project overview...","progress":0,"total":1}

event: ANALYSIS_PROGRESS
data: {"stage":"OVERVIEW","status":"COMPLETED","message":"Project overview complete","progress":1,"total":1}

event: ANALYSIS_PROGRESS
data: {"stage":"FILE_ANNOTATION","status":"RUNNING","message":"Annotating files 40/847","progress":40,"total":847}

event: ANALYSIS_COMPLETE
data: {"analysisId":"uuid","status":"COMPLETED","message":"Analysis complete"}

event: ANALYSIS_ERROR
data: {"stage":"ARCHITECTURE","error":"Claude API timeout after 3 retries","message":"Analysis failed"}
```

### SSE Event Types

| Event Name | When Sent | Data Fields |
|---|---|---|
| ANALYSIS_PROGRESS | Every stage start, update, and completion | stage, status, message, progress, total |
| STAGE_RESULT | After each stage completes | stage, result (the stage JSON output) |
| ANALYSIS_COMPLETE | When all 7 stages done | analysisId, status, message |
| ANALYSIS_ERROR | On unrecoverable failure | stage, error, message |
| HEARTBEAT | Every 15 seconds | timestamp (keeps connection alive) |

### Spring Boot SSE Implementation

Use `SseEmitter` from Spring MVC:

```java
@GetMapping(value = "/analyses/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter streamAnalysis(@PathVariable UUID id) {
    SseEmitter emitter = new SseEmitter(Long.MAX_VALUE); // no timeout
    // Store emitter keyed by analysisId
    // Pipeline stages will call emitter.send() as they progress
    return emitter;
}
```

Store active emitters in a `ConcurrentHashMap<UUID, SseEmitter>` in a `SseEmitterRegistry` bean. Pipeline stages inject this registry and call `registry.send(analysisId, event)`.

---

## 6. API Endpoints to Build

### Repo Endpoints

**POST /api/repos**
```
Request:
{
  "url": "https://github.com/facebook/react",
  "branch": "main"  // optional, defaults to repo's default branch
}

Response 201 Created:
{
  "id": "uuid",
  "url": "https://github.com/facebook/react",
  "owner": "facebook",
  "name": "react",
  "status": "PENDING",
  "fileCount": 0,
  "createdAt": "2026-03-21T10:00:00Z"
}

Errors:
400 - Invalid GitHub URL format
404 - Repository not found on GitHub
403 - Repository is private and user has no access
409 - Repository already ingested (return existing repo ID)
```

**GET /api/repos/{id}**
```
Response 200:
{
  "id": "uuid",
  "url": "...",
  "owner": "facebook",
  "name": "react",
  "status": "READY",
  "fileCount": 847,
  "sizeKb": 185432,
  "defaultBranch": "main",
  "createdAt": "..."
}
```

**GET /api/repos/{id}/tree**
```
Response 200:
{
  "repoId": "uuid",
  "files": [
    {
      "id": "uuid",
      "path": "src/index.js",
      "name": "index.js",
      "type": "FILE",
      "depth": 1,
      "sizeBytes": 1234,
      "language": "JavaScript",
      "isInScope": true
    }
  ]
}
```

---

### Analysis Endpoints

**POST /api/analyses**
```
Request:
{
  "repoId": "uuid",
  "scopeFileIds": ["uuid1", "uuid2"],  // optional — if empty, analyze all in-scope files
  "aiProvider": "CLAUDE"  // optional, defaults to CLAUDE
}

Response 201 Created:
{
  "id": "uuid",
  "repoId": "uuid",
  "status": "PENDING",
  "currentStage": 0,
  "createdAt": "..."
}
```

**GET /api/analyses/{id}**
```
Response 200:
{
  "id": "uuid",
  "repoId": "uuid",
  "status": "COMPLETED",
  "currentStage": 7,
  "tokensUsed": 245000,
  "createdAt": "...",
  "completedAt": "...",
  "stages": [
    {
      "stageNumber": 1,
      "stageName": "OVERVIEW",
      "status": "COMPLETED",
      "tokensUsed": 12500,
      "startedAt": "...",
      "completedAt": "..."
    }
  ],
  "result": { /* full analysis JSON */ }
}
```

**GET /api/analyses/{id}/stream**
```
SSE stream — described in section 5
```

**GET /api/analyses/{id}/overview**
```
Returns just the overview portion of the result
Response: { /* Stage 1 result JSON */ }
```

**GET /api/analyses/{id}/architecture**
```
Returns just the architecture portion
Response: { /* Stage 2 result JSON */ }
```

**GET /api/analyses/{id}/files**
```
Returns annotated file list
Response:
{
  "files": [
    {
      "id": "uuid",
      "path": "src/index.js",
      "roleSummary": "Entry point — initializes React root and renders App component",
      "language": "JavaScript",
      "sizeBytes": 1234
    }
  ]
}
```

**GET /api/analyses/{id}/risks**
```
Returns risk analysis from Stage 5
Response: { /* Stage 5 result JSON */ }
```

**POST /api/repos/{repoId}/files/{fileId}/deep-dive**
```
On-demand deep dive for a single file.
NOT part of the main pipeline — called when user clicks a file in the explorer.

Request:
{
  "analysisId": "uuid"  // to get context from
}

Response (streams via SSE or returns directly):
{
  "filePath": "src/auth/JwtService.java",
  "roleSummary": "...",
  "detailedExplanation": "This file implements JWT token generation and validation...",
  "internalLogic": "step by step explanation of the internal logic",
  "dependencies": ["list of files this file imports"],
  "dependents": ["list of files that import this file"],
  "potentialBugs": ["specific potential issues observed"],
  "howToModify": "guidance on how to safely change this file",
  "relatedFiles": ["other files closely related to this one"]
}
```

---

## 7. Java Classes to Create

### Package: `com.repomind.backend.api.repo`

**RepoController.java**
- POST /api/repos — calls RepoIngestionService
- GET /api/repos/{id} — calls RepoService
- GET /api/repos/{id}/tree — calls FileNodeService

**RepoRequest.java** (DTO)
```java
public record RepoRequest(
    @NotBlank @Pattern(regexp = "https://github.com/.*") String url,
    String branch
) {}
```

**RepoResponse.java** (DTO)

---

### Package: `com.repomind.backend.api.analysis`

**AnalysisController.java**
- POST /api/analyses
- GET /api/analyses/{id}
- GET /api/analyses/{id}/stream — returns SseEmitter
- GET /api/analyses/{id}/overview
- GET /api/analyses/{id}/architecture
- GET /api/analyses/{id}/files
- GET /api/analyses/{id}/risks
- POST /api/repos/{repoId}/files/{fileId}/deep-dive

---

### Package: `com.repomind.backend.domain.repo`

**Repo.java** (JPA Entity — maps to repos table)
**FileNode.java** (JPA Entity — maps to file_nodes table)
**FileDependency.java** (JPA Entity — maps to file_node_dependencies table)
**RepoRepository.java** (JpaRepository<Repo, UUID>)
**FileNodeRepository.java**
- `findByRepoIdAndIsInScopeTrue(UUID repoId)`
- `findByRepoIdAndType(UUID repoId, String type)`
- `updateRoleSummary(UUID fileId, String roleSummary)` — @Modifying @Query

---

### Package: `com.repomind.backend.domain.analysis`

**Analysis.java** (JPA Entity)
**AnalysisStage.java** (JPA Entity)
**AnalysisRepository.java**
**AnalysisStageRepository.java**
- `findByAnalysisIdOrderByStageNumberAsc(UUID analysisId)`
- `updateStatus(UUID stageId, String status)` — @Modifying

---

### Package: `com.repomind.backend.service.ingestion`

**RepoIngestionService.java**
- `ingestRepo(UUID repoId, String userGithubToken)` — main orchestrator
- Calls GitHubApiClient, FileTreeBuilder, DependencyGraphBuilder
- Handles errors, updates repo status

**GitHubApiClient.java**
- `getRepoInfo(String owner, String repo, String token)` → RepoInfo
- `getFileTree(String owner, String repo, String branch, String token)` → List<GitHubTreeEntry>
- `getFileContent(String owner, String repo, String path, String branch, String token)` → String
- Uses Spring WebClient for non-blocking HTTP calls
- Implements rate limiting and retry with exponential backoff
- Caches file content in Redis

**FileTreeBuilder.java**
- `buildFileNodes(List<GitHubTreeEntry> entries, UUID repoId)` → List<FileNode>
- Detects language from extension
- Calculates depth
- Applies scope filtering

**DependencyGraphBuilder.java**
- `buildDependencies(List<FileNode> files, String owner, String repo, String branch, String token)`
- Fetches file content for each source file
- Parses import statements per language
- Resolves import paths to file_node IDs
- Creates FileDependency records

**FileChunker.java**
- `chunk(String content, int chunkSize, int overlap)` → List<String>
- Chunks files for embedding

---

### Package: `com.repomind.backend.service.analysis`

**AnalysisPipelineService.java**
- `@KafkaListener(topics = "repo-analysis-jobs")`
- `runPipeline(UUID analysisId)` — orchestrates all 7 stages
- Calls each stage class in sequence
- Handles stage failures and retries
- Updates analysis status

**AnalysisPipelineMessage.java** (Kafka message DTO)
```java
public record AnalysisPipelineMessage(
    UUID analysisId,
    UUID repoId,
    UUID userId,
    String aiProvider
) {}
```

**stages/OverviewStage.java**
**stages/ArchitectureStage.java**
**stages/ModuleMappingStage.java**
**stages/DataFlowStage.java**
**stages/BugDetectionStage.java**
**stages/FileAnnotationStage.java**
**stages/EmbeddingStage.java**

Each stage implements `AnalysisStage` interface:
```java
public interface AnalysisStage {
    int getStageNumber();
    String getStageName();
    JsonNode execute(UUID analysisId, StageContext context) throws StageException;
}
```

`StageContext` holds:
- All previous stage results
- RepoId, AnalysisId, UserId
- GitHub token for API calls
- AI provider preference

---

### Package: `com.repomind.backend.service.ai`

**AiGatewayService.java**
- `complete(AiRequest request)` → AiResponse
- Routes to ClaudeProvider or OpenAiProvider
- Implements fallback logic
- Logs every call via AiCallLogService

**AiRequest.java**
```java
public record AiRequest(
    String systemPrompt,
    String userPrompt,
    AiProvider preferredProvider,
    int maxTokens,
    double temperature,
    String analysisId,
    String stageName
) {}
```

**AiResponse.java**
```java
public record AiResponse(
    String content,
    String provider,
    String model,
    int inputTokens,
    int outputTokens,
    long durationMs
) {}
```

**providers/AiProvider.java** (interface)
```java
public interface AiProvider {
    AiResponse complete(AiRequest request);
    boolean isAvailable();
}
```

**providers/ClaudeProvider.java**
- Uses Spring WebClient to call `https://api.anthropic.com/v1/messages`
- Headers: `x-api-key: {ANTHROPIC_API_KEY}`, `anthropic-version: 2023-06-01`
- Parses response, extracts text content
- Handles rate limits (429) with retry

**providers/OpenAiProvider.java**
- Uses Spring WebClient to call `https://api.openai.com/v1/chat/completions`
- Headers: `Authorization: Bearer {OPENAI_API_KEY}`
- Fallback provider

---

### Package: `com.repomind.backend.infrastructure.qdrant`

**QdrantClient.java**
- `createCollection(String name, int vectorSize)` — create if not exists
- `upsertPoints(String collection, List<QdrantPoint> points)` — batch insert
- `search(String collection, float[] queryVector, int topK, Map<String, Object> filter)` → List<QdrantSearchResult>
- `deleteByPayload(String collection, String key, String value)` — delete by repoId

**QdrantPoint.java**
```java
public record QdrantPoint(
    String id,
    float[] vector,
    Map<String, Object> payload
) {}
```

**QdrantSearchResult.java**
```java
public record QdrantSearchResult(
    String id,
    float score,
    Map<String, Object> payload
) {}
```

---

### Package: `com.repomind.backend.infrastructure.sse`

**SseEmitterRegistry.java**
- `ConcurrentHashMap<UUID, SseEmitter> emitters`
- `register(UUID analysisId, SseEmitter emitter)`
- `send(UUID analysisId, SseEvent event)`
- `complete(UUID analysisId)`
- `error(UUID analysisId, Exception e)`

**SseEvent.java**
```java
public record SseEvent(
    String eventType,
    String stage,
    String status,
    String message,
    int progress,
    int total,
    Object data
) {}
```

---

## 8. AI Prompt Templates — Stored in Database

At application startup, seed the `prompt_templates` table with these templates if they do not already exist:

| name | provider | version |
|---|---|---|
| STAGE_1_OVERVIEW | CLAUDE | 1 |
| STAGE_2_ARCHITECTURE | CLAUDE | 1 |
| STAGE_3_MODULE_MAPPING | CLAUDE | 1 |
| STAGE_4_DATA_FLOW | CLAUDE | 1 |
| STAGE_5_BUG_DETECTION | CLAUDE | 1 |
| STAGE_6_FILE_ANNOTATION | CLAUDE | 1 |
| DEEP_DIVE | CLAUDE | 1 |

This allows improving prompts in production by inserting a new version (version: 2, is_active: true, old version is_active: false) without redeploying.

---

## 9. Qdrant Integration — Embedding and Search

### Collection Initialization

On application startup, check if the `repomind-embeddings` collection exists. If not, create it:

```
POST http://qdrant:6333/collections/repomind-embeddings
{
  "vectors": {
    "size": 1536,
    "distance": "Cosine"
  },
  "optimizers_config": {
    "default_segment_number": 2
  },
  "replication_factor": 1
}
```

### Upsert Points (Store Embeddings)

```
PUT http://qdrant:6333/collections/repomind-embeddings/points
{
  "points": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "vector": [0.123, 0.456, ...],
      "payload": {
        "repoId": "uuid",
        "analysisId": "uuid",
        "fileNodeId": "uuid",
        "filePath": "src/auth/JwtService.java",
        "language": "Java",
        "chunkIndex": 0,
        "totalChunks": 1,
        "chunkText": "public class JwtService { ...",
        "roleSummary": "Generates and validates RS256 JWT tokens"
      }
    }
  ]
}
```

### Semantic Search (Used by Chat)

```
POST http://qdrant:6333/collections/repomind-embeddings/points/search
{
  "vector": [0.123, 0.456, ...],
  "limit": 10,
  "with_payload": true,
  "filter": {
    "must": [
      {
        "key": "repoId",
        "match": { "value": "uuid-of-this-repo" }
      }
    ]
  }
}
```

This returns the top 10 most semantically similar code chunks to the search vector, filtered to only return chunks from the specific repo being analyzed.

### OpenAI Embeddings API Call

```
POST https://api.openai.com/v1/embeddings
Authorization: Bearer {OPENAI_API_KEY}
{
  "input": "code content to embed",
  "model": "text-embedding-3-small"
}

Response:
{
  "data": [{ "embedding": [0.123, 0.456, ...] }],
  "usage": { "prompt_tokens": 45, "total_tokens": 45 }
}
```

---

## 10. Kafka Integration

### Topic to Create

**Topic name:** `repo-analysis-jobs`
- Partitions: 3 (allows 3 analyses to run in parallel)
- Replication factor: 1 (single broker in our setup)
- Retention: 24 hours

Create this topic on application startup using `NewTopic` bean:

```java
@Bean
public NewTopic analysisJobsTopic() {
    return TopicBuilder.name("repo-analysis-jobs")
        .partitions(3)
        .replicas(1)
        .build();
}
```

### Producer — Publish Analysis Job

Called from `AnalysisController` when POST /api/analyses is called:

```java
kafkaTemplate.send("repo-analysis-jobs", analysisId.toString(), 
    new AnalysisPipelineMessage(analysisId, repoId, userId, aiProvider));
```

### Consumer — Process Analysis Job

```java
@KafkaListener(
    topics = "repo-analysis-jobs",
    groupId = "repomind-analysis-group",
    concurrency = "3"  // 3 parallel consumers matching 3 partitions
)
public void processAnalysisJob(AnalysisPipelineMessage message) {
    analysisPipelineService.runPipeline(message);
}
```

---

## 11. Database Operations

### Key Queries Needed

**Save all file nodes in batch:**
```java
// Use saveAll() with batching configured in application.yml:
// spring.jpa.properties.hibernate.jdbc.batch_size=50
fileNodeRepository.saveAll(fileNodes);
```

**Update role_summary for a file:**
```java
@Modifying
@Query("UPDATE FileNode f SET f.roleSummary = :summary WHERE f.id = :id")
void updateRoleSummary(@Param("id") UUID id, @Param("summary") String summary);
```

**Update embedding_id for a file:**
```java
@Modifying
@Query("UPDATE FileNode f SET f.embeddingId = :embeddingId WHERE f.id = :id")
void updateEmbeddingId(@Param("id") UUID id, @Param("embeddingId") String embeddingId);
```

**Get all in-scope files for annotation:**
```java
List<FileNode> findByRepoIdAndIsInScopeTrueAndTypeAndSizeBytesLessThan(
    UUID repoId, String type, long maxSize);
```

**Update analysis stage status:**
```java
@Modifying
@Query("UPDATE AnalysisStage s SET s.status = :status, s.startedAt = :startedAt WHERE s.id = :id")
void updateStageStarted(@Param("id") UUID id, @Param("status") String status, @Param("startedAt") Instant startedAt);
```

**Save stage result:**
```java
@Modifying
@Query("UPDATE AnalysisStage s SET s.status = :status, s.result = :result, s.completedAt = :completedAt, s.tokensUsed = :tokens WHERE s.id = :id")
void updateStageCompleted(...);
```

### Redis Cache Keys

| Key Pattern | Value | TTL | Purpose |
|---|---|---|---|
| `file-content:{repoId}:{path}` | File content string | 1 hour | Cache GitHub API responses |
| `analysis-progress:{analysisId}` | Current stage number | 24 hours | Track pipeline progress |
| `repo-tree:{repoId}` | Serialized file tree | 1 hour | Cache file tree response |

---

## 12. Error Handling and Resilience

### AI Provider Errors

```
On any AI call:
  Try Claude (up to 3 retries with 2s, 4s, 8s backoff)
  If all Claude retries fail:
    Log Claude failure
    Try OpenAI (up to 2 retries)
    If OpenAI also fails:
      Mark stage as FAILED
      Mark analysis as FAILED
      Send SSE error event
      Throw StageException
```

### GitHub API Rate Limiting

```
On 403 or 429 from GitHub:
  Read X-RateLimit-Reset header
  Wait until reset time
  Retry the request
  If still failing: throw GitHubRateLimitException
```

### Stage Failure Recovery

```
Each stage checks if previous stage completed successfully.
If this analysis was previously attempted and stage N failed:
  Skip stages 1 to N-1 (already completed)
  Re-run from stage N
This allows resuming without losing completed work.
```

### Global Exception Handler

```java
@ControllerAdvice
public class GlobalExceptionHandler {
    
    @ExceptionHandler(RepoNotFoundException.class)
    // Return 404 with message
    
    @ExceptionHandler(GitHubAccessException.class)  
    // Return 403 with message
    
    @ExceptionHandler(AnalysisAlreadyRunningException.class)
    // Return 409 with existing analysis ID
    
    @ExceptionHandler(AiProviderException.class)
    // Return 503 with retry message
    
    @ExceptionHandler(Exception.class)
    // Return 500 with generic message, log full stack trace
}
```

---

## 13. The Unique Features That Set RepoMind Apart

### 1. Progressive Context Building
Each of the 7 stages receives the results of ALL previous stages as context. By Stage 5, the AI has a complete understanding of the project overview, architecture, module structure, and data flow — making the bug detection far more accurate than analyzing files in isolation.

### 2. Dependency Graph for Impact Analysis
We actually parse import statements and build a real dependency graph in the database. When the user asks "what breaks if I change X?", we traverse the graph — not just guess with AI.

### 3. Role Summaries on Every Single File
Not just the important files — EVERY file gets an AI-written role summary. A 2000-file repository gets 2000 role summaries. This is what makes the file explorer genuinely useful.

### 4. Resumable Pipeline
If the server crashes mid-analysis at Stage 4, we can resume from Stage 4. Completed stages are never re-run. This is production-grade reliability.

### 5. RAG with Repo Isolation
Every Qdrant search is filtered by repoId — users never get code from other repositories in their results. Each user's codebase is semantically isolated.

### 6. Chunked Embeddings with Overlap
We chunk files with overlapping windows so code context is never lost at chunk boundaries. A function split across two chunks is still found correctly in semantic search.

### 7. Dual AI Provider
Claude is primary, OpenAI is fallback. If Claude goes down, analysis continues seamlessly. Users never see a failure due to a single provider outage.

### 8. Live SSE Streaming
Users see progress in real time. Not a spinner — they see "Annotating files 234/847" with a progress bar. Each stage completion sends the result immediately so the UI can start rendering partial results.

### 9. On-Demand Deep Dive
After the pipeline completes, any file can get a full deep dive on demand. This is a separate AI call that goes much deeper than the batch annotation — explains internal logic, risks, how to modify, related files.

### 10. Prompt Versioning
All prompts are stored in the database with version numbers. You can A/B test prompts, roll back bad prompts, and improve output quality without redeploying.

---

## 14. Complete Implementation Order

Build in this exact order. Each step is testable before moving to the next.

### Step 1 — JPA Entities
Create all domain entity classes. Run the app — `ddl-auto: validate` confirms tables match entities.

### Step 2 — GitHub API Client
Build GitHubApiClient. Write a test that fetches the tree of a public repo. Verify file nodes are saved to PostgreSQL.

### Step 3 — POST /api/repos endpoint
Build RepoController and RepoIngestionService. Test: POST a GitHub URL, see file_nodes appearing in PgAdmin.

### Step 4 — Kafka producer
Build AnalysisController POST /api/analyses. Test: creates analysis record, publishes message to Kafka. Verify in Kafka UI.

### Step 5 — SSE endpoint (skeleton)
Build the SSE endpoint. Test: open browser to `/api/analyses/{id}/stream`, confirm connection stays open.

### Step 6 — AI Gateway
Build AiGatewayService, ClaudeProvider, OpenAiProvider. Test: send a simple prompt, verify response.

### Step 7 — Stage 1 (Overview)
Build OverviewStage. Build Kafka consumer that runs just Stage 1. Test end to end: POST /api/repos → POST /api/analyses → watch SSE → see Stage 1 result in PgAdmin.

### Step 8 — Stages 2-5
Build remaining analysis stages. Test each one sees previous stage results in context.

### Step 9 — Stage 6 (File Annotation)
Build FileAnnotationStage with batching. Test: after running, verify file_nodes have role_summary populated in PgAdmin.

### Step 10 — Qdrant collection setup
Build QdrantClient. Test: create collection, verify in Qdrant dashboard.

### Step 11 — Stage 7 (Embedding)
Build EmbeddingStage. Test: after running, verify Qdrant collection has points, verify file_nodes have embedding_id.

### Step 12 — Complete pipeline
Wire all stages together. Test full end-to-end run on a small public repo (recommended: https://github.com/spring-projects/spring-petclinic — well-structured Java project).

### Step 13 — Result endpoints
Build GET endpoints for overview, architecture, files, risks. Test all return correct data.

### Step 14 — File deep dive
Build the on-demand deep dive endpoint. Test on a specific interesting file.

### Step 15 — Dependency graph
Build DependencyGraphBuilder. Test: verify file_node_dependencies table has records after ingestion.

---

## 15. All Environment Variables Required

These must be set in `.env` before running:

```env
# Already set from previous work:
DB_HOST=postgres
DB_PORT=5432
DB_NAME=repomind
DB_USERNAME=repomind
DB_PASSWORD=repomind123
REDIS_HOST=redis
REDIS_PORT=6379
REDIS_PASSWORD=redis123
KAFKA_BOOTSTRAP_SERVERS=kafka:29092
QDRANT_HOST=qdrant
QDRANT_PORT=6333

# Must fill in now:
ANTHROPIC_API_KEY=sk-ant-...        # From console.anthropic.com
OPENAI_API_KEY=sk-...               # From platform.openai.com — needed for embeddings even if using Claude for analysis
GITHUB_TOKEN=ghp_...                # Personal access token for GitHub API — public repos only need read access

# Already set from auth work:
GITHUB_CLIENT_ID=...
GITHUB_CLIENT_SECRET=...
JWT_PRIVATE_KEY=...
JWT_PUBLIC_KEY=...
CORS_ALLOWED_ORIGINS=http://localhost:5173
```

**Note on OpenAI key:** Even if you use Claude as the primary analysis AI, you still need the OpenAI key for embeddings (`text-embedding-3-small`). Anthropic does not have a public embeddings API. If you want to use only one provider, use OpenAI for both analysis and embeddings.

**Note on GitHub token:** Create a personal access token at GitHub → Settings → Developer Settings → Personal Access Tokens → Fine-grained tokens. Required scopes: `contents: read` (for public repos, no scopes needed — but rate limits are much lower without a token).

---

## Additional Notes for Implementation

### application.yml additions needed

```yaml
app:
  github:
    api-base-url: https://api.github.com
    token: ${GITHUB_TOKEN:}
    file-content-cache-ttl: 3600  # seconds
    max-file-size-bytes: 512000   # 500KB max file to fetch
    
  analysis:
    file-annotation-batch-size: 20
    embedding-batch-size: 10
    max-files-for-annotation: 2000
    chunk-size: 1500
    chunk-overlap: 200
    
  qdrant:
    collection-name: repomind-embeddings
    vector-size: 1536
    top-k-search-results: 10

spring:
  jpa:
    properties:
      hibernate:
        jdbc:
          batch_size: 50       # batch insert for file_nodes
        order_inserts: true
        order_updates: true
```

### Recommended test repository

For testing the full pipeline during development, use:
`https://github.com/spring-projects/spring-petclinic`

Why: It is a well-known Spring Boot project, public, ~50 files, clean architecture, good variety of Java files. Small enough to run quickly, complex enough to produce interesting analysis.
