import axios from "axios";
import { useAuthStore } from "../store/authStore";

// ─── Axios Instance ──────────────────────────────────────────────────────────
//
//  All requests go through this instance so we get:
//    • Automatic base URL from .env
//    • Automatic Bearer token on every request
//    • Automatic token refresh on 401
//    • Consistent error handling
//
// ─────────────────────────────────────────────────────────────────────────────

const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || "http://localhost:8080/api",
  withCredentials: true,   // Required: sends the HttpOnly refresh-token cookie
  headers: {
    "Content-Type": "application/json",
  },
});

// ─── Request Interceptor ─────────────────────────────────────────────────────
// Attaches the JWT as a Bearer token to every outgoing request automatically.
// ─────────────────────────────────────────────────────────────────────────────

api.interceptors.request.use(
  (config) => {
    const token = useAuthStore.getState().getToken();
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  },
  (error) => Promise.reject(error)
);

// ─── Response Interceptor ────────────────────────────────────────────────────
// If any request gets a 401 (token expired), automatically try to refresh.
// If refresh also fails, log the user out cleanly.
// ─────────────────────────────────────────────────────────────────────────────

let isRefreshing = false;         // prevents multiple simultaneous refresh calls
let pendingQueue = [];            // queues failed requests while refreshing

const processQueue = (error, token = null) => {
  pendingQueue.forEach((p) => (error ? p.reject(error) : p.resolve(token)));
  pendingQueue = [];
};

api.interceptors.response.use(
  (response) => response,
  async (error) => {
    const originalRequest = error.config;

    // Only handle 401 and only retry once
    if (error.response?.status === 401 && !originalRequest._retry) {
      if (isRefreshing) {
        // Queue this request until the refresh completes
        return new Promise((resolve, reject) => {
          pendingQueue.push({ resolve, reject });
        })
          .then((token) => {
            originalRequest.headers.Authorization = `Bearer ${token}`;
            return api(originalRequest);
          })
          .catch((err) => Promise.reject(err));
      }

      originalRequest._retry = true;
      isRefreshing = true;

      try {
        const newToken = await authService.refreshToken();
        processQueue(null, newToken);
        originalRequest.headers.Authorization = `Bearer ${newToken}`;
        return api(originalRequest);
      } catch (refreshError) {
        processQueue(refreshError, null);
        // Refresh failed — force logout
        useAuthStore.getState().logout();
        window.location.href = "/";
        return Promise.reject(refreshError);
      } finally {
        isRefreshing = false;
      }
    }

    return Promise.reject(error);
  }
);

// ─── Auth Service ────────────────────────────────────────────────────────────

const authService = {
  /**
   * exchangeCode(code)
   *
   * Step 5 → 7 of the OAuth flow.
   * Sends the GitHub code to the backend, which exchanges it for a
   * GitHub access token, fetches user info, and returns a signed JWT.
   *
   * @param   {string} code  - The ?code= param from GitHub's redirect
   * @returns {object}       - { token: string, user: object }
   */
  exchangeCode: async (code) => {
    const response = await api.post("/auth/github", { code });
    return response.data;
    // Expected response shape from Spring Boot:
    // {
    //   token: "eyJhbGci...",   ← RS256 JWT
    //   user: {
    //     username:  "octocat",
    //     avatarUrl: "https://avatars.githubusercontent.com/...",
    //     email:     "octocat@github.com",
    //     plan:      "FREE",
    //     createdAt: "2025-01-01T00:00:00Z"
    //   }
    // }
  },

  /**
   * refreshToken()
   *
   * Sends a request to the backend using the HttpOnly refresh-token cookie.
   * The cookie is sent automatically because of `withCredentials: true`.
   * Returns a new JWT string on success.
   *
   * @returns {string} newToken - Fresh RS256 JWT
   */
  refreshToken: async () => {
    const response = await api.post("/auth/refresh");
    const newToken = response.data.token;

    // Update the store with the new token while keeping user info intact
    const currentUser = useAuthStore.getState().user;
    useAuthStore.getState().login(newToken, currentUser);

    return newToken;
  },

  /**
   * logoutApi()
   *
   * Tells the backend to invalidate the refresh-token cookie server-side.
   * Always call this alongside authStore.logout() for a clean session end.
   * Silently ignores errors (e.g. if the token is already expired).
   */
  logoutApi: async () => {
    try {
      await api.post("/auth/logout");
    } catch {
      // Ignore — we're logging out regardless
    } finally {
      useAuthStore.getState().logout();
    }
  },
};

export default authService;
export { api };   // export the axios instance for other services to reuse