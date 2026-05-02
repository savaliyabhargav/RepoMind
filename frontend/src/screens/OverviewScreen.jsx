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
    <div className="ov-root">
      <div className="ov-scanlines" aria-hidden="true" />
      <div className="ov-grid" aria-hidden="true" />

      <header className="ov-nav">
        <div className="ov-nav-left">
          <div className="ov-logo">
            <span className="ov-lb">[</span>
            <span className="ov-lt">RepoMind</span>
            <span className="ov-lb">]</span>
          </div>
          <div className="ov-nav-tag">MISSION CONTROL</div>
        </div>
        <div className="ov-nav-right">
          <div className="ov-status-pill">
            <span className="ov-status-dot" />
            SYSTEMS NOMINAL
          </div>
          <button className="ov-logout-btn" onClick={handleLogout}>
            <svg
              width="13"
              height="13"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2.5"
              strokeLinecap="round"
            >
              <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
              <polyline points="16 17 21 12 16 7" />
              <line x1="21" y1="12" x2="9" y2="12" />
            </svg>
            LOGOUT
          </button>
        </div>
      </header>

      <main className="ov-main">
        <aside className="ov-panel ov-left">
          <p className="ov-panel-label">OPERATOR</p>

          <div className="ov-profile">
            <div className="ov-avatar-wrap">
              {user?.avatarUrl ? (
                <img src={user.avatarUrl} alt={`${user.username} avatar`} className="ov-avatar" />
              ) : (
                <div className="ov-avatar-fallback">
                  {user?.username?.[0]?.toUpperCase() ?? "?"}
                </div>
              )}
              <div className="ov-avatar-ring" aria-hidden="true" />
            </div>
            <p className="ov-profile-name">{user?.username ?? "-"}</p>
            {user?.email && <p className="ov-profile-email">{user.email}</p>}
          </div>

          <div className="ov-readouts">
            {[
              { label: "CLEARANCE", value: user?.plan ?? "FREE" },
              { label: "ENROLLED", value: joinedDate },
              { label: "PROVIDER", value: "GITHUB" },
              { label: "STATUS", value: "ACTIVE" },
            ].map(({ label, value }) => (
              <div key={label} className="ov-readout">
                <span className="ov-readout-label">{label}</span>
                <span className="ov-readout-value">{value}</span>
              </div>
            ))}
          </div>

          <div className="ov-uid-block">
            <span className="ov-uid-label">UID</span>
            <span className="ov-uid-value">{user?.id ?? "-"}</span>
          </div>
        </aside>

        <section className="ov-panel ov-center">
          <p className="ov-panel-label">TARGET ACQUISITION</p>

          <div className="ov-hero">
            <div>
              <span className="ov-kicker">Repository ingestion</span>
              <h1 className="ov-hero-title">
                Scan a
                <span className="ov-hero-accent"> GitHub repository</span>
              </h1>
              <p className="ov-hero-sub">
                Enter a public repository URL to begin analysis. The UI stays simple here while
                your existing backend ingestion flow continues unchanged.
              </p>
            </div>

            <div className="ov-hero-meta">
              <div className="ov-hero-stat">
                <span className="ov-hero-stat-label">Pipeline</span>
                <strong>Ingest to analysis</strong>
              </div>
              <div className="ov-hero-stat">
                <span className="ov-hero-stat-label">Mode</span>
                <strong>Current stage only</strong>
              </div>
            </div>
          </div>

          <div
            className={`ov-input-wrap ${ingest === INGEST.ERROR ? "is-error" : ""} ${ingest === INGEST.SUCCESS ? "is-success" : ""}`}
          >
            <span className="ov-input-prefix">URL://</span>
            <input
              className="ov-input"
              type="url"
              placeholder="github.com/owner/repository"
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
          </div>

          {ingest === INGEST.ERROR && (
            <p className="ov-error-msg">Warning: {errorMsg}</p>
          )}

          <button
            className={`ov-scan-btn ${ingest === INGEST.LOADING ? "is-loading" : ""} ${ingest === INGEST.SUCCESS ? "is-success" : ""}`}
            onClick={handleIngest}
            disabled={ingest === INGEST.LOADING || ingest === INGEST.SUCCESS || !repoUrl.trim()}
          >
            {ingest === INGEST.IDLE && (
              <>
                <svg
                  width="15"
                  height="15"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="2.5"
                  strokeLinecap="round"
                >
                  <circle cx="11" cy="11" r="8" />
                  <line x1="21" y1="21" x2="16.65" y2="16.65" />
                </svg>
                INITIATE SCAN
              </>
            )}
            {ingest === INGEST.LOADING && (
              <>
                <span className="ov-spinner" aria-hidden="true" />
                SCANNING...
              </>
            )}
            {ingest === INGEST.SUCCESS && (
              <>
                <svg
                  width="15"
                  height="15"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="2.5"
                  strokeLinecap="round"
                >
                  <polyline points="20 6 9 17 4 12" />
                </svg>
                SCAN COMPLETE
              </>
            )}
            {ingest === INGEST.ERROR && (
              <>
                <svg
                  width="15"
                  height="15"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="2.5"
                  strokeLinecap="round"
                >
                  <circle cx="11" cy="11" r="8" />
                  <line x1="21" y1="21" x2="16.65" y2="16.65" />
                </svg>
                RETRY SCAN
              </>
            )}
          </button>

          {ingest === INGEST.SUCCESS && repoData && (
            <div className="ov-success-card">
              {[
                { label: "REPO", value: `${repoData.owner}/${repoData.name}` },
                { label: "FILES", value: `${repoData.fileCount} indexed` },
                { label: "SIZE", value: `${repoData.sizeKb} KB` },
                { label: "STATUS", value: repoData.status, green: true },
              ].map(({ label, value, green }) => (
                <div key={label} className="ov-success-row">
                  <span className="ov-success-label">{label}</span>
                  <span className={`ov-success-value ${green ? "is-ready" : ""}`}>{value}</span>
                </div>
              ))}
              <p className="ov-redirect-note">Redirecting to mission overview...</p>
            </div>
          )}

          {(ingest === INGEST.IDLE || ingest === INGEST.ERROR) && (
            <div className="ov-instructions">
              {[
                ["01", "Paste any public GitHub repo URL above"],
                ["02", "RepoMind maps the entire file tree"],
                ["03", "Explore structure, stats and AI insights"],
              ].map(([num, text]) => (
                <div key={num} className="ov-instr-row">
                  <span className="ov-instr-num">{num}</span>
                  <span className="ov-instr-text">{text}</span>
                </div>
              ))}
            </div>
          )}
        </section>

        <aside className="ov-panel ov-right">
          <p className="ov-panel-label">SYSTEM</p>
          <div className="ov-sys-list">
            {[
              ["FRONTEND", "React 19"],
              ["BACKEND", "Spring Boot"],
              ["AUTH", "RS256 JWT"],
              ["DATABASE", "PostgreSQL"],
              ["CACHE", "Redis"],
              ["QUEUE", "Kafka"],
              ["STORAGE", "MinIO"],
              ["VECTORS", "Qdrant"],
            ].map(([label, value]) => (
              <div key={label} className="ov-sys-row">
                <span className="ov-sys-label">{label}</span>
                <span className="ov-sys-value">{value}</span>
                <span className="ov-sys-dot" />
              </div>
            ))}
          </div>

          <p className="ov-panel-label" style={{ marginTop: "1.75rem" }}>
            CAPABILITIES
          </p>
          <div className="ov-caps-list">
            {[
              "File tree mapping",
              "Dependency graphs",
              "Commit analysis",
              "Code health score",
              "AI interrogation",
              "Team insights",
            ].map((cap) => (
              <div key={cap} className="ov-cap-row">
                <span className="ov-cap-dot" />
                {cap}
              </div>
            ))}
          </div>
        </aside>
      </main>
    </div>
  );
}
