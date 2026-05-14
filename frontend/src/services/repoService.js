import { api } from "./authService";

// ─── Repo Service ─────────────────────────────────────────────────────────────
//
//  All repository-related API calls.
//  Uses the same axios instance from authService so the Bearer token
//  is automatically attached to every request.
//
// ─────────────────────────────────────────────────────────────────────────────

const repoService = {
  /**
   * ingestRepo(url, userId)
   *
   * Sends a repo URL to the backend for ingestion.
   * Backend fetches metadata + maps all FileNodes to the database.
   *
   * @param   {string} url    - Full GitHub repo URL e.g. https://github.com/owner/repo
   * @param   {string} userId - The logged-in user's PostgreSQL UUID from authStore
   * @returns {object}        - The full Repo object (see shape below)
   *
   * Request:
   *   POST /api/repo/ingest
   *   { url: string, userId: string }
   *
   * Response (200 OK):
   *   {
   *     id:            "9ce6bac3-...",       ← Repo UUID — use for all future calls
   *     user:          { id, username, avatarUrl, plan },
   *     url:           "https://github.com/...",
   *     name:          "spring-petclinic",
   *     owner:         "spring-projects",
   *     provider:      "GITHUB",
   *     defaultBranch: "main",
   *     fileCount:     176,
   *     sizeKb:        10865,
   *     status:        "READY",
   *     private:       false,
   *     createdAt:     "2026-04-14T05:25:16.509999Z"
   *   }
   */
  ingestRepo: async (url, userId) => {
    const response = await api.post("/repo/ingest", { url, userId });
    return response.data;
  },

  getRepo: async (repoId) => {
    const response = await api.get(`/repo/${repoId}`);
    return response.data;
  },

  getRepoTree: async (repoId) => {
    const response = await api.get(`/repo/${repoId}/tree`);
    return response.data;
  },

  startAnalysis: async ({ repoId, userId, aiProvider = "NVIDIA_DEV" }) => {
    const response = await api.post("/analyses", { repoId, userId, aiProvider });
    return response.data;
  },

  getAnalysisStages: async (analysisId) => {
    const response = await api.get(`/analyses/${analysisId}/stages`);
    return response.data;
  },

  indexRepoVectors: async ({ repoId, userId, aiProvider = "NVIDIA_DEV" }) => {
    const response = await api.post("/retrieval/index", { repoId, userId, aiProvider });
    return response.data;
  },

  searchRepoVectors: async ({ repoId, query, topK = 8, aiProvider = "NVIDIA_DEV" }) => {
    const response = await api.post("/retrieval/search", { repoId, query, topK, aiProvider });
    return response.data;
  },
};

export default repoService;
