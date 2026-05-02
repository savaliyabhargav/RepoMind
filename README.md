# RepoMind

RepoMind is an AI-powered repository intelligence platform built to help developers understand unfamiliar codebases without spending days reverse-engineering them by hand. A user submits a GitHub repository, RepoMind ingests the full project structure, stores a normalized model of the codebase, runs a staged AI analysis pipeline, generates searchable semantic embeddings, and exposes the results through a React dashboard with live progress streaming and guided exploration.

The goal is not to produce a shallow summary. RepoMind is designed to turn a raw repository into a navigable system map: what the project does, how it is structured, which files matter, how requests and data move through the app, where risk likely lives, and how a developer can safely make changes.

## Why RepoMind Exists

Modern repositories are large, layered, and noisy. New contributors, reviewers, and even experienced maintainers lose time trying to answer basic but critical questions:

- Where does the application start?
- Which modules own which responsibilities?
- How does a request flow from the UI to persistence?
- Which files are central and which are incidental?
- What could break if a feature is changed?
- Where are the likely security or design risks?

RepoMind exists to compress that discovery cycle. Instead of manually opening dozens or hundreds of files, the user gets a structured analysis and a contextual interface for understanding the system faster.

## What The Product Delivers

RepoMind is built around a few core outcomes:

- Repository ingestion from GitHub with metadata capture and full recursive file-tree persistence.
- AI-generated repository understanding, broken into explicit analysis stages instead of a single opaque response.
- File-level role summaries for every stored file node so the project tree becomes readable at a glance.
- Live progress streaming during analysis so users can see the pipeline advance in real time.
- Retrieval-augmented chat over repository context so questions can be answered against the indexed codebase, not generic model memory.

At a product level, RepoMind is meant to feel like an engineering copilot for codebase onboarding, architecture discovery, bug triage, enhancement planning, and change impact analysis.

## How RepoMind Works

### 1. Repository ingestion

When a repository URL is submitted, the backend validates the URL, fetches GitHub repository metadata, retrieves the full tree with a recursive GitHub API call, and persists the result into PostgreSQL. Every file becomes part of a structured internal graph that can later be analyzed, annotated, and searched.

### 2. Analysis pipeline

A repository analysis request does not block the HTTP request lifecycle. Instead, the backend creates an analysis record and publishes a Kafka job. A consumer then runs the multi-stage pipeline sequentially, carrying context forward between stages.

The current analysis flow is designed around seven stages:

1. Overview extraction from readme/config/bootstrap files.
2. Architecture mapping based on the framework and entry points detected.
3. Module mapping for key files and responsibility boundaries.
4. Data-flow tracing across request, business, and persistence layers.
5. Bug and risk detection from the assembled context.
6. File annotation for repository-wide one-line summaries.
7. Embedding generation for semantic retrieval.

### 3. Live progress streaming

As the job runs, the backend pushes stage progress back to the frontend over Server-Sent Events. This gives the UI a live timeline of what has finished, what is in progress, and when the final result is ready.

### 4. Retrieval-augmented chat

After the repository has been embedded, user questions can be converted into vectors and matched against repository-scoped chunks in Qdrant. The best contextual matches are injected into the prompt layer so the AI can answer based on the analyzed codebase rather than only general reasoning.

## System Architecture

RepoMind is currently built as a containerized monolith with clear domain boundaries, intentionally structured so it can evolve into microservices later if the product demands it. The present architecture keeps delivery and debugging practical while still enforcing production-grade separation of concerns.

The platform runs as a 10-service local stack:

- `repomind-backend`: Spring Boot API and orchestration layer.
- `repomind-frontend`: React dashboard served through Vite in development.
- `repomind-postgres`: primary relational database.
- `repomind-redis`: cache and high-speed support store.
- `repomind-qdrant`: vector store for embeddings and semantic retrieval.
- `repomind-kafka`: asynchronous job backbone for analysis execution.
- `repomind-zookeeper`: Kafka coordination layer.
- `repomind-minio`: S3-compatible object storage.
- `repomind-pgadmin`: operational database UI.
- `repomind-kafka-ui`: operational Kafka inspection UI.

All services run on the shared Docker bridge network `repomind-network`.

## Backend Overview

The backend is built with Java 21 and Spring Boot 4.0.4. It owns authentication, repository ingestion, analysis orchestration, persistence, streaming, and AI integration boundaries.

Important backend design choices:

- PostgreSQL schema is managed through Flyway migrations, not Hibernate schema generation.
- JPA entities are expected to validate against the database schema on startup.
- Security is built around GitHub-authenticated identity, RSA-signed JWT access tokens, and rotated refresh tokens stored as SHA-256 hashes.
- Non-blocking network-heavy operations are expected to use `WebClient`.
- Long-running AI analysis is decoupled from request handling through Kafka.

This keeps the API responsive while still supporting heavy repository processing workflows.

## Frontend Overview

The frontend is built with React 19, Vite 8, React Router 7, Axios, and Zustand. It is responsible for authentication flow handling, dashboard rendering, analysis progress presentation, repository exploration, and chat-driven interaction with analyzed results.

The client state model centers around lightweight global stores:

- authentication state for the current user and access token lifecycle
- analysis state for the active repository and current analysis context

During development, the frontend relies on the Vite dev server and proxies API traffic to the backend so browser-side CORS complexity stays minimal.

## Data Model

RepoMind persists much more than a simple list of repositories. The relational model captures users, refresh tokens, repositories, file trees, file-to-file dependencies, analyses, per-stage analysis results, chat sessions, chat messages, prompt templates, AI call audits, and share links.

This allows the platform to support:

- durable ingestion records
- stage-by-stage analysis tracking
- chat history and context auditing
- future sharing and collaboration features
- operational insight into AI usage and failures

## Security Model

Security is a first-class architectural concern in RepoMind rather than an afterthought.

- GitHub is used as the identity provider for login.
- Access tokens are short-lived JWTs signed with RSA keys.
- Refresh tokens are rotated and stored in hashed form.
- The raw refresh token is intended to be delivered through an `HttpOnly`, `Secure`, `SameSite=Strict` cookie.
- Protected endpoints are validated through the backend security filter chain.

The project's standard is explicit, verifiable, production-grade behavior around token handling, schema integrity, and serialized API output.

## Core Stack

### Application stack

- Backend: Java 21, Spring Boot 4.0.4, Spring Security, Spring Data JPA, WebFlux `WebClient`
- Frontend: React 19, Vite 8, React Router 7, Axios, Zustand
- Database: PostgreSQL 16
- Cache: Redis 7
- Vector store: Qdrant 1.11.0
- Messaging: Kafka 7.6.0 with Zookeeper 7.6.0
- Object storage: MinIO
- Migrations: Flyway
- Container orchestration: Docker Compose

### Key platform capabilities

- GitHub repository ingestion
- staged AI analysis
- live SSE progress updates
- repository-wide file annotation
- vector embeddings and semantic retrieval
- repository-aware chat workflows

## Repository Structure

```text
RepoMind/
|-- backend/                 Spring Boot backend
|-- frontend/                React frontend
|-- docker-compose.yml       Full local service stack
|-- AGENTS.md                Project engineering rules and standards
|-- ProjectReadme.md         Extended internal project documentation
|-- EnvironmentReadme.md     Environment and setup reference
|-- AnalysisApiBlueprint.md  Analysis API design reference
`-- RepoIngestionSetup.md    Repository ingestion reference
```

## Current Direction

RepoMind is being built with an enterprise mindset from the start: strict schema ownership, deterministic persistence, asynchronous processing for long-running workloads, audited AI interactions, and infrastructure that reflects the actual complexity of repository intelligence rather than hiding it behind toy abstractions.

This repository is the foundation for a system that helps developers understand codebases faster, safer, and with much more context than a manual file-by-file walkthrough can realistically provide.
