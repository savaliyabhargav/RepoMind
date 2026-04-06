# 🧠 RepoMind

> **Enterprise-grade GitHub repository intelligence platform** built on Spring Boot 4.0 and React 18, featuring RSA-256 asymmetric JWT authentication with OAuth2.

---

## 📋 Table of Contents

- [Tech Stack](#tech-stack)
- [System Architecture](#system-architecture)
- [Project Structure](#project-structure)
- [Authentication Flow](#authentication-flow)
- [Frontend State Management](#frontend-state-management)
- [Getting Started](#getting-started)
- [API Reference](#api-reference)
- [Debugging & Known Fixes](#debugging--known-fixes)
- [Roadmap](#roadmap)

---

## Tech Stack

| Layer | Technology |
|---|---|
| Backend | Java 21, Spring Boot 4.0 |
| Frontend | React 18, Vite |
| Database | PostgreSQL 16 |
| Auth | RS256 JWT + HttpOnly Cookies + OAuth2 |
| Containerization | Docker (custom bridge network) |
| DB Management | pgAdmin 4 |
| HTTP Client | Axios (frontend), WebClient (backend) |
| State Management | Zustand |

---

## System Architecture

RepoMind runs as a **containerized micro-stack** of four networked services on a custom Docker bridge network (`repomind-network`).

```
Browser
  │
  ▼
┌─────────────────────────┐
│  Vite / React  :5173    │  ← UI Gateway
│  (Reverse Proxy /api)   │
└──────────┬──────────────┘
           │ transparently proxies /api → backend:8080
           ▼
┌─────────────────────────┐
│  Spring Boot  :8080     │  ← Intelligence Layer
└──────────┬──────────────┘
           │
           ▼
┌─────────────────────────┐
│  PostgreSQL  :5432      │  ← Persistent Storage
└─────────────────────────┘

pgAdmin available for real-time DB inspection
```

> **Why Vite Reverse Proxy?** The browser believes the backend lives at `localhost:5173/api`. Vite silently forwards those requests to `backend:8080/api`. This eliminates 90% of CORS issues before they occur.

---

## Project Structure

### Backend — `com.repomind.backend`

```
backend/
├── api/
│   ├── auth/AuthController.java        # POST /github, POST /refresh — sets HttpOnly cookie
│   └── health/HealthController.java    # Health check endpoint
│
├── config/
│   ├── RsaKeyConfig.java               # Loads .pem files → Java PublicKey / PrivateKey
│   ├── SecurityConfig.java             # Route guards, CORS, CSRF config
│   └── WebClientConfig.java            # Non-blocking WebClient for GitHub API calls
│
├── domain/user/
│   ├── User.java                       # JPA entity: github_token (encrypted), avatar_url, Plan
│   ├── Plan.java                       # Enum: FREE | PRO | ...
│   ├── RefreshToken.java               # Long-lived tokens stored in DB, linked to User
│   ├── UserRepository.java             # Spring Data interface → Postgres
│   └── RefreshTokenRepository.java     # Spring Data interface → Postgres
│
├── security/
│   └── JwtAuthFilter.java              # Intercepts every request, validates RS256 Bearer token
│
└── service/auth/
    ├── AuthService.java                # Orchestrates code exchange → DB save → JWT issue
    ├── GitHubService.java              # Handles GitHub API token exchange and profile fetch
    └── JwtService.java                 # JWT creation and Claims extraction
```

### Cryptographic Keys — `resources/certs/`

| File | Purpose | Visibility |
|---|---|---|
| `private_key.pem` | Signs JWT tokens | **Never share / commit** |
| `public_key.pem` | Verifies JWT tokens | Can be distributed |

### Frontend

```
frontend/
├── services/
│   ├── authService.js      # Axios engine — request/response interceptors
│   └── ...
├── store/
│   └── authStore.js        # Zustand store — user, token, isAuthenticated
└── pages/
    └── LoginCallback.jsx   # Captures GitHub ?code=XYZ and triggers exchange
```

---

## Authentication Flow

### Phase A — GitHub Handshake

```
1. User clicks "Login with GitHub"
2. React redirects → GitHub OAuth page
3. GitHub redirects back → localhost:5173/auth/callback?code=XYZ
4. LoginCallback.jsx captures XYZ → calls authService.exchangeCode(XYZ)
```

### Phase B — Token Exchange (Backend)

```
5. Backend sends XYZ + ClientSecret → GitHub
6. GitHub validates → returns github_access_token
7. Backend calls api.github.com/user → fetches profile + email
8. DB check:
     User exists?  → update avatar_url and github_token
     New user?     → create record
```

### Phase C — Issuing RepoMind Credentials

```
9.  Access Token  → JWT signed with RSA Private Key, returned in JSON response
10. Refresh Token → UUID generated, saved to DB
11. Cookie        → UUID placed in HttpOnly + Secure cookie
                    (JS cannot read it — XSS-safe "Remember Me")
```

---

## Frontend State Management

### `authStore.js` (Zustand)

| State Key | Type | Storage |
|---|---|---|
| `user` | Object | `localStorage` (persisted) |
| `token` | String | Memory only |
| `isAuthenticated` | Boolean | Derived |

### `authService.js` (Axios)

**Request Interceptor** — Automatically injects `Authorization: Bearer <token>` into every outbound request.

**Response Interceptor (Silent Re-authenticator)** — If any request returns `401 Unauthorized`:
1. Automatically calls `/auth/refresh` using the HttpOnly cookie
2. Updates the Zustand store with the new token
3. Retries the original failed request — the user never notices

---

## Getting Started

### Prerequisites

- Docker & Docker Compose
- Java 21
- Node.js 18+

### 1. Generate RSA Keys

```bash
# Generate private key
openssl genrsa -out src/main/resources/certs/private_key.pem 2048

# Extract public key
openssl rsa -in src/main/resources/certs/private_key.pem \
            -pubout -out src/main/resources/certs/public_key.pem
```

### 2. Configure Environment

Set the following in your `application.yml` or environment:

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          github:
            client-id: YOUR_GITHUB_CLIENT_ID
            client-secret: YOUR_GITHUB_CLIENT_SECRET
```

### 3. Start the Stack

```bash
docker-compose up --build
```

### 4. Verify Everything Is Running

Visit `http://localhost:5173/api/health`

Expected response:
```json
{ "status": "UP" }
```

A `200 OK` here confirms the Vite proxy, Spring Boot backend, and PostgreSQL are all communicating correctly.

### 5. Inspect the Database (pgAdmin)

1. Open pgAdmin and connect using service name `postgres`
2. Navigate to **Databases → repomind → Schemas → public → Tables**
3. Right-click `users` → **View/Edit Data**
4. Confirm your GitHub profile info and hashed credentials appear after login

---

## API Reference

| Method | Endpoint | Auth Required | Description |
|---|---|---|---|
| `POST` | `/api/auth/github` | No | Exchanges GitHub OAuth code for RepoMind JWTs |
| `POST` | `/api/auth/refresh` | Cookie | Issues a new access token using refresh cookie |
| `GET` | `/api/health` | No | Returns backend status |

---

## Debugging & Known Fixes

### 403 Forbidden — CSRF/CORS
**Cause:** Spring Security 7+ enables CSRF by default for all `POST` requests. Stateless JWT apps don't use CSRF tokens.

**Fix:** Explicitly disable CSRF in `SecurityConfig.java` and configure CORS to allow `http://localhost:5173` with `AllowCredentials(true)`.

---

### 404 Not Found — Double Context Path
**Cause:** `application.yml` had `context-path: /api` AND the controller used `@RequestMapping("/api/auth")`, resulting in the backend listening at `/api/api/auth`.

**Fix:** Remove the `/api` prefix from the controller mapping so the final URL resolves to `/api/auth/github`.

---

### 502 Bad Gateway — Filter Bean Conflict
**Cause:** `JwtAuthFilter` was registered twice (once by `@Component` scanning, once by Spring Security). Removing `@Component` caused an `UnsatisfiedDependencyException`.

**Fix:** Remove `@Component` from `JwtAuthFilter` and manually declare it as a `@Bean` inside `SecurityConfig.java`.

---

## Roadmap

- [ ] **Security Hardening** — Switch protected routes from `permitAll()` to `.authenticated()` to fully exercise `JwtAuthFilter`
- [ ] **Repository Fetching** — Build `RepositoryService` to use the saved `github_token` and call GitHub's repos API
- [ ] **Dashboard UI** — Repository grid on the Overview page
- [ ] **Plan Gating** — Enforce FREE vs PRO feature access using the `Plan` enum

---

## Security Notes

- The `private_key.pem` file **must never be committed to version control**. Add `src/main/resources/certs/` to `.gitignore`.
- The refresh token UUID is stored in an `HttpOnly` cookie — JavaScript cannot access it, protecting against XSS.
- The `github_token` is stored encrypted in the database.

---

*Stack: Java 21 · Spring Boot 4.0 · React 18 · Vite · PostgreSQL 16 · Docker*
*Auth: RS256 JWT + HttpOnly Cookies + GitHub OAuth2*
