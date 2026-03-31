import { useEffect, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { useAuthStore } from "../../store/authStore";
import authService from "../../services/authService";
import "./LoginCallback.css";

// ─── Status constants ────────────────────────────────────────────────────────
const STATUS = {
  VERIFYING: "verifying",
  EXCHANGING: "exchanging",
  SUCCESS: "success",
  ERROR: "error",
};

// ─── Component ───────────────────────────────────────────────────────────────
export default function LoginCallback() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const login = useAuthStore((s) => s.login);

  const [status, setStatus] = useState(STATUS.VERIFYING);
  const [errorMsg, setErrorMsg] = useState("");

  useEffect(() => {
    // Get code and state fresh from URL every time this page loads
    const code = searchParams.get("code");
    const returnedState = searchParams.get("state");
    const githubError = searchParams.get("error");

    // Always clear localStorage state at the start of every attempt
    // so stale values never block a fresh login
    const savedState = localStorage.getItem("github_oauth_state");
    localStorage.removeItem("github_oauth_state");

    handleCallback(code, returnedState, githubError, savedState);
  }, []); // runs once per page mount — each GitHub redirect is a fresh mount

  async function handleCallback(code, returnedState, githubError, savedState) {
    try {
      // ── Step 1: Check for GitHub errors ─────────────────────────────────
      if (githubError) {
        throw new Error(
          githubError === "access_denied"
            ? "You cancelled the GitHub login. Please try again."
            : `GitHub returned an error: ${githubError}`
        );
      }

      if (!code) {
        throw new Error("No authorisation code received from GitHub.");
      }

      // ── Step 2: CSRF state check ─────────────────────────────────────────
      // ── Step 2: CSRF state check ─────────────────────────────────────────
      setStatus(STATUS.VERIFYING);

      if (!savedState || savedState !== returnedState) {
        // In development, log the mismatch but continue anyway
        console.warn("[LoginCallback] State mismatch — skipping CSRF check in dev mode");
        if (import.meta.env.PROD) {
          throw new Error("Security check failed. Please try logging in again.");
        }
      }

      // ── Step 3: Exchange code for JWT ────────────────────────────────────
      setStatus(STATUS.EXCHANGING);
      const { token, user } = await authService.exchangeCode(code);

      if (!token || !user) {
        throw new Error("Invalid response from server. Please try again.");
      }

      // ── Step 4: Save to store & redirect ────────────────────────────────
      login(token, user);
      setStatus(STATUS.SUCCESS);
      setTimeout(() => navigate("/analyze", { replace: true }), 1000);

    } catch (err) {
      console.error("[LoginCallback] Auth failed:", err);
      setStatus(STATUS.ERROR);
      setErrorMsg(err.message || "An unexpected error occurred.");
    }
  }

  // ── Render ──────────────────────────────────────────────────────────────────
  return (
    <div className="callback-root">
      <div className="callback-grid" aria-hidden="true" />

      <div className="callback-card">
        {/* Logo */}
        <div className="callback-logo">
          <span className="cb-bracket">[</span>
          <span className="cb-text">RepoMind</span>
          <span className="cb-bracket">]</span>
        </div>

        {status === STATUS.VERIFYING && (
          <CallbackState
            icon={<Spinner />}
            title="Verifying request"
            subtitle="Checking security token…"
          />
        )}

        {status === STATUS.EXCHANGING && (
          <CallbackState
            icon={<Spinner />}
            title="Authenticating"
            subtitle="Talking to GitHub, hang tight…"
            showSteps
          />
        )}

        {status === STATUS.SUCCESS && (
          <CallbackState
            icon={<SuccessIcon />}
            title="You're in!"
            subtitle="Redirecting to your dashboard…"
            success
          />
        )}

        {status === STATUS.ERROR && (
          <CallbackState
            icon={<ErrorIcon />}
            title="Authentication failed"
            subtitle={errorMsg}
            error
            onRetry={() => navigate("/", { replace: true })}
          />
        )}
      </div>
    </div>
  );
}

// ─── Sub-components ───────────────────────────────────────────────────────────

function CallbackState({ icon, title, subtitle, success, error, onRetry, showSteps }) {
  return (
    <div className="cb-state">
      <div className={`cb-icon-wrap ${success ? "cb-success" : error ? "cb-error" : ""}`}>
        {icon}
      </div>
      <h1 className="cb-title">{title}</h1>
      <p className="cb-subtitle">{subtitle}</p>

      {showSteps && (
        <ul className="cb-steps" aria-label="Progress steps">
          {["Verifying state", "Exchanging code", "Fetching profile"].map((step, i) => (
            <li key={step} className="cb-step">
              <span className="step-dot" style={{ animationDelay: `${i * 0.3}s` }} />
              {step}
            </li>
          ))}
        </ul>
      )}

      {error && onRetry && (
        <button className="cb-retry-btn" onClick={onRetry}>
          ← Back to login
        </button>
      )}
    </div>
  );
}

function Spinner() {
  return (
    <svg className="cb-spinner" viewBox="0 0 24 24" fill="none" aria-label="Loading">
      <circle cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="2.5"
        strokeLinecap="round" strokeDasharray="32" strokeDashoffset="32" />
    </svg>
  );
}

function SuccessIcon() {
  return (
    <svg className="cb-check" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-label="Success">
      <polyline points="20 6 9 17 4 12" />
    </svg>
  );
}

function ErrorIcon() {
  return (
    <svg className="cb-x" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2.5" strokeLinecap="round" aria-label="Error">
      <line x1="18" y1="6" x2="6" y2="18" />
      <line x1="6" y1="6" x2="18" y2="18" />
    </svg>
  );
}