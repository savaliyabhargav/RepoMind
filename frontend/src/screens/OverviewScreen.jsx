import { useState, useCallback } from "react";
import { useNavigate } from "react-router-dom";
import { useAuthStore } from "../store/authStore";
import authService from "../services/authService";
import repoService from "../services/repoService";
import "./OverviewScreen.css";

const INGEST = {
  IDLE: "idle",
  LOADING: "loading",
  SUCCESS: "success",
  ERROR: "error",
};

function isValidGithubUrl(url) {
  try {
    const u = new URL(url);
    return u.hostname === "github.com" && u.pathname.split("/").filter(Boolean).length >= 2;
  } catch {
    return false;
  }
}

export default function OverviewScreen() {
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);

  const [repoUrl, setRepoUrl] = useState("");
  const [ingest, setIngest] = useState(INGEST.IDLE);
  const [errorMsg, setErrorMsg] = useState("");
  const [repoData, setRepoData] = useState(null);

  const joinedDate = user?.createdAt
    ? new Date(user.createdAt).toLocaleDateString("en-GB", {
        day: "numeric",
        month: "short",
        year: "numeric",
      })
    : "-";

  const handleLogout = useCallback(async () => {
    await authService.logoutApi();
    navigate("/", { replace: true });
  }, [navigate]);

  const handleIngest = useCallback(async () => {
    if (!repoUrl.trim()) return;

    if (!isValidGithubUrl(repoUrl.trim())) {
      setErrorMsg("Enter a valid GitHub URL - e.g. https://github.com/owner/repo");
      setIngest(INGEST.ERROR);
      return;
    }

    setIngest(INGEST.LOADING);
    setErrorMsg("");
    setRepoData(null);

    try {
      const data = await repoService.ingestRepo(repoUrl.trim(), user.id);
      setRepoData(data);
      setIngest(INGEST.SUCCESS);
      setTimeout(() => navigate(`/analyze/repo/${data.id}`), 1200);
    } catch (err) {
      console.error("[OverviewScreen] Ingest failed:", err);
      setErrorMsg(err.response?.data?.message || "Failed to ingest repository. Please try again.");
      setIngest(INGEST.ERROR);
    }
  }, [repoUrl, user, navigate]);

  return (
    <div className="overview-root">
      <div className="overview-backdrop" aria-hidden="true" />
      <div className="overview-grid" aria-hidden="true" />

      <aside className="overview-sidebar">
        <div className="overview-sidebar-brand">
          <div className="overview-sidebar-mark">RM</div>
          <div>
            <strong>RepoMind</strong>
            <span>Mission workspace</span>
          </div>
        </div>

        <div className="overview-sidebar-section">
          <p className="overview-section-label">Operator</p>
          <div className="overview-profile-card">
            {user?.avatarUrl ? (
              <img src={user.avatarUrl} alt={`${user.username} avatar`} className="overview-avatar" />
            ) : (
              <div className="overview-avatar overview-avatar-fallback">
                {user?.username?.[0]?.toUpperCase() ?? "?"}
              </div>
            )}
            <strong>{user?.username ?? "-"}</strong>
            {user?.email && <span>{user.email}</span>}
          </div>
        </div>

        <div className="overview-sidebar-section">
          <p className="overview-section-label">Workspace facts</p>
          <div className="overview-sidebar-list">
            {[
              ["Plan", user?.plan ?? "FREE"],
              ["Joined", joinedDate],
              ["Provider", "GITHUB"],
              ["Status", "ACTIVE"],
            ].map(([label, value]) => (
              <div key={label} className="overview-sidebar-item">
                <span>{label}</span>
                <strong>{value}</strong>
              </div>
            ))}
          </div>
        </div>

        <div className="overview-sidebar-section">
          <p className="overview-section-label">Stack</p>
          <div className="overview-chip-list">
            {["React 19", "Spring Boot", "PostgreSQL", "Redis", "Kafka", "Qdrant"].map((item) => (
              <span key={item}>{item}</span>
            ))}
          </div>
        </div>
      </aside>

      <div className="overview-main">
        <header className="overview-topbar">
          <div>
            <p className="overview-kicker">Repository ingestion</p>
            <h1>Analyze a repository without changing the pipeline underneath</h1>
          </div>

          <div className="overview-topbar-actions">
            <div className="overview-status-badge">
              <span className="overview-status-dot" />
              Systems nominal
            </div>
            <button className="overview-logout" onClick={handleLogout}>
              Logout
            </button>
          </div>
        </header>

        <section className="overview-hero-grid">
          <article className="overview-ingest-card">
            <div className="overview-card-head">
              <div>
                <p className="overview-mini-label">Current task</p>
                <h2>Connect a GitHub repository and start the analysis workflow</h2>
              </div>
              <div className="overview-stage-pill">Stage 1 · Ingestion</div>
            </div>

            <p className="overview-card-copy">
              Paste a public GitHub repository URL. RepoMind will keep the same backend and auth
              behavior, then send the repo into your existing ingestion flow.
            </p>

            <div
              className={`overview-input-shell ${ingest === INGEST.ERROR ? "is-error" : ""} ${ingest === INGEST.SUCCESS ? "is-success" : ""}`}
            >
              <label className="overview-input-label" htmlFor="repo-url">
                Repository URL
              </label>
              <div className="overview-input-row">
                <input
                  id="repo-url"
                  className="overview-input"
                  type="url"
                  placeholder="https://github.com/owner/repository"
                  value={repoUrl}
                  onChange={(e) => {
                    setRepoUrl(e.target.value);
                    if (ingest === INGEST.ERROR) setIngest(INGEST.IDLE);
                  }}
                  onKeyDown={(e) => e.key === "Enter" && handleIngest()}
                  disabled={ingest === INGEST.LOADING || ingest === INGEST.SUCCESS}
                  spellCheck={false}
                  autoComplete="off"
                  aria-label="GitHub repository URL"
                />
                <button
                  className={`overview-submit ${ingest === INGEST.LOADING ? "is-loading" : ""} ${ingest === INGEST.SUCCESS ? "is-success" : ""}`}
                  onClick={handleIngest}
                  disabled={ingest === INGEST.LOADING || ingest === INGEST.SUCCESS || !repoUrl.trim()}
                >
                  {ingest === INGEST.LOADING ? (
                    <>
                      <span className="overview-spinner" aria-hidden="true" />
                      Scanning
                    </>
                  ) : ingest === INGEST.SUCCESS ? (
                    "Complete"
                  ) : ingest === INGEST.ERROR ? (
                    "Retry"
                  ) : (
                    "Start analysis"
                  )}
                </button>
              </div>
            </div>

            {ingest === INGEST.ERROR && (
              <p className="overview-feedback error">{errorMsg}</p>
            )}

            {ingest === INGEST.SUCCESS && repoData && (
              <div className="overview-result-card">
                {[
                  ["Repository", `${repoData.owner}/${repoData.name}`],
                  ["Files indexed", `${repoData.fileCount}`],
                  ["Size", `${repoData.sizeKb} KB`],
                  ["Status", repoData.status],
                ].map(([label, value]) => (
                  <div key={label} className="overview-result-row">
                    <span>{label}</span>
                    <strong>{value}</strong>
                  </div>
                ))}
                <p className="overview-feedback success">Redirecting to the repository workspace...</p>
              </div>
            )}

            {(ingest === INGEST.IDLE || ingest === INGEST.ERROR) && (
              <div className="overview-steps">
                {[
                  ["01", "Paste a valid public GitHub repository link"],
                  ["02", "RepoMind sends it through the current ingest logic"],
                  ["03", "Move into the analysis workspace after success"],
                ].map(([step, text]) => (
                  <div key={step} className="overview-step">
                    <span>{step}</span>
                    <p>{text}</p>
                  </div>
                ))}
              </div>
            )}
          </article>

          <aside className="overview-insight-column">
            <section className="overview-panel">
              <div className="overview-panel-head">
                <p className="overview-mini-label">Capabilities</p>
                <span>Current UI foundation</span>
              </div>

              <div className="overview-feature-list">
                {[
                  "Repository tree mapping",
                  "Dependency and architecture context",
                  "AI analysis preparation",
                  "Future-ready design system for the full app",
                ].map((item) => (
                  <div key={item} className="overview-feature-item">
                    <span className="overview-feature-dot" />
                    <p>{item}</p>
                  </div>
                ))}
              </div>
            </section>

            <section className="overview-panel">
              <div className="overview-panel-head">
                <p className="overview-mini-label">Session</p>
                <span>Connected account</span>
              </div>

              <div className="overview-user-meta">
                <div className="overview-user-meta-row">
                  <span>User ID</span>
                  <strong>{user?.id ?? "-"}</strong>
                </div>
                <div className="overview-user-meta-row">
                  <span>Auth provider</span>
                  <strong>GitHub OAuth</strong>
                </div>
                <div className="overview-user-meta-row">
                  <span>Token mode</span>
                  <strong>JWT + refresh flow</strong>
                </div>
              </div>
            </section>
          </aside>
        </section>
      </div>
    </div>
  );
}
