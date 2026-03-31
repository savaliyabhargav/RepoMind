import { create } from "zustand";
import { persist, createJSONStorage } from "zustand/middleware";

// ─── Auth Store ──────────────────────────────────────────────────────────────
//
//  Manages:
//    • accessToken  — the RS256 JWT returned by the backend
//    • user         — { username, avatarUrl, email, plan, createdAt }
//    • isLoggedIn   — derived boolean, true when a token is present
//
//  Persisted to sessionStorage so the session survives page refreshes
//  but is automatically cleared when the browser tab is closed.
//  (The HttpOnly refresh-token cookie handles long-term re-auth.)
//
// ─────────────────────────────────────────────────────────────────────────────

export const useAuthStore = create(
  persist(
    (set, get) => ({
      // ── State ──────────────────────────────────────────────────────────────
      accessToken: null,
      user: null,
      isLoggedIn: false,

      // ── Actions ────────────────────────────────────────────────────────────

      /**
       * login(token, user)
       * Called by LoginCallback after the backend returns a JWT.
       *
       * @param {string} token   - The RS256 JWT string
       * @param {object} user    - { username, avatarUrl, email, plan, createdAt }
       */
      login: (token, user) => {
        set({
          accessToken: token,
          user: {
            username: user.username ?? null,
            avatarUrl: user.avatarUrl ?? null,
            email: user.email ?? null,
            plan: user.plan ?? "FREE",
            createdAt: user.createdAt ?? null,
          },
          isLoggedIn: true,
        });
      },

      /**
       * logout()
       * Clears all auth state. The caller is also responsible for
       * calling authService.logoutApi() to invalidate the refresh-token cookie.
       */
      logout: () => {
        set({
          accessToken: null,
          user: null,
          isLoggedIn: false,
        });
      },

      /**
       * checkAuth()
       * Returns true if a token currently exists in the store.
       * Useful for guards that run before any async calls.
       */
      checkAuth: () => {
        return !!get().accessToken;
      },

      /**
       * getToken()
       * Helper used by authService / axios interceptor to attach
       * the Bearer token to outgoing API requests.
       */
      getToken: () => get().accessToken,

      /**
       * updateUser(partial)
       * Merges updated user fields into the store (e.g. after a plan upgrade).
       *
       * @param {Partial<user>} partial
       */
      updateUser: (partial) => {
        set((state) => ({
          user: state.user ? { ...state.user, ...partial } : state.user,
        }));
      },
    }),

    // ── Persist config ──────────────────────────────────────────────────────
    {
      name: "repomind-auth",                    // sessionStorage key
      storage: createJSONStorage(() => sessionStorage),

      // Only persist these fields — never store anything sensitive beyond the token
      partialize: (state) => ({
        accessToken: state.accessToken,
        user: state.user,
        isLoggedIn: state.isLoggedIn,
      }),
    }
  )
);