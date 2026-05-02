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
    <div className="landing-root">
      <div className="landing-backdrop" aria-hidden="true" />
      <div className="landing-noise" aria-hidden="true" />

      <header className="landing-topbar">
        <div className="landing-brand">
          <span className="landing-brand-mark">RM</span>
          <div>
            <strong>RepoMind</strong>
            <span>AI repository intelligence</span>
          </div>
        </div>

        <div className="landing-topbar-meta">
          <span>Enterprise-ready architecture</span>
          <span>GitHub auth + Spring backend</span>
        </div>
      </header>

      <main className="landing-shell">
        <section className="landing-primary">
          <div className="landing-announcement">
            <span className="landing-announcement-dot" />
            Built for repository ingestion, analysis pipelines, and AI workflows
          </div>

          <h1 className="landing-title">
            A serious interface for
            <span> understanding complex codebases</span>
          </h1>

          <p className="landing-description">
            RepoMind transforms GitHub repositories into structured intelligence with
            architecture context, file mapping, contributor visibility, and analysis-ready
            repository data.
          </p>

          <div className="landing-actions">
            <button
              className="landing-signin"
              onClick={handleLogin}
              aria-label="Sign in with GitHub"
            >
              <svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                <path d="M12 0C5.374 0 0 5.373 0 12c0 5.302 3.438 9.8 8.207 11.387.599.111.793-.261.793-.577v-2.234c-3.338.726-4.033-1.416-4.033-1.416-.546-1.387-1.333-1.756-1.333-1.756-1.089-.745.083-.729.083-.729 1.205.084 1.839 1.237 1.839 1.237 1.07 1.834 2.807 1.304 3.492.997.107-.775.418-1.305.762-1.604-2.665-.305-5.467-1.334-5.467-5.931 0-1.311.469-2.381 1.236-3.221-.124-.303-.535-1.524.117-3.176 0 0 1.008-.322 3.301 1.23A11.509 11.509 0 0112 5.803c1.02.005 2.047.138 3.006.404 2.291-1.552 3.297-1.23 3.297-1.23.653 1.653.242 2.874.118 3.176.77.84 1.235 1.911 1.235 3.221 0 4.609-2.807 5.624-5.479 5.921.43.372.823 1.102.823 2.222v3.293c0 .319.192.694.801.576C20.566 21.797 24 17.3 24 12c0-6.627-5.373-12-12-12z" />
              </svg>
              Continue with GitHub
            </button>

            <p className="landing-signin-note">
              Secure sign-in. Existing auth and backend flow remain unchanged.
            </p>
          </div>

          <div className="landing-metrics" role="list" aria-label="Platform metrics">
            {[
              { value: "7-stage", label: "analysis pipeline" },
              { value: "RS256", label: "token security" },
              { value: "Qdrant", label: "vector search" },
            ].map((metric) => (
              <div key={metric.label} className="landing-metric" role="listitem">
                <strong>{metric.value}</strong>
                <span>{metric.label}</span>
              </div>
            ))}
          </div>
        </section>

        <aside className="landing-secondary">
          <section className="landing-panel landing-preview">
            <div className="landing-panel-header">
              <span>Workflow preview</span>
              <span className="landing-status">Live product direction</span>
            </div>

            <div className="landing-preview-window">
              <div className="landing-preview-toolbar">
                <span />
                <span />
                <span />
              </div>

              <div className="landing-preview-layout">
                <div className="landing-preview-sidebar">
                  <div className="landing-preview-chip">Repo source</div>
                  <div className="landing-preview-line short" />
                  <div className="landing-preview-line" />
                  <div className="landing-preview-line medium" />
                </div>

                <div className="landing-preview-main">
                  <div className="landing-preview-card hero">
                    <small>Ingestion status</small>
                    <strong>Repository mapped and ready for analysis</strong>
                    <div className="landing-preview-bars">
                      <span />
                      <span />
                      <span />
                    </div>
                  </div>

                  <div className="landing-preview-grid">
                    <div className="landing-preview-card">
                      <small>Overview</small>
                      <strong>Architecture summary</strong>
                    </div>
                    <div className="landing-preview-card">
                      <small>Signals</small>
                      <strong>Code health and patterns</strong>
                    </div>
                    <div className="landing-preview-card">
                      <small>Context</small>
                      <strong>Team and dependency insight</strong>
                    </div>
                    <div className="landing-preview-card">
                      <small>RAG</small>
                      <strong>Ask questions against the repo</strong>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </section>

          <section className="landing-panel landing-capabilities">
            <div className="landing-panel-header">
              <span>Current capabilities</span>
              <span>Now</span>
            </div>

            <ul className="landing-capability-list">
              {[
                "Repository ingestion from GitHub",
                "Structured file-tree mapping",
                "AI-oriented analysis preparation",
                "Professional dashboard foundation for the full product",
              ].map((item) => (
                <li key={item}>
                  <span className="landing-capability-icon" aria-hidden="true" />
                  {item}
                </li>
              ))}
            </ul>
          </section>
        </aside>
      </main>
    </div>
  );
}
