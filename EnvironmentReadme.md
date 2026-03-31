# RepoMind — Complete Environment Documentation
### Everything you need to know about this project from zero

---

## Table of Contents
1. [What is RepoMind?](#what-is-repomind)
2. [What are we building — the big picture](#what-are-we-building)
3. [Why do we need so many services?](#why-do-we-need-so-many-services)
4. [Tech Stack — every technology explained](#tech-stack)
5. [Project Folder Structure](#project-folder-structure)
6. [How Docker works in this project](#how-docker-works)
7. [All Running Services — ports, URLs, credentials](#all-running-services)
8. [Dashboard Login Guide](#dashboard-login-guide)
9. [Every file we created and why](#every-file-we-created-and-why)
10. [Database — all 11 tables explained](#database)
11. [How to start and stop everything](#how-to-start-and-stop-everything)
12. [Current status of the code](#current-status-of-the-code)
13. [What comes next](#what-comes-next)

---

## What is RepoMind?

RepoMind is an **AI-powered GitHub repository analyzer**.

The idea is simple: as a developer, when you join a new project or look at an open source library, understanding the entire codebase takes days or weeks. You have to read every file, figure out how things connect, understand the architecture — it is exhausting.

RepoMind solves this. You paste a GitHub repository URL, and within minutes the AI:
- Tells you exactly what the project does
- Shows you the tech stack and architecture
- Explains every single file in the repo and its role
- Lets you chat with the codebase in 5 specialized modes:
  - **Bug Search** — describe a bug symptom, get exact files and root cause
  - **Enhancement Guide** — describe a feature you want to add, get a file-by-file plan
  - **Subsystem Explainer** — select any folder, get a full narrative explanation
  - **Code Search** — ask in plain English, find where something is implemented
  - **Impact Analysis** — describe a change, see everything that would break

---

## What are we building — the big picture

The application has two main parts:

**1. Backend (Spring Boot — Java)**
This is the brain of the application. It handles:
- User authentication (login with GitHub)
- Fetching repository data from GitHub API
- Running AI analysis on the code
- Storing everything in the database
- Providing a REST API that the frontend calls

**2. Frontend (React — JavaScript)**
This is what the user sees in their browser. It shows:
- The landing page where you paste a GitHub URL
- A loading screen while analysis runs with live progress
- The project overview and architecture diagram
- The file explorer with AI annotations on every file
- The AI chat interface with 5 modes

These two parts talk to each other over HTTP. The frontend sends requests to the backend, the backend processes them and sends back responses.

---

## Why do we need so many services?

If you look at our Docker setup you will see 10 services running. This might seem overwhelming. Here is exactly why each one exists:

**PostgreSQL** — We need to store user data, repository info, analysis results, and chat history permanently. PostgreSQL is the main relational database. Think of it as the filing cabinet of the application. Every piece of data that must survive a restart lives here.

**Redis** — Some data needs to be accessed extremely fast (like checking if a user's login token is valid on every single incoming request). PostgreSQL is fast but Redis is 10-100x faster because it stores data in memory (RAM) instead of on disk. We use Redis for caching frequently accessed data and storing session tokens.

**Qdrant** — This is a special type of database called a vector database. When the AI analyzes code files, it converts each file into a mathematical representation called a vector (a long list of numbers that captures the meaning and content of the code). When you ask a question in the chat, your question also becomes a vector, and Qdrant finds the most relevant files by comparing vectors mathematically. This is what makes the AI chat actually understand your codebase and give precise answers instead of guessing.

**Kafka** — Analyzing a large repository with hundreds of files can take 5 to 10 minutes. We cannot make the user wait for an HTTP request that long (browsers time out after 30-60 seconds). So we use a message queue. The backend puts an "analyze this repo" message into Kafka and immediately tells the user "analysis started". A background worker picks up that message and does the heavy work asynchronously. The user watches a live progress screen while this happens. This pattern is called asynchronous processing.

**Zookeeper** — Kafka needs a coordinator service to manage itself, track which brokers are alive, and handle leader election. Zookeeper is that coordinator. You never interact with it directly — it just runs in the background keeping Kafka healthy. Think of it as Kafka's internal manager.

**MinIO** — When users want to analyze a private repository or upload a ZIP file directly, we need somewhere to store that file before processing it. MinIO is our own private file storage system — it works exactly like Amazon S3 (AWS file storage) but runs locally inside Docker. We store uploaded ZIP files here in a bucket called repomind-repos.

**PgAdmin** — This is a visual web interface for our PostgreSQL database. Instead of writing SQL commands in a terminal, you can use this browser-based UI to browse tables, view data, run queries, and inspect schema. It is only used by developers during development.

**Kafka UI** — Similar to PgAdmin but for Kafka. It gives you a visual interface to see all Kafka topics, browse messages flowing through them, see consumer groups, and monitor throughput. Essential for debugging the async analysis pipeline.

---

## Tech Stack

### Backend

| Technology | Version | What it is | Why we use it |
|---|---|---|---|
| Java | 21 | Programming language | Latest LTS version with modern features like records, sealed classes, virtual threads |
| Spring Boot | 4.x | Java web framework | Industry standard for building REST APIs in Java, handles 80% of boilerplate automatically |
| Maven | 3.9 | Build tool | Manages Java dependencies and builds the JAR file — like npm for Node.js |
| Spring Security | included | Security framework | Handles authentication, authorization, and protects API endpoints |
| Spring Data JPA | included | Database abstraction layer | Lets us work with PostgreSQL using Java objects instead of raw SQL queries |
| Hibernate | included | ORM (Object Relational Mapper) | Maps Java classes to database tables automatically |
| Flyway | included | Database migration tool | Manages and versions all database schema changes through SQL files |
| Spring Kafka | included | Kafka client library | Send messages to and receive messages from Kafka topics |
| Spring Data Redis | included | Redis client library | Store and retrieve data from Redis with simple Java APIs |
| Lombok | included | Code generation library | Reduces boilerplate — generates getters, setters, constructors, builders at compile time |
| Spring Actuator | included | Health and metrics | Provides /health endpoint and application metrics out of the box |

### Frontend

| Technology | Version | What it is | Why we use it |
|---|---|---|---|
| React | 19.x | UI library | Build interactive user interfaces with reusable components |
| Vite | 6.x | Build tool and dev server | Extremely fast development server with instant hot module replacement |
| JavaScript JSX | ES2024 | Language | JSX is JavaScript with HTML-like syntax that React uses for components |
| React Router | 7.x | Client-side routing | Navigate between pages without full browser page reload |
| Axios | 1.x | HTTP client | Make API calls from the browser to the backend with automatic JSON handling |
| Zustand | 5.x | State management | Store global app state (logged in user, current analysis) in a simple way |

### Infrastructure

| Technology | Version | What it is | Why we use it |
|---|---|---|---|
| Docker | latest | Containerization platform | Package each service with all dependencies into an isolated container |
| Docker Compose | latest | Multi-container orchestration | Define and start all 10 services together with a single command |
| PostgreSQL | 16 | Relational database | Store all structured data with full ACID guarantees |
| Redis | 7 | In-memory data store | Extremely fast cache and session storage |
| Qdrant | v1.11.0 | Vector database | Store and search AI embeddings for semantic code understanding |
| Kafka | 7.6.0 | Distributed message queue | Decouple long-running analysis jobs from HTTP requests |
| Zookeeper | 7.6.0 | Distributed coordinator | Internal Kafka cluster management |
| MinIO | latest | Object storage | Store uploaded files — self-hosted Amazon S3 equivalent |

---

## Project Folder Structure

```
D:\Projects\RepoMind\
│
├── backend\                                      # Spring Boot Java application
│   ├── src\
│   │   └── main\
│   │       ├── java\com\repomind\backend\
│   │       │   ├── BackendApplication.java       # Main class — starts the entire Spring Boot app
│   │       │   ├── HealthController.java          # GET /api/health → returns status UP
│   │       │   └── SecurityConfig.java            # Spring Security rules — what is protected
│   │       └── resources\
│   │           ├── application.yml                # All configuration (DB, Redis, Kafka, etc.)
│   │           └── db\migration\                  # Flyway SQL files — creates all database tables
│   │               ├── V1__create_users.sql
│   │               ├── V2__create_refresh_tokens.sql
│   │               ├── V3__create_repos.sql
│   │               ├── V4__create_file_nodes.sql
│   │               ├── V5__create_file_node_dependencies.sql
│   │               ├── V6__create_analyses.sql
│   │               ├── V7__create_analysis_stages.sql
│   │               ├── V8__create_chat_sessions.sql
│   │               ├── V9__create_chat_messages.sql
│   │               ├── V10__create_ai_gateway_tables.sql
│   │               └── V11__create_share_links.sql
│   ├── pom.xml                                    # Maven dependencies — like package.json for Java
│   └── Dockerfile                                 # Recipe for building the backend Docker image
│
├── frontend\                                      # React Vite application
│   ├── src\
│   │   ├── App.jsx                                # Root React component — currently default Vite template
│   │   ├── main.jsx                               # Entry point — mounts React app into index.html
│   │   └── assets\                                # Static assets (images, icons, SVGs)
│   ├── public\                                    # Files served as-is (favicon, robots.txt)
│   ├── index.html                                 # The single HTML page — React renders inside this
│   ├── package.json                               # Node.js dependencies and scripts
│   ├── vite.config.js                             # Vite configuration — host binding, proxy settings
│   └── Dockerfile                                 # Recipe for building the frontend Docker image
│
├── docker-compose.yml                             # Master file — defines and connects all 10 services
├── .env                                           # Secret environment variables — NEVER commit to Git
└── EnvironmentReadme.md                           # This documentation file
```

---

## How Docker Works in This Project

### What is Docker?
Docker lets you run applications in isolated boxes called containers. Each container includes the application code plus everything it needs to run — the runtime, libraries, configuration — all packaged together. This means:
- It runs identically on every developer machine and every server
- Services do not conflict with each other (each has its own isolated environment)
- You do not need to install PostgreSQL, Redis, Kafka, Java etc on your own Windows machine
- Starting the entire environment takes one command

### What is a Docker image vs a container?
A Docker image is like a recipe or a blueprint — it is a read-only snapshot. A container is a running instance of that image — like baking a cake from a recipe. You can run many containers from the same image.

### What is Docker Compose?
Docker Compose lets you define multiple containers in one YAML file (docker-compose.yml) and manage them all together. Instead of running 10 separate docker run commands with all their arguments, you write the configuration once and use:
- `docker-compose up` to start everything
- `docker-compose down` to stop everything
- `docker-compose ps` to see status of everything

### How our containers communicate
All 10 containers are connected to a shared virtual network called `repomind-network`. On this network, every container is reachable by its service name — Docker automatically handles the DNS resolution:

| From | To | Using hostname |
|---|---|---|
| backend | postgres | `postgres` |
| backend | redis | `redis` |
| backend | kafka | `kafka` |
| backend | qdrant | `qdrant` |
| backend | minio | `minio` |
| kafka-ui | kafka | `kafka` |
| pgadmin | postgres | `postgres` |

Port mappings like `5432:5432` in docker-compose.yml expose the service to your Windows machine. The format is `HOST_PORT:CONTAINER_PORT`. So `5050:80` means pgadmin runs on port 80 inside its container, but you access it at port 5050 on your machine.

### What is a Dockerfile?
A Dockerfile is a step-by-step recipe telling Docker how to build an image. Our backend Dockerfile:
1. Starts from an official Maven + Java 21 image (so we do not install Java ourselves)
2. Copies pom.xml and downloads all Maven dependencies (cached separately)
3. Copies the Java source code
4. Defines how to start the application

### Why do we copy pom.xml before source code?
Docker builds images in layers. Each instruction in the Dockerfile is a layer. Docker caches layers and only rebuilds layers that changed. Since Maven dependencies (pom.xml) change far less often than source code, we copy them separately. When you only change a Java file, Docker uses the cached dependencies layer and skips the slow download step. This makes rebuilds from ~3 minutes to ~10 seconds.

---

## All Running Services

| Service | Container Name | URL | Port | Credentials |
|---|---|---|---|---|
| Spring Boot Backend | repomind-backend | http://localhost:8080 | 8080 | none |
| React Frontend | repomind-frontend | http://localhost:5173 | 5173 | none |
| PostgreSQL Database | repomind-postgres | localhost:5432 | 5432 | user: `repomind` password: `repomind123` database: `repomind` |
| Redis Cache | repomind-redis | localhost:6379 | 6379 | password: `redis123` |
| Qdrant Vector DB | repomind-qdrant | http://localhost:6333 | 6333 | none |
| Kafka Message Queue | repomind-kafka | localhost:9092 | 9092 | none |
| Zookeeper | repomind-zookeeper | localhost:2181 | 2181 | none — internal only |
| MinIO Console UI | repomind-minio | http://localhost:9001 | 9001 | username: `minioadmin` password: `minioadmin123` |
| MinIO API | repomind-minio | http://localhost:9000 | 9000 | same as above |
| PgAdmin UI | repomind-pgadmin | http://localhost:5050 | 5050 | email: `admin@repomind.com` password: `admin123` |
| Kafka UI | repomind-kafka-ui | http://localhost:8090 | 8090 | none |
| JVM Remote Debugger | repomind-backend | localhost:5005 | 5005 | attach your IDE debugger here |

---

## Dashboard Login Guide

### PgAdmin — PostgreSQL Visual Interface
**What it is:** A browser-based GUI for managing and querying your PostgreSQL database. Instead of typing SQL commands in a terminal, you can click through tables, browse data, and run queries visually.

**URL:** http://localhost:5050

**Step 1 — Login to PgAdmin itself:**
- Email: `admin@repomind.com`
- Password: `admin123`
- Click Login

**Step 2 — Register the database server connection:**
After logging in you will see the PgAdmin dashboard. You need to tell PgAdmin how to connect to our PostgreSQL container:
- Right click on "Servers" in the left sidebar
- Click "Register" → "Server..."
- A dialog box opens

In the **General** tab:
- Name: `RepoMind` (just a display label — can be anything)

Click the **Connection** tab:
- Host name/address: `postgres` (this is the Docker container name — PgAdmin is also on the Docker network so it resolves this)
- Port: `5432`
- Maintenance database: `repomind`
- Username: `repomind`
- Password: `repomind123`
- Toggle "Save password?" to ON so you don't have to type it every time

Click **Save**.

**Step 3 — Browse the database:**
In the left sidebar expand:
`RepoMind` → `Databases` → `repomind` → `Schemas` → `public` → `Tables`

You will see all 13 tables.

**Step 4 — View data in a table:**
Right click any table → "View/Edit Data" → "All Rows" — opens a spreadsheet-style view of all rows.

**Step 5 — Run a SQL query:**
Click "Tools" in the top menu → "Query Tool" → type SQL → click the Play button (▶) to run.

Example: `SELECT * FROM users;`

---

### Kafka UI — Kafka Visual Interface
**What it is:** A browser-based dashboard for monitoring Apache Kafka. Shows you all topics, messages, consumers, and throughput in real time.

**URL:** http://localhost:8090
**Login:** No login required — opens directly to the dashboard.

**What you will see:**
- **Brokers** — the Kafka server instances running (we have 1)
- **Topics** — named channels where messages are published and consumed (currently empty — we create topics when we implement the pipeline)
- **Consumers** — services that are reading from topics
- **Schema Registry** — message format definitions (not used yet)

**What we will use it for:**
When the analysis pipeline is built, you will see topics like `repo-analysis-jobs` and `analysis-progress`. You can use this UI to see messages flowing through, debug stuck jobs, and monitor how fast the system is processing analyses.

---

### MinIO Console — Object Storage Interface
**What it is:** A browser-based file manager for MinIO, our self-hosted file storage. Works exactly like the AWS S3 console.

**URL:** http://localhost:9001
**Login:**
- Username: `minioadmin`
- Password: `minioadmin123`

**What you can do here:**
- Create buckets (a bucket is like a top-level folder)
- Upload and download files
- Set access policies (public vs private)
- Browse stored files

**What we will use it for:**
When a user uploads a ZIP file of their repository (instead of providing a GitHub URL), the backend stores that ZIP file here in a bucket called `repomind-repos`. The analysis service then reads the ZIP from here to process it.

**Note:** The bucket `repomind-repos` has not been created yet — that happens when we implement the upload feature.

---

### Qdrant Dashboard — Vector Database Interface
**What it is:** A browser-based UI for the Qdrant vector database.

**URL:** http://localhost:6333/dashboard
**Login:** No login required.

**What you will see:**
- **Collections** — like tables but for vectors (currently empty)
- **Points** — individual vectors stored in a collection
- **Search** — run test similarity searches

**What we will use it for:**
After Stage 7 of the analysis pipeline runs, every code file gets embedded as a vector and stored here in a collection called `repomind-embeddings`. When you type a question in the AI chat, the backend converts your question to a vector and Qdrant returns the most similar code file vectors — those files become the context for the AI's answer.

---

## Every File We Created and Why

### Generated by Spring Initializer (start.spring.io)

**`backend/pom.xml`** — Maven project file listing all Java dependencies. We selected these dependencies on the Spring Initializer website and it generated this file. Think of it like package.json for Java.

**`backend/src/main/java/com/repomind/backend/BackendApplication.java`** — The main entry point. Spring Initializer generates this. It contains the `main()` method that boots the entire Spring application. The `@SpringBootApplication` annotation tells Spring to scan all classes in the package and auto-configure everything.

**`backend/src/test/java/com/repomind/backend/BackendApplicationTests.java`** — A basic test class that just checks the Spring context loads without errors. Generated automatically.

---

### Generated by Vite (npm create vite@latest)

**`frontend/src/App.jsx`** — The root React component. Currently shows the default Vite + React welcome page. We will replace its contents with RepoMind UI.

**`frontend/src/main.jsx`** — The JavaScript entry point. It imports React, imports App.jsx, and renders the App component into the `<div id="root">` in index.html. This is the bridge between HTML and React.

**`frontend/index.html`** — The single HTML page. The entire React app lives inside `<div id="root"></div>`. Vite injects the compiled JavaScript here.

**`frontend/package.json`** — Lists all Node.js dependencies and defines scripts like `npm run dev` and `npm run build`. Generated by Vite, then we ran `npm install axios react-router-dom zustand` to add our extra dependencies.

---

### Created manually by us

#### `backend/Dockerfile`
**Why we created it:** Spring Initializer does not generate Docker configuration — that is always written by the developer.

**Full explanation of every line:**
```dockerfile
FROM maven:3.9-eclipse-temurin-21
```
Start from an official Docker image that already has Maven 3.9 and Java 21 (Eclipse Temurin JDK) installed. We do not need to install Java ourselves.

```dockerfile
WORKDIR /app
```
Set the working directory inside the container to /app. All subsequent commands run from here.

```dockerfile
COPY pom.xml .
RUN mvn dependency:go-offline -B
```
Copy ONLY pom.xml first. Then download all Maven dependencies and cache them offline. The `-B` flag means batch mode (no interactive prompts). This is a separate step from copying source code so Docker caches it. If pom.xml has not changed, this entire step is skipped on rebuild — saving 2-3 minutes.

```dockerfile
COPY src ./src
```
Now copy the actual Java source code. This runs after dependencies so changing source code does not invalidate the dependency cache.

```dockerfile
EXPOSE 8080 5005
```
Document that this container uses port 8080 (the Spring Boot app) and port 5005 (Java remote debugger). EXPOSE is documentation — the actual port binding happens in docker-compose.yml.

```dockerfile
CMD ["mvn", "spring-boot:run", "-Dspring-boot.run.jvmArguments=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"]
```
Start the app using Maven. The long JVM argument enables remote debugging:
- `transport=dt_socket` — use network socket (not shared memory)
- `server=y` — JVM listens for a debugger to connect (not the other way around)
- `suspend=n` — do not wait for debugger before starting
- `address=*:5005` — listen on all interfaces on port 5005

This lets you attach your IDE (IntelliJ, VS Code) to the running container and set breakpoints.

---

#### `frontend/Dockerfile`
**Why we created it:** Vite does not generate Docker configuration.

```dockerfile
FROM node:20-alpine
```
Start from Node.js 20 on Alpine Linux. Alpine is a minimal Linux distribution (only ~5MB) — keeps our image small and fast to pull.

```dockerfile
WORKDIR /app
COPY package.json .
RUN npm install
```
Copy package.json first, run npm install as a cached layer. If package.json has not changed, the npm install step is skipped on rebuild.

```dockerfile
COPY . .
EXPOSE 5173
CMD ["npm", "run", "dev", "--", "--host"]
```
Copy all source files. Expose port 5173 (Vite default). Start Vite dev server with `--host` flag.

**Why the --host flag is critical:** Without it, Vite only listens on `127.0.0.1` (localhost inside the container). Docker port mapping cannot forward traffic to `127.0.0.1` — it needs the server to listen on `0.0.0.0` (all interfaces). Without this flag, the container starts but the browser gets "site can't be reached".

---

#### `frontend/vite.config.js` — Modified from generated version
**Problem we solved:** Even after adding `--host` to the CMD, the frontend was still not accessible at http://localhost:3000. The browser showed "site can't be reached".

**Root cause discovery:** We tried `docker run -p 3000:5173` and then `docker run -p 5173:5173`. The second one worked. This revealed that Vite was listening on port 5173 inside the container, so the port mapping must match.

**Fix applied:**
```javascript
server: {
    host: '0.0.0.0',   // listen on ALL network interfaces, not just 127.0.0.1
    port: 5173,         // explicit port so it never randomly changes
}
```

**Lesson learned:** Always explicitly set host to 0.0.0.0 for any dev server running inside Docker.

---

#### `backend/src/main/resources/application.yml` — Replaced application.properties
**Why we replaced it:** Spring Initializer generates `application.properties` which uses flat key=value format. We replaced it with `application.yml` which uses YAML hierarchical format — much more readable for nested configuration.

**Key design decisions:**

`context-path: /api` — All endpoints prefixed with /api. The health endpoint is /api/health not just /health. This makes Nginx proxy configuration clean in production: proxy all /api/ requests to the backend.

`ddl-auto: validate` — The most important JPA setting. Options are: create (dangerous — drops and recreates tables), create-drop (drops on shutdown), update (tries to alter tables — can lose data), validate (only checks schema matches entities — never touches data), none. We use validate because Flyway owns the schema. If a Java entity does not match the database table, the app crashes on startup with a clear error — fail fast, never corrupt data silently.

`flyway.locations: classpath:db/migration` — Tells Flyway exactly where to find the SQL files: inside the JAR under db/migration/ (which maps to src/main/resources/db/migration/ in source).

All secrets use `${ENV_VAR:default}` syntax — reads from environment variable, falls back to default if not set. This means the same application.yml works in Docker (where ENV vars come from .env file) and locally (where defaults are used).

---

#### `backend/src/main/java/com/repomind/backend/HealthController.java`
**Why we created it:** Spring Boot does not generate any controllers — only the main application class. All REST endpoints are written by developers.

**Purpose:** A dead-simple endpoint that confirms the backend is alive and responding. Returns:
```json
{
  "status": "UP",
  "service": "repomind-backend",
  "timestamp": "2026-03-21T05:57:42Z"
}
```

**Why it matters:**
1. Docker health checks — Docker can call this every 10 seconds to confirm the backend is healthy. If it stops responding, Docker can restart the container automatically.
2. Load balancers — In production, the load balancer pings this before routing traffic to an instance.
3. Development verification — Quickest way to confirm the backend started correctly after a code change.

**Annotations explained:**
- `@RestController` — This class handles HTTP requests and returns JSON responses directly (not HTML pages)
- `@RequestMapping("/health")` — All methods in this class are under the /health path
- `@GetMapping` — This method handles GET requests
- `ResponseEntity.ok(...)` — Returns HTTP 200 OK with the given body

---

#### `backend/src/main/java/com/repomind/backend/SecurityConfig.java`
**Why we created it:** Spring Boot's default security configuration was causing a critical problem.

**The problem:** When we first accessed http://localhost:8080/api/health, the browser redirected to GitHub's OAuth2 login page instead of showing the JSON response. This happened because:
1. We included spring-boot-starter-oauth2-client in pom.xml
2. Spring Boot auto-configuration detected this and automatically enabled GitHub OAuth2 login
3. Spring Security's default behavior: if any OAuth2 provider is configured, protect all endpoints and redirect unauthenticated users to the OAuth2 login

**Our fix:**
```java
.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
.oauth2Login(oauth2 -> oauth2.disable())
```
Allow all requests without authentication and disable the OAuth2 login redirect.

**Why we kept the OAuth2 dependency:** We will need OAuth2 when we implement GitHub login. Removing it now and adding it back later would disrupt the project. We just disabled its auto-behavior for now.

**Current state:** Fully permissive — no authentication required for any endpoint. This is intentional for the scaffolding stage.

**Future state:** When auth is implemented, this file will be completely replaced with proper JWT validation, OAuth2 login flow, and per-endpoint authorization rules.

---

#### `docker-compose.yml`
**Why we created it:** The main orchestration file. Defines all 10 services, how they are built, how they connect, and how they start.

**Key design decisions:**

**Health checks on PostgreSQL and Redis:**
```yaml
healthcheck:
  test: ["CMD-SHELL", "pg_isready -U repomind -d repomind"]
  interval: 10s
  retries: 5
```
Docker checks these every 10 seconds. The backend is configured with `depends_on: postgres: condition: service_healthy` — it will not start until PostgreSQL is actually ready to accept connections (not just "running"). Without this, the backend would start, try to connect to PostgreSQL before it is ready, and crash.

**Two Kafka listener addresses:**
```yaml
KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092,PLAINTEXT_INTERNAL://kafka:29092
```
Kafka needs two different addresses because it is accessed from two different network contexts:
- `localhost:9092` — for tools running on your Windows machine (Kafka UI from outside Docker, or testing with a local client)
- `kafka:29092` — for containers on the Docker network (the backend uses this address)

That is why .env has `KAFKA_BOOTSTRAP_SERVERS=kafka:29092` — the backend is inside Docker and must use the internal address.

**Named volumes:**
```yaml
volumes:
  postgres_data:/var/lib/postgresql/data
  redis_data:/data
  qdrant_data:/qdrant/storage
  minio_data:/data
```
Volumes persist data on your host machine even when containers stop or are removed. PostgreSQL data survives `docker-compose down` because of this. Running `docker-compose down -v` deletes volumes and all data.

**env_file:**
```yaml
env_file:
  - .env
```
The backend container reads all variables from the .env file. Spring Boot then reads them using ${VARIABLE_NAME} in application.yml. This chain is: .env file → Docker injects as environment variables → Spring Boot reads them.

---

#### `.env`
**Why we created it:** Store all secrets and environment-specific configuration outside of code.

**Why it must never be committed to Git:** If you push this file to GitHub, your database passwords, API keys, and OAuth secrets are public for anyone to see. It is listed in .gitignore.

**How new developers set up:** They copy the .env.example file (which has placeholder values), fill in their own API keys, and never commit the filled .env.

**Current contents explained:**
```env
DB_HOST=postgres          # hostname of postgres container — NOT localhost
DB_PASSWORD=repomind123   # database password
REDIS_PASSWORD=redis123   # redis auth password
KAFKA_BOOTSTRAP_SERVERS=kafka:29092  # internal Docker address — NOT localhost:9092
MINIO_ENDPOINT=http://minio:9000     # internal Docker address
ANTHROPIC_API_KEY=        # empty — fill in when implementing AI features
GITHUB_CLIENT_ID=         # empty — fill in when implementing GitHub login
JWT_PRIVATE_KEY=          # empty — fill in when implementing JWT auth
```

---

#### Flyway Migration Files V1 through V11

**What is Flyway?**
Flyway is a database migration tool. Instead of manually running SQL to create tables (which is error-prone and hard to track), you write numbered SQL files and Flyway runs them automatically in order when the application starts. Every developer and every environment gets the exact same database schema.

**How Flyway works step by step:**
1. App starts → Flyway looks in `src/main/resources/db/migration/`
2. Flyway checks the `flyway_schema_history` table in PostgreSQL to see which migrations already ran
3. Flyway runs any new migrations in ascending version order (V1, then V2, then V3...)
4. After each migration succeeds, Flyway records it in `flyway_schema_history` with a checksum
5. If you ever modify an already-run migration file, Flyway detects the checksum mismatch and refuses to start — this protects you from accidentally changing a schema that is already in production

**File naming convention:** `V{version}__{description}.sql`
- V = always the letter V (required by Flyway)
- version number (1, 2, 3...)
- double underscore __ separates version from description
- description uses underscores for spaces
- Example: `V4__create_file_nodes.sql`

**Why separate files instead of one big SQL file:**
Each migration is independent and trackable. If V7 fails, V1-V6 are already done. You can add V12 later to ALTER a table without touching V1-V11. The history is clear and auditable.

---

## Database

### All Tables Explained

**flyway_schema_history** (created automatically by Flyway)
Not one of our tables — Flyway creates this itself. It records every migration that has run: the version, description, script filename, checksum, execution time, and whether it succeeded. Flyway reads this on every startup to know which migrations to skip. Never manually modify this table.

---

**users** — Created by V1__create_users.sql
Stores every person who has logged into RepoMind via GitHub OAuth2.

| Column | Type | Purpose |
|---|---|---|
| id | UUID | Unique identifier for each user — generated automatically |
| github_id | BIGINT | GitHub's own numeric user ID — stable even if they rename their account |
| email | VARCHAR | Their GitHub email — may be null if they hide it on GitHub |
| username | VARCHAR | Their GitHub login name (e.g. "johndoe") |
| avatar_url | TEXT | URL to their GitHub profile picture |
| github_token | TEXT | Their OAuth access token stored encrypted — used to fetch their private repos |
| plan | VARCHAR | FREE, PRO, or ENTERPRISE — for future paid tier features |
| created_at | TIMESTAMPTZ | When they first logged in |
| updated_at | TIMESTAMPTZ | Last time their record was updated |

---

**refresh_tokens** — Created by V2__create_refresh_tokens.sql
Manages user sessions. When someone logs in we give them two tokens: an access token (valid 15 minutes, short-lived) and a refresh token (valid 7 days, long-lived). When the access token expires, the frontend sends the refresh token to get a new access token — the user stays logged in without re-authenticating.

| Column | Type | Purpose |
|---|---|---|
| id | UUID | Unique identifier |
| user_id | UUID | Which user this token belongs to |
| token_hash | VARCHAR | SHA-256 hash of the raw token — we NEVER store the actual token, only its hash |
| expires_at | TIMESTAMPTZ | When this refresh token expires |
| revoked | BOOLEAN | Set to true on logout — token is invalidated without deleting the record |

Why store the hash and not the token? If someone gains access to your database, they cannot use hashed tokens to log in — they would need the original token. Same reason passwords are stored as hashes.

---

**repos** — Created by V3__create_repos.sql
Every GitHub repository ever submitted for analysis by any user.

| Column | Type | Purpose |
|---|---|---|
| id | UUID | Unique identifier |
| user_id | UUID | Which user submitted this repo |
| url | TEXT | The full GitHub URL as the user pasted it |
| name | VARCHAR | Repo name parsed from URL (e.g. "react") |
| owner | VARCHAR | Repo owner parsed from URL (e.g. "facebook") |
| provider | VARCHAR | GITHUB — extensible for GitLab, Bitbucket later |
| is_private | BOOLEAN | Whether this is a private repo |
| default_branch | VARCHAR | main or master — used when fetching file tree |
| file_count | INT | Total number of files — populated during ingestion |
| size_kb | BIGINT | Repo size in kilobytes |
| status | VARCHAR | PENDING → INGESTING → READY or FAILED |
| error_msg | TEXT | If status is FAILED, the error message explaining why |

---

**file_nodes** — Created by V4__create_file_nodes.sql
The most important table for the analysis. One row for every single file and folder in a repo. A repo with 500 files gets 500+ rows here.

| Column | Type | Purpose |
|---|---|---|
| id | UUID | Unique identifier |
| repo_id | UUID | Which repo this file belongs to |
| path | TEXT | Full path from repo root: "src/main/java/com/example/App.java" |
| name | VARCHAR | Just the filename: "App.java" |
| type | VARCHAR | FILE or DIRECTORY |
| depth | INT | How deep in the tree — 0 is root level |
| size_bytes | BIGINT | File size |
| language | VARCHAR | Detected language: Java, TypeScript, Python, etc. |
| role_summary | TEXT | AI-generated one-line description: "Entry point, bootstraps Spring context" — filled in Stage 6 |
| embedding_id | VARCHAR | The ID of this file's vector stored in Qdrant — filled in Stage 7 |
| is_in_scope | BOOLEAN | User can exclude folders from analysis — excluded files are false here |

---

**file_node_dependencies** — Created by V5__create_file_node_dependencies.sql
Tracks which files import or depend on which other files. This data powers the Impact Analysis chat mode — if you change FileA.java, the AI can find everything that imports or uses FileA.java.

| Column | Type | Purpose |
|---|---|---|
| source_file_id | UUID | The file that has the dependency (the importer) |
| target_file_id | UUID | The file being depended on (the imported) |
| relationship_type | VARCHAR | IMPORTS, EXTENDS, IMPLEMENTS, CALLS, or USES |

---

**analyses** — Created by V6__create_analyses.sql
One row per analysis run. A user can analyze the same repo multiple times (with different scope, different AI provider, etc.).

| Column | Type | Purpose |
|---|---|---|
| id | UUID | Unique identifier |
| repo_id | UUID | Which repo is being analyzed |
| user_id | UUID | Who triggered this analysis |
| ai_provider | VARCHAR | CLAUDE or OPENAI — which AI ran this analysis |
| status | VARCHAR | PENDING → RUNNING → COMPLETED or FAILED |
| current_stage | INT | Which of the 7 stages is currently running (1-7) — shown on loading screen |
| scope_file_ids | UUID[] | PostgreSQL array of file IDs the user chose to include |
| result | JSONB | Final merged JSON output from all 7 stages — the complete analysis |
| tokens_used | INT | Total AI tokens consumed across all stages — for cost tracking |
| completed_at | TIMESTAMPTZ | When the analysis finished |

---

**analysis_stages** — Created by V7__create_analysis_stages.sql
One row per stage per analysis (7 rows per analysis run). Allows tracking and resuming individual stages independently. If the app crashes during Stage 4, we can resume from Stage 4 instead of restarting from Stage 1.

| Column | Type | Purpose |
|---|---|---|
| analysis_id | UUID | Which analysis this stage belongs to |
| stage_number | INT | 1 through 7 |
| stage_name | VARCHAR | OVERVIEW, ARCHITECTURE, MODULE_MAPPING, DATA_FLOW, BUG_DETECTION, FILE_ANNOTATION, EMBEDDING |
| status | VARCHAR | PENDING, RUNNING, COMPLETED, or FAILED |
| result | JSONB | Stage-specific output JSON — what this stage produced |
| tokens_used | INT | AI tokens used by this specific stage |

---

**chat_sessions** — Created by V8__create_chat_sessions.sql
One conversation thread. A user can have multiple chat sessions per analysis — for example one in Bug Search mode and one in Enhancement Guide mode.

| Column | Type | Purpose |
|---|---|---|
| id | UUID | Unique identifier |
| user_id | UUID | Which user owns this chat |
| analysis_id | UUID | Which analysis this chat is about |
| title | VARCHAR | Auto-generated or user-set title for the conversation |
| mode | VARCHAR | BUG_SEARCH, ENHANCEMENT_GUIDE, SUBSYSTEM_EXPLAINER, CODE_SEARCH, or IMPACT_ANALYSIS |
| message_count | INT | Number of messages — incremented on each new message |
| last_active | TIMESTAMPTZ | Updated every time a message is sent — used for sorting recent chats |

---

**chat_messages** — Created by V9__create_chat_messages.sql
Every individual message in every chat session. Both user messages and AI responses are stored here.

| Column | Type | Purpose |
|---|---|---|
| session_id | UUID | Which conversation this message belongs to |
| role | VARCHAR | USER (message from the person) or ASSISTANT (message from AI) |
| content | TEXT | The full message text |
| tokens_used | INT | AI tokens used to generate this response |
| context_chunks | JSONB | JSON array of Qdrant search results used as context for this AI response — stored for debugging |

The context_chunks field is very useful for debugging. It lets you see exactly which files the AI was given as context when it generated an answer — helping you understand why the AI said what it said.

---

**prompt_templates** — Created by V10__create_ai_gateway_tables.sql
Versioned AI prompt templates for each analysis stage and chat mode. Stored in the database instead of hardcoded in Java so prompts can be improved without redeploying the entire backend.

| Column | Type | Purpose |
|---|---|---|
| name | VARCHAR | Unique identifier: "OVERVIEW_STAGE", "BUG_SEARCH_CHAT", etc. |
| system_prompt | TEXT | The system instruction given to the AI |
| user_prompt | TEXT | The user message template with placeholders for file content |
| version | INT | Incremented each time the prompt is improved |
| is_active | BOOLEAN | Only the active version is used — old versions are kept for comparison |

---

**ai_call_logs** — Created by V10__create_ai_gateway_tables.sql
Every single call made to an AI provider (Claude or OpenAI) is logged here. This is critical for production operations.

| Column | Type | Purpose |
|---|---|---|
| provider | VARCHAR | CLAUDE or OPENAI |
| model | VARCHAR | claude-opus-4-5, gpt-4o, etc. |
| input_tokens | INT | Tokens in the prompt |
| output_tokens | INT | Tokens in the response |
| duration_ms | BIGINT | How long the AI call took in milliseconds |
| success | BOOLEAN | Whether the call succeeded or failed |
| error_msg | TEXT | If it failed, the error message |

This table answers questions like: How much did this analysis cost? Why did this AI call fail? Which model is slower? Is Claude or OpenAI performing better?

---

**share_links** — Created by V11__create_share_links.sql
When a user wants to share their analysis with someone who does not have a RepoMind account, they generate a share link. Anyone with the link can view the analysis in read-only mode.

| Column | Type | Purpose |
|---|---|---|
| analysis_id | UUID | Which analysis is being shared |
| created_by | UUID | Which user created this share link |
| token | VARCHAR | Random unique string used in the URL: /share/{token} |
| expires_at | TIMESTAMPTZ | When the link expires — null means it never expires |
| view_count | INT | How many times this link has been opened |
| is_active | BOOLEAN | Owner can deactivate without deleting — link stops working immediately |

---

## How to Start and Stop Everything

### Start everything including a rebuild (use after any code change):
```bash
cd D:\Projects\RepoMind
docker-compose up --build -d
```
`--build` rebuilds Docker images for backend and frontend if their source changed.
`-d` runs everything in the background (detached mode) so your terminal is free.

### Start everything without rebuilding (use when no code changed):
```bash
docker-compose up -d
```

### Check all containers status:
```bash
docker-compose ps
```
Shows name, status, and ports for every container. Look for "Up" in the status column.

### View logs for a specific service:
```bash
docker logs repomind-backend
docker logs repomind-postgres
docker logs repomind-frontend
docker logs repomind-kafka
```

### Follow logs in real time (like tail -f):
```bash
docker logs -f repomind-backend
```
Press Ctrl+C to stop following.

### Rebuild and restart only the backend (after a Java code change):
```bash
docker-compose up --build -d backend
```

### Stop all containers (data is preserved):
```bash
docker-compose down
```

### Stop all containers AND delete all data (completely fresh start):
```bash
docker-compose down -v
```
⚠️ The `-v` flag deletes all Docker volumes. All PostgreSQL data, Redis cache, Qdrant vectors, and MinIO files are permanently deleted. Use this when you want a completely clean slate.

### Open a terminal inside a running container:
```bash
docker exec -it repomind-backend bash
docker exec -it repomind-postgres bash
```

### Run PostgreSQL CLI directly:
```bash
docker exec -it repomind-postgres psql -U repomind -d repomind
```

---

## Current Status of the Code

### What is fully working:

✅ **All 10 Docker containers running and healthy**
PostgreSQL (healthy), Redis (healthy), Qdrant (running), Kafka (running), Zookeeper (running), MinIO (running), PgAdmin (running), Kafka UI (running), Backend (running), Frontend (running)

✅ **Complete database schema**
All 11 tables created by Flyway migrations, verified in PgAdmin. Flyway history shows all V1-V11 migrations applied successfully.

✅ **Backend starts and connects to all services**
Spring Boot connects to PostgreSQL, Redis, and Kafka on startup. No connection errors in logs.

✅ **Health endpoint responding**
`GET http://localhost:8080/api/health` returns:
```json
{"status":"UP","service":"repomind-backend","timestamp":"2026-03-21T05:57:42Z"}
```

✅ **Frontend dev server running**
React + Vite accessible at http://localhost:5173 (shows default Vite template)

✅ **All developer dashboards accessible**
PgAdmin at :5050, Kafka UI at :8090, MinIO Console at :9001, Qdrant dashboard at :6333/dashboard

---

### What is not implemented yet:

❌ **No RepoMind UI** — Frontend shows default Vite template page, React Router not configured, no screens built

❌ **No authentication** — GitHub OAuth2 login flow not implemented, JWT tokens not generated or validated

❌ **No JPA entities** — Java classes for User, Repo, Analysis etc. not created yet (tables exist in DB but no Java representation)

❌ **No repository ingestion** — Cannot fetch file tree from GitHub API yet

❌ **No analysis pipeline** — The 7-stage Kafka-driven analysis not implemented

❌ **No AI integration** — Anthropic Claude and OpenAI API calls not implemented

❌ **No chat system** — RAG pipeline, Qdrant vector search, streaming responses not implemented

❌ **No Kafka topics** — Message queue is running but no topics created, no producers or consumers

❌ **No Qdrant collections** — Vector database running but no collections created

❌ **No MinIO buckets** — Object storage running but no buckets created

❌ **JWT keys not generated** — RSA key pair needed for JWT signing — to be generated when implementing auth

---

## What Comes Next

The planned implementation order after this base setup:

1. **React Router + page skeleton** — Add all 5 routes, create skeleton screen components, configure Vite proxy for /api/ calls
2. **Landing page UI** — Build the page where users paste a GitHub URL
3. **GitHub OAuth2 + JWT** — Implement login with GitHub, issue JWT tokens, protect endpoints
4. **JPA entities** — Create Java entity classes for all 11 tables
5. **Repo ingestion** — GitHub API client, fetch file tree, save as file_nodes records
6. **Analysis pipeline** — 7-stage Kafka-driven pipeline with SSE live progress
7. **AI integration** — Anthropic Claude as primary, OpenAI as fallback
8. **RAG chat** — Embed files into Qdrant, implement semantic search, streaming chat responses
9. **Production setup** — Multi-stage Docker builds, Nginx, production docker-compose

---

## All Dependencies Reference

### Backend — pom.xml

| Dependency | What it does in RepoMind |
|---|---|
| spring-boot-starter-web | Enables @RestController, @GetMapping etc. Runs embedded Tomcat on port 8080 |
| spring-boot-starter-webflux | Provides WebClient bean — used for non-blocking HTTP calls to GitHub API and AI providers |
| spring-boot-starter-security | Provides SecurityFilterChain, protects endpoints, handles authentication filters |
| spring-boot-starter-oauth2-client | Manages GitHub OAuth2 authorization code flow — login redirect, token exchange |
| spring-boot-starter-oauth2-resource-server | Validates JWT bearer tokens on incoming requests using the public key |
| spring-boot-starter-data-jpa | Provides JpaRepository interface, @Entity support, EntityManager for PostgreSQL |
| postgresql | JDBC driver — the actual network connector between Java and PostgreSQL |
| flyway-core | Scans db/migration folder on startup and runs new SQL migrations automatically |
| flyway-database-postgresql | PostgreSQL-specific Flyway support — required since Flyway 10 split into modules |
| spring-boot-starter-data-redis | Provides RedisTemplate, @Cacheable, StringRedisTemplate for Redis operations |
| spring-kafka | Provides KafkaTemplate.send() for producing messages and @KafkaListener for consuming |
| spring-boot-starter-validation | Enables @Valid on controller parameters, @NotNull @Size on DTO fields |
| spring-boot-starter-actuator | Automatically creates /actuator/health and /actuator/metrics endpoints |
| spring-boot-starter-aop | Enables @Aspect and @Around — used for execution time logging and AI call auditing |
| lombok | @Data generates getters/setters, @Builder generates builder pattern, @Slf4j injects logger |
| jackson-datatype-jsr310 | Serializes Java LocalDateTime and Instant as ISO 8601 strings instead of arrays |
| spring-boot-devtools | Watches classpath for changes and restarts app automatically in dev mode |

### Frontend — package.json

| Dependency | What it does in RepoMind |
|---|---|
| react | Core library — provides useState, useEffect, component model |
| react-dom | Renders React component tree to the browser's DOM |
| vite | Dev server with instant hot reload, production bundler |
| @vitejs/plugin-react | Enables JSX transformation and React Fast Refresh (instant UI updates without page reload) |
| react-router-dom | Provides BrowserRouter, Routes, Route, Link, useNavigate, useParams for page navigation |
| axios | Makes HTTP requests to backend — handles JSON, interceptors for auth headers, error handling |
| zustand | Global state store — holds logged-in user info, current analysis data, UI flags |
