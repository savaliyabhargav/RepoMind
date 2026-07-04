import { useEffect, useCallback } from "react";
import { useNavigate } from "react-router-dom";
import { useAuthStore } from "../store/authStore";
import "./LandingScreen.css";

const GITHUB_CLIENT_ID = import.meta.env.VITE_GITHUB_CLIENT_ID;
const GITHUB_REDIRECT_URI = import.meta.env.VITE_GITHUB_REDIRECT_URI;
const GITHUB_SCOPE = import.meta.env.VITE_AUTH_SCOPE || "user:email repo";

function generateState(length = 32) {
  const chars =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
  return Array.from(crypto.getRandomValues(new Uint8Array(length)))
    .map((b) => chars[b % chars.length])
    .join("");
}

export default function LandingScreen() {
  const navigate = useNavigate();
  const isLoggedIn = useAuthStore((s) => s.isLoggedIn);

  useEffect(() => {
    if (isLoggedIn) navigate("/analyze", { replace: true });
  }, [isLoggedIn, navigate]);

  const handleLogin = useCallback(() => {
    if (!GITHUB_CLIENT_ID) {
      console.error("VITE_GITHUB_CLIENT_ID is not set in .env");
      return;
    }

    const state = generateState();
    localStorage.setItem("github_oauth_state", state);

    const params = new URLSearchParams({
      client_id: GITHUB_CLIENT_ID,
      redirect_uri: GITHUB_REDIRECT_URI,
      scope: GITHUB_SCOPE,
      state,
      response_type: "code",
    });

    window.location.href = `https://github.com/login/oauth/authorize?${params}`;
  }, []);

  return (
    <div className="login">
      <main className="login-card">
        <div className="login-logo">RM</div>

        <h1 className="login-title">Sign in to RepoMind</h1>
        <p className="login-sub">
          Turn any GitHub repository into clear, AI-generated understanding.
        </p>

        <button className="login-btn" onClick={handleLogin}>
          <svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
            <path d="M12 0C5.374 0 0 5.373 0 12c0 5.302 3.438 9.8 8.207 11.387.599.111.793-.261.793-.577v-2.234c-3.338.726-4.033-1.416-4.033-1.416-.546-1.387-1.333-1.756-1.333-1.756-1.089-.745.083-.729.083-.729 1.205.084 1.839 1.237 1.839 1.237 1.07 1.834 2.807 1.304 3.492.997.107-.775.418-1.305.762-1.604-2.665-.305-5.467-1.334-5.467-5.931 0-1.311.469-2.381 1.236-3.221-.124-.303-.535-1.524.117-3.176 0 0 1.008-.322 3.301 1.23A11.509 11.509 0 0112 5.803c1.02.005 2.047.138 3.006.404 2.291-1.552 3.297-1.23 3.297-1.23.653 1.653.242 2.874.118 3.176.77.84 1.235 1.911 1.235 3.221 0 4.609-2.807 5.624-5.479 5.921.43.372.823 1.102.823 2.222v3.293c0 .319.192.694.801.576C20.566 21.797 24 17.3 24 12c0-6.627-5.373-12-12-12z" />
          </svg>
          Continue with GitHub
        </button>

        <p className="login-fine">
          We only request read-only access. Your code is never stored.
        </p>
      </main>
    </div>
  );
}
