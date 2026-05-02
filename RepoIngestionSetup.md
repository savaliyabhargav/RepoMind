# 🧠 RepoMind — Repository Ingestion

> **High-performance codebase discovery and mapping engine** built on Spring Boot 4.0, leveraging GitHub's Recursive Trees API to transform remote repositories into a queryable database structure.

---

## 📋 Table of Contents

- [Tech Stack](#tech-stack)
- [System Architecture](#system-architecture)
- [Project Structure](#project-structure)
- [Ingestion Flow](#ingestion-flow)
- [Database Schema](#database-schema)
- [API Reference](#api-reference)
- [Debugging & Known Fixes](#debugging--known-fixes)
- [Roadmap](#roadmap)

---

## Tech Stack

| Layer | Technology |
|---|---|
| Backend | Java 21, Spring Boot 4.0 |
| GitHub Integration | GitHub Trees API (Recursive) |
| Database | PostgreSQL 16 |
| JSON Serialization | Jackson (Auto-snake-to-camel mapping) |
| Containerization | Docker (custom bridge network) |
| HTTP Client | Axios (frontend), WebClient (backend) |

---

## System Architecture

The ingestion engine acts as the **Data Bridge** between GitHub's cloud and RepoMind's persistent storage.

```
Frontend (React)
  │
  ▼
Spring Boot (:8080) ────► GitHub API
  │  (Ingestion Engine)
  ▼
PostgreSQL (:5432)
  │ (repos & file_nodes)
```

> **Why Recursive Trees?** Instead of thousands of separate API calls for each directory, RepoMind makes **one high-impact call** to GitHub to retrieve the entire repository structure in a single JSON payload, drastically reducing ingestion time and API rate limit consumption.

---

## Project Structure

### Backend — `com.repomind.backend`

```
backend/
├── api/repo/
│   └── RepoController.java             # POST /ingest — accepts URL and userId
│
├── domain/repo/
│   ├── Repo.java                       # JPA Entity — root repo metadata
│   ├── FileNode.java                   # JPA Entity — individual file/folder record
│   ├── RepoRepository.java             # Persistence layer for repos
│   └── FileNodeRepository.java         # Batch persistence for file_nodes
│
└── service/ingestion/
    ├── IngestionService.java           # Orchestrates metadata fetch + tree mapping
    ├── GitHubApiClient.java            # WebClient wrapper for GitHub communication
    └── dto/                            # Records for GitHub API response mapping
        ├── GitHubRepoResponse.java
        └── GitHubTreeResponse.java
```

---

## Ingestion Flow

### Phase A — Metadata & Validation
```
1. Frontend sends URL + userId to /api/repo/ingest
2. Backend validates GitHub URL regex
3. Backend fetches repo metadata (default_branch, visibility, size)
4. Repo record created in DB with status: 'INGESTING'
```

### Phase B — Tree Mapping
```
5. Backend calls GitHub Trees API with recursive=true
6. Every entry (file/folder) is converted to a FileNode entity
7. Language detection applied based on extension (e.g., .java → Java)
8. Directory depth calculated for UI tree rendering
```

### Phase C — Batch Persistence
```
9.  Bulk insert of all FileNodes into PostgreSQL (High-performance batch)
10. Repo status updated to 'READY'
11. Final fileCount and sizeKb recorded
12. Complete Repo object returned to Frontend
```

---

## Database Schema

### `repos` table
| Column | Type | Description |
|---|---|---|
| `id` | UUID | Primary Key |
| `user_id` | UUID | Foreign Key to `users` |
| `name` | String | e.g., "spring-petclinic" |
| `owner` | String | e.g., "spring-projects" |
| `status` | String | `PENDING`, `INGESTING`, `READY`, `FAILED` |
| `private` | Boolean | Visibility status |

### `file_nodes` table
| Column | Type | Description |
|---|---|---|
| `id` | UUID | Primary Key |
| `repo_id` | UUID | Foreign Key to `repos` |
| `path` | String | Full repo path (e.g., "src/main/App.java") |
| `type` | String | `FILE` or `DIRECTORY` |
| `language` | String | Detected programming language |

---

## API Reference

### POST `/api/repo/ingest`

**Request Body:**
```json
{
    "url": "https://github.com/spring-projects/spring-petclinic",
    "userId": "9cf51bf0-9255-47ed-acc1-0d148329a336"
}
```

**Response Body (200 OK):**
```json
{
    "id": "9ce6bac3-4d59-4d7c-9c3a-b93e3c304061",
    "user": {
        "id": "9cf51bf0-9255-47ed-acc1-0d148329a336",
        "username": "savaliyabhargav",
        "avatarUrl": "https://avatars.githubusercontent.com/u/73011177?v=4",
        "plan": "FREE"
    },
    "url": "https://github.com/spring-projects/spring-petclinic",
    "name": "spring-petclinic",
    "owner": "spring-projects",
    "provider": "GITHUB",
    "defaultBranch": "main",
    "fileCount": 176,
    "sizeKb": 10865,
    "status": "READY",
    "private": false,
    "createdAt": "2026-04-14T05:25:16.509999Z"
}
```

---

## Debugging & Known Fixes

### Boolean Field Serialization (`isPrivate` vs `private`)
**Problem:** Jackson by default serializes `isPrivate` as `private`.
**Solution:** Ensure Frontend uses `private` in mapping and Backend documentation reflects the serialized JSON field name, not just the Java variable name.

### 403 Forbidden — GitHub Rate Limits
**Cause:** Fetching large trees without a token or exceeding 5000 requests/hr.
**Fix:** Ensure the `github_token` stored in the `User` entity is used in the `GitHubApiClient` headers for all ingestion requests.

---

## Roadmap

- [ ] **SSE Progress Updates** — Stream ingestion progress (e.g., "Fetched 500/2000 files") to the Frontend.
- [ ] **Scope Filtering** — Allow users to exclude `node_modules` or `dist` before saving FileNodes.
- [ ] **Webhook Integration** — Automatically re-ingest on repository `push` events.
- [ ] **Binary Detection** — Filter out `.png`, `.jpg`, and `.exe` from AI analysis scope during ingestion.

---

*Stack: Java 21 · Spring Boot 4.0 · React 18 · Vite · PostgreSQL 16 · Docker*
*Engine: GitHub Recursive Trees API*
