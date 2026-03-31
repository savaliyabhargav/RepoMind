import { useCallback } from "react";
import { useNavigate } from "react-router-dom";
import { useAuthStore } from "../store/authStore";
import authService from "../services/authService";
import "./OverviewScreen.css";

// ─── Component ───────────────────────────────────────────────────────────────
export default function OverviewScreen() {
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);

  // Format the joined date nicely
  const joinedDate = user?.createdAt
    ? new Date(user.createdAt).toLocaleDateString("en-GB", {
        day: "numeric",
        month: "long",
        year: "numeric",
      })
    : "—";

  const handleLogout = useCallback(async () => {
    await authService.logoutApi(); // clears store + invalidates cookie
    navigate("/", { replace: true });
  }, [navigate]);

  return (
    <div className="overview-root">
      <div className="overview-grid" aria-hidden="true" />

      {/* ── Top nav bar ── */}
      <header className="overview-nav">
        <div className="nav-logo">
          <span className="nb">[</span>
          <span className="nt">RepoMind</span>
          <span className="nb">]</span>
        </div>

        <button className="btn-logout" onClick={handleLogout}>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none"
            stroke="currentColor" strokeWidth="2.5" strokeLinecap="round"
            aria-hidden="true">
            <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
            <polyline points="16 17 21 12 16 7" />
            <line x1="21" y1="12" x2="9" y2="12" />
          </svg>
          Logout
        </button>
      </header>

      {/* ── Main content ── */}
      <main className="overview-main">
        {/* Profile card */}
        <div className="profile-card" role="region" aria-label="Your profile">
          {/* Avatar */}
          <div className="profile-avatar-wrap">
            {user?.avatarUrl ? (
              <img
                src={user.avatarUrl}
                alt={`${user.username}'s GitHub avatar`}
                className="profile-avatar"
              />
            ) : (
              <div className="profile-avatar-fallback" aria-label="Avatar placeholder">
                {user?.username?.[0]?.toUpperCase() ?? "?"}
              </div>
            )}
            <span className="avatar-badge" title="Authenticated via GitHub">
              <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                <path d="M12 0C5.374 0 0 5.373 0 12c0 5.302 3.438 9.8 8.207 11.387.599.111.793-.261.793-.577v-2.234c-3.338.726-4.033-1.416-4.033-1.416-.546-1.387-1.333-1.756-1.333-1.756-1.089-.745.083-.729.083-.729 1.205.084 1.839 1.237 1.839 1.237 1.07 1.834 2.807 1.304 3.492.997.107-.775.418-1.305.762-1.604-2.665-.305-5.467-1.334-5.467-5.931 0-1.311.469-2.381 1.236-3.221-.124-.303-.535-1.524.117-3.176 0 0 1.008-.322 3.301 1.23A11.509 11.509 0 0112 5.803c1.02.005 2.047.138 3.006.404 2.291-1.552 3.297-1.23 3.297-1.23.653 1.653.242 2.874.118 3.176.77.84 1.235 1.911 1.235 3.221 0 4.609-2.807 5.624-5.479 5.921.43.372.823 1.102.823 2.222v3.293c0 .319.192.694.801.576C20.566 21.797 24 17.3 24 12c0-6.627-5.373-12-12-12z" />
              </svg>
            </span>
          </div>

          {/* Name + username */}
          <div className="profile-identity">
            <h1 className="profile-name">
              {user?.username ?? "GitHub User"}
            </h1>
            {user?.email && (
              <p className="profile-email">{user.email}</p>
            )}
          </div>

          {/* Plan badge */}
          <div className={`plan-badge plan-${(user?.plan ?? "FREE").toLowerCase()}`}>
            <span className="plan-dot" aria-hidden="true" />
            {user?.plan ?? "FREE"} plan
          </div>
        </div>

        {/* Stats / details grid */}
        <div className="details-grid">
          <DetailCard
            label="GitHub username"
            value={`@${user?.username ?? "—"}`}
            mono
          />
          <DetailCard
            label="Email address"
            value={user?.email ?? "Not public"}
            mono
          />
          <DetailCard
            label="Current plan"
            value={user?.plan ?? "FREE"}
            highlight
          />
          <DetailCard
            label="Member since"
            value={joinedDate}
          />
        </div>

        {/* Coming soon strip */}
        <div className="coming-soon-strip">
          <span className="cs-dot" aria-hidden="true" />
          <span className="cs-text">
            Repo analysis dashboard — coming in next phase
          </span>
        </div>
      </main>
    </div>
  );
}

// ─── Detail Card sub-component ───────────────────────────────────────────────
function DetailCard({ label, value, mono, highlight }) {
  return (
    <div className="detail-card">
      <span className="detail-label">{label}</span>
      <span className={`detail-value ${mono ? "mono" : ""} ${highlight ? "highlight" : ""}`}>
        {value}
      </span>
    </div>
  );
}