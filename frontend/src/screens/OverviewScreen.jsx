import { useState, useCallback, useEffect } from "react";
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

function statusTone(status) {
  const s = (status || "").toUpperCase();
  if (["FAILED", "ERROR"].includes(s)) return "red";
  if (["PENDING", "PROCESSING", "INGESTING", "RUNNING"].includes(s)) return "amber";
  return "green";
}

export default function OverviewScreen() {
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);

  const [repoUrl, setRepoUrl] = useState("");
  const [ingest, setIngest] = useState(INGEST.IDLE);
  const [errorMsg, setErrorMsg] = useState("");
  const [menuOpen, setMenuOpen] = useState(false);
  const [navOpen, setNavOpen] = useState(false);

  const [history, setHistory] = useState([]);
  const [historyState, setHistoryState] = useState("loading"); // loading | ready | error

  useEffect(() => {
    if (!user?.id) return;
    let active = true;
    (async () => {
      try {
        const repos = await repoService.listRepos(user.id);
        if (active) {
          setHistory(Array.isArray(repos) ? repos : []);
          setHistoryState("ready");
        }
      } catch (err) {
        console.error("[OverviewScreen] Failed to load history:", err);
        if (active) setHistoryState("error");
      }
    })();
    return () => {
      active = false;
    };
  }, [user?.id]);

  const handleLogout = useCallback(async () => {
    await authService.logoutApi();
    navigate("/", { replace: true });
  }, [navigate]);

  const handleIngest = useCallback(async () => {
    if (!repoUrl.trim()) return;

    if (!isValidGithubUrl(repoUrl.trim())) {
      setErrorMsg("Enter a valid GitHub URL — e.g. https://github.com/owner/repo");
      setIngest(INGEST.ERROR);
      return;
    }

    setIngest(INGEST.LOADING);
    setErrorMsg("");

    try {
      const data = await repoService.ingestRepo(repoUrl.trim(), user.id);
      setIngest(INGEST.SUCCESS);
      setTimeout(() => navigate(`/analyze/repo/${data.repoId}`), 300);
    } catch (err) {
      console.error("[OverviewScreen] Ingest failed:", err);
      setErrorMsg(err.response?.data?.message || "Failed to ingest repository. Please try again.");
      setIngest(INGEST.ERROR);
    }
  }, [repoUrl, user, navigate]);

  const resetComposer = useCallback(() => {
    setRepoUrl("");
    setIngest(INGEST.IDLE);
    setErrorMsg("");
    setNavOpen(false);
  }, []);

  const busy = ingest === INGEST.LOADING || ingest === INGEST.SUCCESS;

  return (
    <div className="home">
      {navOpen && <div className="home-scrim" onClick={() => setNavOpen(false)} aria-hidden="true" />}

      {/* ── Sidebar ──────────────────────────────────────────── */}
      <aside className={`home-sidebar ${navOpen ? "open" : ""}`}>
        <div className="home-side-top">
          <div className="home-brand">
            <span className="home-brand-mark">RM</span>
            RepoMind
          </div>
          <button className="home-new" onClick={resetComposer}>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
              <line x1="12" y1="5" x2="12" y2="19" strokeLinecap="round" />
              <line x1="5" y1="12" x2="19" y2="12" strokeLinecap="round" />
            </svg>
            New analysis
          </button>
        </div>

        <div className="home-history">
          <p className="home-history-label">Recents</p>

          {historyState === "loading" && (
            <div className="home-history-skeleton">
              {[0, 1, 2, 3].map((i) => (
                <span key={i} />
              ))}
            </div>
          )}

          {historyState === "error" && (
            <p className="home-history-empty">Could not load history.</p>
          )}

          {historyState === "ready" && history.length === 0 && (
            <p className="home-history-empty">No repositories yet. Analyze one to get started.</p>
          )}

          {historyState === "ready" && history.length > 0 && (
            <div className="home-history-list">
              {history.map((repo) => (
                <button
                  key={repo.id}
                  className="home-repo"
                  title={repo.url}
                  onClick={() => navigate(`/analyze/repo/${repo.id}`)}
                >
                  <span className={`home-repo-dot ${statusTone(repo.status)}`} />
                  <span className="home-repo-text">
                    <span className="home-repo-name">{repo.name}</span>
                    <span className="home-repo-owner">{repo.owner}</span>
                  </span>
                </button>
              ))}
            </div>
          )}
        </div>

        <div className="home-side-foot">
          <button className="home-account" onClick={() => setMenuOpen((v) => !v)}>
            {user?.avatarUrl ? (
              <img src={user.avatarUrl} alt="" className="home-avatar" />
            ) : (
              <span className="home-avatar home-avatar-fallback">
                {user?.username?.[0]?.toUpperCase() ?? "?"}
              </span>
            )}
            <span className="home-account-name">{user?.username ?? "Account"}</span>
            <svg className="home-account-caret" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
              <polyline points="6 9 12 15 18 9" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </button>

          {menuOpen && (
            <div className="home-menu" onMouseLeave={() => setMenuOpen(false)}>
              {user?.email && <div className="home-menu-mail">{user.email}</div>}
              <button className="home-menu-item" onClick={handleLogout}>
                Sign out
              </button>
            </div>
          )}
        </div>
      </aside>

      {/* ── Main ─────────────────────────────────────────────── */}
      <main className="home-main">
        <div className="home-mobilebar">
          <button className="home-burger" onClick={() => setNavOpen(true)} aria-label="Open menu">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
              <line x1="3" y1="6" x2="21" y2="6" strokeLinecap="round" />
              <line x1="3" y1="12" x2="21" y2="12" strokeLinecap="round" />
              <line x1="3" y1="18" x2="21" y2="18" strokeLinecap="round" />
            </svg>
          </button>
          <div className="home-brand">
            <span className="home-brand-mark">RM</span>
            RepoMind
          </div>
        </div>

        <div className="home-stage">
          <h1 className="home-title">What repository do you want to understand?</h1>

          <div
            className={`home-field ${ingest === INGEST.ERROR ? "is-error" : ""} ${ingest === INGEST.SUCCESS ? "is-success" : ""}`}
          >
            <input
              className="home-input"
              type="url"
              placeholder="Paste a GitHub repository URL"
              value={repoUrl}
              onChange={(e) => {
                setRepoUrl(e.target.value);
                if (ingest === INGEST.ERROR) setIngest(INGEST.IDLE);
              }}
              onKeyDown={(e) => e.key === "Enter" && handleIngest()}
              disabled={busy}
              spellCheck={false}
              autoComplete="off"
              aria-label="GitHub repository URL"
            />
            <button
              className="home-send"
              onClick={handleIngest}
              disabled={busy || !repoUrl.trim()}
              aria-label="Analyze repository"
            >
              {ingest === INGEST.LOADING ? (
                <span className="home-spinner" aria-hidden="true" />
              ) : (
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
                  <line x1="5" y1="12" x2="19" y2="12" strokeLinecap="round" />
                  <polyline points="12 5 19 12 12 19" strokeLinecap="round" strokeLinejoin="round" />
                </svg>
              )}
            </button>
          </div>

          {ingest === INGEST.ERROR && <p className="home-note error">{errorMsg}</p>}
          {ingest === INGEST.SUCCESS && (
            <p className="home-note success">Opening the repository workspace…</p>
          )}

          {(ingest === INGEST.IDLE || ingest === INGEST.ERROR) && (
            <div className="home-suggestions">
              {["facebook/react", "vercel/next.js", "spring-projects/spring-boot"].map((slug) => (
                <button
                  key={slug}
                  type="button"
                  className="home-suggestion"
                  onClick={() => {
                    setRepoUrl(`https://github.com/${slug}`);
                    if (ingest === INGEST.ERROR) setIngest(INGEST.IDLE);
                  }}
                >
                  {slug}
                </button>
              ))}
            </div>
          )}
        </div>
      </main>
    </div>
  );
}
