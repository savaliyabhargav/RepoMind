import { create } from "zustand";
import { persist, createJSONStorage } from "zustand/middleware";

export const useAuthStore = create(
  persist(
    (set, get) => ({
      // ── State ──────────────────────────────────────────────────────────────
      accessToken: null,
      user: null,
      isLoggedIn: false,

      // ── Actions ────────────────────────────────────────────────────────────

      login: (token, user) => {
        set({
          accessToken: token,
          user: {
            id:        user.id        ?? null,   // ← PostgreSQL UUID (needed for ingestion API)
            username:  user.username  ?? null,
            avatarUrl: user.avatarUrl ?? null,
            email:     user.email     ?? null,
            plan:      user.plan      ?? "FREE",
            createdAt: user.createdAt ?? null,
          },
          isLoggedIn: true,
        });
      },

      logout: () => {
        set({ accessToken: null, user: null, isLoggedIn: false });
      },

      checkAuth: () => !!get().accessToken,

      getToken: () => get().accessToken,

      updateUser: (partial) => {
        set((state) => ({
          user: state.user ? { ...state.user, ...partial } : state.user,
        }));
      },
    }),

    {
      name: "repomind-auth",
      storage: createJSONStorage(() => sessionStorage),
      partialize: (state) => ({
        accessToken: state.accessToken,
        user:        state.user,
        isLoggedIn:  state.isLoggedIn,
      }),
    }
  )
);