import axios from "axios";
import { useAuthStore } from "../store/authStore";

/**
 * ─── Axios Instance ──────────────────────────────────────────────────────────
 * * We use '/api' as the baseURL so that it hits the Vite Proxy.
 * Vite then forwards it to http://backend:8080/api inside Docker.
 */
const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || "/api",
  withCredentials: true,   // Required for HttpOnly refresh-token cookies
  headers: {
    "Content-Type": "application/json",
  },
});

/**
 * ─── Request Interceptor ─────────────────────────────────────────────────────
 * Attaches the JWT from Zustand store to every outgoing request.
 */
api.interceptors.request.use(
  (config) => {
    const token = useAuthStore.getState().accessToken;
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  },
  (error) => Promise.reject(error)
);

/**
 * ─── Response Interceptor ────────────────────────────────────────────────────
 * Handles 401 errors by attempting to refresh the token using the refresh cookie.
 */
let isRefreshing = false;
let pendingQueue = [];

const processQueue = (error, token = null) => {
  pendingQueue.forEach((p) => (error ? p.reject(error) : p.resolve(token)));
  pendingQueue = [];
};

api.interceptors.response.use(
  (response) => response,
  async (error) => {
    const originalRequest = error.config;

    if (error.response?.status === 401 && !originalRequest._retry) {
      if (isRefreshing) {
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

/**
 * ─── Auth Service ────────────────────────────────────────────────────────────
 */
const authService = {
  /**
   * exchangeCode(code)
   * Sends GitHub code to Backend. 
   * Maps 'accessToken' from backend to 'token' for the frontend store.
   */
  exchangeCode: async (code) => {
    const response = await api.post("/auth/github", { code });
    
    // BACKEND LOGIC: The backend returns { accessToken, user }
    // We map it here so the AuthStore gets exactly what it needs.
    const { accessToken, user } = response.data;
    
    // Update the Zustand store immediately
    useAuthStore.getState().login(accessToken, user);
    
    return { token: accessToken, user };
  },

  /**
   * refreshToken()
   * Hits /auth/refresh. Backend reads the HttpOnly cookie.
   */
  refreshToken: async () => {
    const response = await api.post("/auth/refresh");
    const newToken = response.data.accessToken; // Match backend naming

    const currentUser = useAuthStore.getState().user;
    useAuthStore.getState().login(newToken, currentUser);

    return newToken;
  },

  /**
   * logoutApi()
   */
  logoutApi: async () => {
    try {
      await api.post("/auth/logout");
    } catch {
      // Fail silently on network errors
    } finally {
      useAuthStore.getState().logout();
    }
  },
};

export default authService;
export { api };
