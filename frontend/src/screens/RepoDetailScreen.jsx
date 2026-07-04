import { useCallback, useEffect, useRef, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { useAuthStore } from "../store/authStore";
import repoService from "../services/repoService";
import mermaid from "mermaid";
import { fixMermaidCode } from "./fixMermaidCode";
import "./RepoDetailScreen.css";

mermaid.initialize({
  startOnLoad: false,
  theme: "dark",
  securityLevel: "loose",
  fontSize: 16,
  fontFamily: '"IBM Plex Mono", "Courier New", monospace',
  themeVariables: {
    darkMode: true,
    background: "#0c0e16",
    mainBkg: "#141820",
    nodeBorder: "rgba(77,157,224,0.4)",
    clusterBkg: "#111520",
    titleColor: "#e8edf7",
    edgeLabelBackground: "#141820",
    lineColor: "rgba(77,157,224,0.55)",
    textColor: "#c8d4e8",
    primaryColor: "#1a2235",
    primaryTextColor: "#dce5f4",
    primaryBorderColor: "rgba(77,157,224,0.45)",
    secondaryColor: "#151c2c",
    tertiaryColor: "#111520",
    activationBorderColor: "rgba(77,157,224,0.6)",
    activationBkgColor: "#1a2235",
    sequenceNumberColor: "#4d9de0",
    actorBkg: "#141820",
    actorBorder: "rgba(77,157,224,0.4)",
    actorTextColor: "#dce5f4",
    actorLineColor: "rgba(77,157,224,0.35)",
    signalColor: "#4d9de0",
    signalTextColor: "#c8d4e8",
    labelBoxBkgColor: "#141820",
    labelBoxBorderColor: "rgba(77,157,224,0.3)",
    labelTextColor: "#c8d4e8",
    loopTextColor: "#c8d4e8",
  },
});

const LOAD = { LOADING: "loading", PROCESSING: "processing", SUCCESS: "success", ERROR: "error" };

const AI_PROVIDER = "GROQ"; // single engine — key rotation + failover happen server-side

const DOCK_ITEMS = [
  { id: "files", label: "Explorer" },
  { id: "overview", label: "Overview" },
  { id: "uml", label: "UML" },
  { id: "chat", label: "Chat" },
  { id: "settings", label: "Settings" },
];

// ─── Icons ────────────────────────────────────────────────────────────────────

function ActivityIcon({ id }) {
  const p = { fill: "none", stroke: "currentColor", strokeWidth: 1.5, strokeLinecap: "round", strokeLinejoin: "round" };
  switch (id) {
    case "files":
      return (
        <svg viewBox="0 0 24 24" {...p}>
          <path d="M14 3H6a1 1 0 0 0-1 1v16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V8z" />
          <path d="M14 3v5h5" />
        </svg>
      );
    case "overview":
      return (
        <svg viewBox="0 0 24 24" {...p}>
          <rect x="4" y="4" width="7" height="7" rx="1" />
          <rect x="13" y="4" width="7" height="7" rx="1" />
          <rect x="4" y="13" width="7" height="7" rx="1" />
          <rect x="13" y="13" width="7" height="7" rx="1" />
        </svg>
      );
    case "uml":
      return (
        <svg viewBox="0 0 24 24" {...p}>
          <circle cx="6" cy="6" r="2.6" />
          <circle cx="18" cy="6" r="2.6" />
          <circle cx="12" cy="18" r="2.6" />
          <path d="M7.3 8.2 10.8 15.6 M16.7 8.2 13.2 15.6 M8.6 6h6.8" />
        </svg>
      );
    case "chat":
      return (
        <svg viewBox="0 0 24 24" {...p}>
          <path d="M21 12a8 8 0 0 1-8 8H5l-2 2V12a8 8 0 0 1 8-8h2a8 8 0 0 1 8 8z" />
        </svg>
      );
    case "settings":
      return (
        <svg viewBox="0 0 24 24" {...p}>
          <circle cx="12" cy="12" r="3" />
          <path d="M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M19.1 4.9 17 7M7 17l-2.1 2.1" />
        </svg>
      );
    default:
      return null;
  }
}

// Per-language accent colors, close to the VS Code / Seti icon palette
const EXT_COLORS = {
  java: "#e8834a", kt: "#a97bff", groovy: "#6398aa",
  js: "#e8d44d", jsx: "#5fd4f2", ts: "#4a8fd4", tsx: "#5fd4f2", mjs: "#e8d44d", cjs: "#e8d44d",
  py: "#6aa8e8", go: "#56c2d6", rs: "#d69a7c", rb: "#e05a6d", php: "#8892bf", cs: "#9b6fd4",
  c: "#6a9fd4", cpp: "#6a9fd4", h: "#b58ee0", hpp: "#b58ee0",
  html: "#e07b53", css: "#7c9fe8", scss: "#e06a9f", less: "#7c9fe8",
  json: "#c9b45a", yml: "#b58ee0", yaml: "#b58ee0", toml: "#b58ee0", xml: "#9fb35a",
  sql: "#e0a44a", md: "#6ac2a8", sh: "#8ecf6a", bat: "#8ecf6a", ps1: "#6a9fd4",
  txt: "#8a94a8", svg: "#e0b44a", png: "#b58ee0", jpg: "#b58ee0", gif: "#b58ee0", ico: "#b58ee0",
  gradle: "#6aa86a", properties: "#8a94a8", lock: "#8a94a8", env: "#c9b45a",
  dockerfile: "#4a9fd4", gitignore: "#8a94a8",
};

function extColor(name) {
  const lower = (name || "").toLowerCase();
  if (lower === "dockerfile") return EXT_COLORS.dockerfile;
  if (lower.startsWith(".git")) return EXT_COLORS.gitignore;
  const dot = lower.lastIndexOf(".");
  const ext = dot >= 0 ? lower.slice(dot + 1) : "";
  return EXT_COLORS[ext] || "#7d8aa0";
}

function FileIcon({ name }) {
  const color = extColor(name);
  return (
    <svg className="ide-item-icon" viewBox="0 0 16 16" aria-hidden="true">
      <path d="M9.2 1.75H4.5a.9.9 0 0 0-.9.9v10.7a.9.9 0 0 0 .9.9h7a.9.9 0 0 0 .9-.9V4.95z"
        fill="none" stroke={color} strokeWidth="1.15" strokeLinejoin="round" />
      <path d="M9.2 1.75v3.2h3.2" fill="none" stroke={color} strokeWidth="1.15" strokeLinejoin="round" />
    </svg>
  );
}

function FolderIcon({ open }) {
  return (
    <svg className="ide-item-icon" viewBox="0 0 16 16" aria-hidden="true">
      {open ? (
        <path d="M2 12.6 3.5 6.9a.9.9 0 0 1 .87-.65h9.13l-1.62 6.4a.9.9 0 0 1-.87.65H2.4a.45.45 0 0 1-.4-.7zM2 11.5V3.9a.9.9 0 0 1 .9-.9h2.9l1.4 1.5h4.4a.9.9 0 0 1 .9.9v.85"
          fill="none" stroke="#8ea6c8" strokeWidth="1.15" strokeLinejoin="round" />
      ) : (
        <path d="M1.9 12.4V3.9a.9.9 0 0 1 .9-.9h2.9l1.4 1.5h5.6a.9.9 0 0 1 .9.9v7a.9.9 0 0 1-.9.9H2.8a.9.9 0 0 1-.9-.9z"
          fill="none" stroke="#8ea6c8" strokeWidth="1.15" strokeLinejoin="round" />
      )}
    </svg>
  );
}

function BranchIcon() {
  return (
    <svg className="ide-status-icon" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.2" aria-hidden="true">
      <circle cx="4.5" cy="3.5" r="1.7" />
      <circle cx="4.5" cy="12.5" r="1.7" />
      <circle cx="11.5" cy="5.5" r="1.7" />
      <path d="M4.5 5.2v5.6M11.5 7.2c0 2.2-2.5 3-4.8 3.3" strokeLinecap="round" />
    </svg>
  );
}

function SplitIcon() {
  return (
    <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.2" aria-hidden="true">
      <rect x="1.8" y="2.6" width="12.4" height="10.8" rx="1.2" />
      <path d="M8 2.6v10.8" />
    </svg>
  );
}

function SidebarIcon() {
  return (
    <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.2" aria-hidden="true">
      <rect x="1.8" y="2.6" width="12.4" height="10.8" rx="1.2" />
      <path d="M6 2.6v10.8" />
    </svg>
  );
}

function CloseIcon() {
  return (
    <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" aria-hidden="true">
      <path d="M4.5 4.5l7 7M11.5 4.5l-7 7" />
    </svg>
  );
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function formatBytes(bytes = 0) {
  if (!bytes) return "0 B";
  const units = ["B", "KB", "MB", "GB"];
  const i = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
  const v = bytes / 1024 ** i;
  return `${v.toFixed(v >= 10 || i === 0 ? 0 : 1)} ${units[i]}`;
}

function buildFileTree(nodes) {
  const root = { id: "root", name: "Repository", path: "", type: "DIRECTORY", children: new Map() };
  nodes.forEach((node) => {
    const parts = node.path.split("/").filter(Boolean);
    let cur = root;
    parts.forEach((part, idx) => {
      const isLeaf = idx === parts.length - 1;
      const path = parts.slice(0, idx + 1).join("/");
      if (!cur.children.has(part)) {
        cur.children.set(part, {
          id: isLeaf ? node.id : path,
          name: part, path,
          type: isLeaf ? node.type : "DIRECTORY",
          sizeBytes: isLeaf ? node.sizeBytes : 0,
          children: new Map(),
        });
      }
      const next = cur.children.get(part);
      if (isLeaf) { next.id = node.id; next.type = node.type; next.sizeBytes = node.sizeBytes; }
      cur = next;
    });
  });
  function sort(item) {
    return Array.from(item.children.values())
      .sort((a, b) => { if (a.type !== b.type) return a.type === "DIRECTORY" ? -1 : 1; return a.name.localeCompare(b.name); })
      .map((c) => ({ ...c, children: sort(c) }));
  }
  return sort(root);
}

function collectFolderPaths(items, paths = []) {
  items.forEach((item) => { if (item.type === "DIRECTORY") { paths.push(item.path); collectFolderPaths(item.children, paths); } });
  return paths;
}

// ─── Blueprint Infinite Canvas ────────────────────────────────────────────────

function BlueprintCanvas({ explain, loading, error, onRegenerate }) {
  const viewportRef = useRef(null);
  const [transform, setTransform] = useState({ x: 0, y: 0, scale: 1 });
  const isDragging = useRef(false);
  const lastMouse = useRef({ x: 0, y: 0 });

  // Rendered result tagged with the code it came from — staleness is derived
  // below instead of resetting state synchronously inside the effect.
  const [rendered, setRendered] = useState(null); // { code, svg, error, errMsg }
  const mermaidSeq = useRef(0);
  const svgNodeRef = useRef(null);

  // Center view on first mount
  useEffect(() => {
    const vp = viewportRef.current;
    if (vp) setTransform({ x: Math.round(vp.clientWidth / 2), y: Math.round(vp.clientHeight / 2), scale: 1 });
  }, []);

  // Render (and re-center on completion) whenever explain changes
  useEffect(() => {
    if (!explain?.mermaidCode) return;

    let active = true;
    const code = explain.mermaidCode;
    const id = `bp-${Date.now()}-${++mermaidSeq.current}`;
    const cleaned = fixMermaidCode(code);
    mermaid
      .render(id, cleaned)
      .then(({ svg: s }) => {
        if (!active) return;
        setRendered({ code, svg: s, error: false, errMsg: "" });
        const vp = viewportRef.current;
        if (vp) setTransform({ x: Math.round(vp.clientWidth / 2), y: Math.round(vp.clientHeight / 2), scale: 1 });
      })
      .catch((err) => {
        if (!active) return;
        const msg = err?.message || String(err);
        console.error("[mermaid render error]", msg, "\n--- code ---\n", cleaned);
        setRendered({ code, svg: "", error: true, errMsg: msg });
      });
    return () => { active = false; };
  }, [explain?.fileId, explain?.mermaidCode]);

  // A result only counts if it matches the diagram currently requested —
  // stale renders from a previously selected file show the skeleton instead.
  const isCurrent = rendered != null && rendered.code === explain?.mermaidCode;
  const svg = isCurrent && !rendered.error ? rendered.svg : "";
  const svgError = isCurrent ? rendered.error : false;
  const svgRenderErr = isCurrent ? rendered.errMsg : "";

  // Non-passive wheel for zoom-toward-cursor
  const handleWheel = useCallback((e) => {
    e.preventDefault();
    const rect = viewportRef.current.getBoundingClientRect();
    const mx = e.clientX - rect.left;
    const my = e.clientY - rect.top;
    const factor = e.deltaY < 0 ? 1.12 : 1 / 1.12;
    setTransform((t) => {
      const newScale = Math.max(0.02, Math.min(15, t.scale * factor));
      const ratio = newScale / t.scale;
      return { x: mx + (t.x - mx) * ratio, y: my + (t.y - my) * ratio, scale: newScale };
    });
  }, []);

  useEffect(() => {
    const vp = viewportRef.current;
    if (!vp) return;
    vp.addEventListener("wheel", handleWheel, { passive: false });
    return () => vp.removeEventListener("wheel", handleWheel);
  }, [handleWheel]);

  // Initial view: fix SVG pixel dimensions and set a text-readable scale.
  // "Fit to viewport" sounds right but makes large diagrams unreadable (2-3px text).
  // Instead: Mermaid renders text at fontSize:14 SVG units → scale = 10/14 ≈ 0.714
  // so text lands at ~10px on screen immediately, and the user pans to explore.
  useEffect(() => {
    if (!svg || !svgNodeRef.current || !viewportRef.current) return;
    requestAnimationFrame(() => {
      const svgEl = svgNodeRef.current?.querySelector("svg");
      if (!svgEl) return;

      // Read natural coordinate dimensions from viewBox
      let natW = 480, natH = 320;
      const vb = svgEl.getAttribute("viewBox");
      if (vb) {
        const parts = vb.trim().split(/[\s,]+/);
        if (parts.length >= 4) {
          const w = parseFloat(parts[2]);
          const h = parseFloat(parts[3]);
          if (w > 0) natW = w;
          if (h > 0) natH = h;
        }
      }

      // Force SVG to its natural coordinate size so 1 SVG unit = 1 CSS pixel.
      // Without this, width="100%" is ambiguous inside an inline-block parent.
      svgEl.style.width = natW + "px";
      svgEl.style.height = natH + "px";
      svgEl.style.maxWidth = "none";

      const vp = viewportRef.current;
      // fontSize:16 in SVG units → 100px on screen → scale = 100/16 = 6.25
      const scale = 100 / 16;
      setTransform({
        x: Math.round(vp.clientWidth / 2),
        y: Math.round(vp.clientHeight / 2),
        scale,
      });
    });
  }, [svg]);

  const onMouseDown = useCallback((e) => {
    if (e.button !== 0) return;
    isDragging.current = true;
    lastMouse.current = { x: e.clientX, y: e.clientY };
    viewportRef.current?.classList.add("is-dragging");
  }, []);

  const onMouseMove = useCallback((e) => {
    if (!isDragging.current) return;
    const dx = e.clientX - lastMouse.current.x;
    const dy = e.clientY - lastMouse.current.y;
    lastMouse.current = { x: e.clientX, y: e.clientY };
    setTransform((t) => ({ ...t, x: t.x + dx, y: t.y + dy }));
  }, []);

  const onMouseUp = useCallback(() => {
    isDragging.current = false;
    viewportRef.current?.classList.remove("is-dragging");
  }, []);

  const fitView = useCallback(() => {
    const vp = viewportRef.current;
    if (vp) setTransform({ x: Math.round(vp.clientWidth / 2), y: Math.round(vp.clientHeight / 2), scale: 1 });
  }, []);

  const zoomBy = useCallback((factor) => {
    const vp = viewportRef.current;
    if (!vp) return;
    const cx = Math.round(vp.clientWidth / 2);
    const cy = Math.round(vp.clientHeight / 2);
    setTransform((t) => {
      const newScale = Math.max(0.02, Math.min(15, t.scale * factor));
      const ratio = newScale / t.scale;
      return { x: cx + (t.x - cx) * ratio, y: cy + (t.y - cy) * ratio, scale: newScale };
    });
  }, []);

  const hasContent = explain && !loading && !error;

  return (
    <div
      ref={viewportRef}
      className="bp-viewport"
      style={{ backgroundPosition: `${transform.x % 24}px ${transform.y % 24}px` }}
      onMouseDown={onMouseDown}
      onMouseMove={onMouseMove}
      onMouseUp={onMouseUp}
      onMouseLeave={onMouseUp}
    >
      {/* ── State overlays (not part of the world) ── */}
      {!explain && !loading && !error && (
        <div className="bp-state-overlay">
          <svg className="bp-state-glyph" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.2" aria-hidden="true">
            <circle cx="6" cy="6" r="2.4" />
            <circle cx="18" cy="6" r="2.4" />
            <circle cx="12" cy="18" r="2.4" />
            <path d="M7.2 7.9 10.9 15.8 M16.8 7.9 13.1 15.8 M8.4 6h7.2" strokeLinecap="round" />
          </svg>
          <p className="bp-state-title">No diagram open</p>
          <p>Pick a file from the Explorer and RepoMind will draw its architecture for you.</p>
        </div>
      )}

      {loading && (
        <div className="bp-state-overlay">
          <span className="workspace-loader" />
          <p className="bp-state-title">Analyzing source<span className="bp-ellipsis" /></p>
          <p>Reading the file and generating a diagram with AI — usually a few seconds.</p>
        </div>
      )}

      {error && !loading && (
        <div className="bp-state-overlay bp-state-overlay--error">
          <div className="bp-state-icon">⚠</div>
          <p>{error}</p>
          {onRegenerate && (
            <button type="button" className="bp-retry-btn" onClick={onRegenerate}>
              Try again
            </button>
          )}
        </div>
      )}

      {/* ── World (pannable / zoomable) ── */}
      {hasContent && (
        <div
          className="bp-world"
          style={{ transform: `translate(${Math.round(transform.x)}px,${Math.round(transform.y)}px) scale(${transform.scale})` }}
        >
          {/* Diagram node card — centered at world origin */}
          <div className="bp-node">
            {!svg && !svgError && <div className="bp-node-skeleton" />}
            {svgError && (
              <div className="bp-node-raw-wrap">
                <p className="bp-node-raw-label">
                  The AI produced an invalid diagram for this file — this happens occasionally.
                </p>
                {onRegenerate && (
                  <button type="button" className="bp-retry-btn" onClick={onRegenerate}>
                    Regenerate diagram
                  </button>
                )}
                {svgRenderErr && (
                  <pre className="bp-node-raw bp-node-raw--error">{svgRenderErr}</pre>
                )}
                <p className="bp-node-raw-label" style={{ marginTop: 10 }}>Raw Mermaid code:</p>
                <pre className="bp-node-raw">{fixMermaidCode(explain.mermaidCode)}</pre>
              </div>
            )}
            {svg && (
              <div ref={svgNodeRef} className="bp-node-svg" dangerouslySetInnerHTML={{ __html: svg }} />
            )}
          </div>
        </div>
      )}

      {/* ── HUD: file info (top-left) ── */}
      {hasContent && (
        <div className="bp-hud bp-hud--tl">
          <strong className="bp-hud-name">{explain.name}</strong>
          {explain.summary && <p className="bp-hud-summary">{explain.summary}</p>}
          <div className="bp-hud-badges">
            {explain.language && explain.language !== "Unknown" && (
              <span className="bp-badge">{explain.language}</span>
            )}
            <span className="bp-badge bp-badge--blue">{explain.diagramType}</span>
            {explain.sizeBytes > 0 && (
              <span className="bp-badge">{formatBytes(explain.sizeBytes)}</span>
            )}
          </div>
        </div>
      )}

      {/* ── HUD: concepts (bottom-left) ── */}
      {hasContent && explain.concepts?.length > 0 && (
        <div className="bp-hud bp-hud--bl">
          <div className="bp-concepts-label">Key concepts</div>
          <div className="bp-concepts">
            {explain.concepts.map((c, i) => (
              <span key={i} className="bp-concept">{c}</span>
            ))}
          </div>
        </div>
      )}

      {/* ── Zoom controls (bottom-right, always visible) ── */}
      <div className="bp-controls">
        <button className="bp-ctrl-btn" onClick={() => zoomBy(1.2)} title="Zoom in">+</button>
        <span className="bp-ctrl-pct">{Math.round(transform.scale * 100)}%</span>
        <button className="bp-ctrl-btn" onClick={() => zoomBy(1 / 1.2)} title="Zoom out">−</button>
        <div className="bp-ctrl-sep" />
        <button className="bp-ctrl-btn" onClick={fitView} title="Reset view">⊡</button>
        {hasContent && onRegenerate && (
          <>
            <div className="bp-ctrl-sep" />
            <button className="bp-ctrl-btn" onClick={onRegenerate} title="Regenerate diagram">↻</button>
          </>
        )}
      </div>
    </div>
  );
}

// ─── Explain pane: owns fetching + caching for one file ─────────────────────
// Both editor panes (main + split) render one of these, so two diagrams can be
// generated and viewed side by side, each with its own loading/retry state.

function ExplainPane({ repoId, file, cacheRef }) {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [nonce, setNonce] = useState(0); // bumped by regenerate → bypasses cache

  const fileId = file?.id;

  useEffect(() => {
    if (!fileId) { setData(null); setLoading(false); setError(""); return; }
    const cacheKey = `${AI_PROVIDER}:${fileId}`;
    if (cacheRef.current.has(cacheKey)) {
      setData(cacheRef.current.get(cacheKey));
      setLoading(false); setError(""); return;
    }
    let cancelled = false;
    setLoading(true); setData(null); setError("");
    repoService.explainFile(repoId, fileId, AI_PROVIDER)
      .then((d) => { if (!cancelled) { cacheRef.current.set(cacheKey, d); setData(d); setLoading(false); } })
      .catch((err) => {
        if (!cancelled) {
          setError(err.response?.data?.error || err.response?.data?.message || "Failed to generate diagram.");
          setLoading(false);
        }
      });
    return () => { cancelled = true; };
  }, [repoId, fileId, nonce, cacheRef]);

  const regenerate = useCallback(() => {
    if (!fileId) return;
    cacheRef.current.delete(`${AI_PROVIDER}:${fileId}`);
    setNonce((n) => n + 1);
  }, [fileId, cacheRef]);

  return <BlueprintCanvas explain={data} loading={loading} error={error} onRegenerate={regenerate} />;
}

// ─── Main screen ──────────────────────────────────────────────────────────────

export default function RepoDetailScreen() {
  const { repoId } = useParams();
  const userId = useAuthStore((s) => s.user?.id);
  const [repoInfo, setRepoInfo] = useState(null);
  const [nodes, setNodes] = useState([]);
  const [status, setStatus] = useState(LOAD.LOADING);
  const [error, setError] = useState("");
  const [activeSection, setActiveSection] = useState("files");
  const [expandedFolders, setExpandedFolders] = useState(new Set());
  const [explorerOpen, setExplorerOpen] = useState(true);

  // VS Code-style editor tabs: every clicked file opens (or refocuses) a tab.
  const [openTabIds, setOpenTabIds] = useState([]);
  const [activeTabId, setActiveTabId] = useState(null);
  // Split view: one extra file rendered in a right-hand pane.
  const [splitFileId, setSplitFileId] = useState(null);

  const explainCache = useRef(new Map());

  const [testProvider, setTestProvider] = useState(AI_PROVIDER);
  const [analysisId, setAnalysisId] = useState("");
  const [testBusy, setTestBusy] = useState(false);
  const [testError, setTestError] = useState("");
  const [analysisResult, setAnalysisResult] = useState(null);
  const [stageResult, setStageResult] = useState(null);

  useEffect(() => {
    let mounted = true;
    let pollTimer = null;
    let retries = 0;
    const MAX_RETRIES = 80; // 80 × 1500 ms ≈ 2 minutes

    function applyTree(treeResponse) {
      const treeData = treeResponse?.nodes ?? treeResponse ?? [];
      setNodes(treeData);
      setExpandedFolders(new Set(collectFolderPaths(buildFileTree(treeData))));
      setStatus(LOAD.SUCCESS);
    }

    function pollUntilReady() {
      if (retries >= MAX_RETRIES) {
        if (mounted) {
          setError("Ingestion timed out — the repository may be too large or ingestion failed. Try re-submitting.");
          setStatus(LOAD.ERROR);
        }
        return;
      }
      retries++;
      pollTimer = setTimeout(async () => {
        if (!mounted) return;
        try {
          const treeResponse = await repoService.getRepoTree(repoId);
          if (!mounted) return;
          if (treeResponse?.source === "failed") {
            setError(treeResponse.message || "Repository ingestion failed. Try re-submitting.");
            setStatus(LOAD.ERROR);
          } else if (treeResponse?.source === "pending") {
            pollUntilReady();
          } else {
            applyTree(treeResponse);
          }
        } catch (err) {
          if (!mounted) return;
          const errData = err?.response?.data;
          if (errData?.source === "failed") {
            setError(errData.message || "Repository ingestion failed. Try re-submitting.");
            setStatus(LOAD.ERROR);
          } else {
            pollUntilReady();
          }
        }
      }, 1500);
    }

    async function load() {
      setStatus(LOAD.LOADING); setError("");
      try {
        const [repoResponse, treeResponse] = await Promise.all([
          repoService.getRepo(repoId),
          repoService.getRepoTree(repoId),
        ]);
        if (!mounted) return;

        setRepoInfo(repoResponse);
        const repoStatus = (repoResponse?.status || "").toUpperCase();
        if (repoStatus === "FAILED" || treeResponse?.source === "failed") {
          setError(
            repoResponse.errorMsg ||
            treeResponse?.message ||
            "Repository ingestion failed. Please try re-submitting."
          );
          setStatus(LOAD.ERROR);
          return;
        }

        if (treeResponse?.source === "pending") {
          setStatus(LOAD.PROCESSING);
          pollUntilReady();
        } else {
          applyTree(treeResponse);
        }
      } catch (err) {
        if (!mounted) return;
        setError(err.response?.data?.message || "Unable to load repository workspace.");
        setStatus(LOAD.ERROR);
      }
    }

    load();
    return () => {
      mounted = false;
      clearTimeout(pollTimer);
    };
  }, [repoId]);

  const files = nodes.filter((n) => n.type === "FILE");
  const folders = nodes.filter((n) => n.type === "DIRECTORY");
  const fileById = (id) => files.find((n) => n.id === id) || null;
  const openTabs = openTabIds.map(fileById).filter(Boolean);
  const activeFile = fileById(activeTabId);
  const splitFile = fileById(splitFileId);
  const activeDockItem = DOCK_ITEMS.find((item) => item.id === activeSection);
  const fileTree = buildFileTree(nodes);

  function openFile(id) {
    setOpenTabIds((prev) => (prev.includes(id) ? prev : [...prev, id]));
    setActiveTabId(id);
  }

  function closeTab(id, e) {
    e?.stopPropagation();
    setOpenTabIds((prev) => {
      const idx = prev.indexOf(id);
      const next = prev.filter((t) => t !== id);
      if (activeTabId === id) {
        // Focus the right neighbor, else the left one, else nothing (VS Code behavior)
        setActiveTabId(next[Math.min(idx, next.length - 1)] ?? null);
      }
      return next;
    });
  }

  function openToSide() {
    if (!activeTabId) return;
    setSplitFileId(activeTabId);
  }

  function toggleFolder(path) {
    setExpandedFolders((cur) => { const next = new Set(cur); next.has(path) ? next.delete(path) : next.add(path); return next; });
  }

  function handleActivityClick(id) {
    if (id === "files" && activeSection === "files") {
      // Clicking the active Explorer icon toggles the sidebar — full-screen diagrams
      setExplorerOpen((o) => !o);
      return;
    }
    setActiveSection(id);
    if (id === "files") setExplorerOpen(true);
  }

  function renderTreeItems(items, depth = 0) {
    return items.map((item) => {
      const isFolder = item.type === "DIRECTORY";
      const isExpanded = expandedFolders.has(item.path);
      const isSelected = item.id === activeTabId;
      return (
        <div key={item.path || item.id} className="ide-tree-group">
          <button
            type="button"
            className={`ide-tree-row ${isSelected ? "is-selected" : ""}`}
            style={{ "--tree-depth": depth }}
            title={item.path}
            onClick={() => { if (isFolder) toggleFolder(item.path); else openFile(item.id); }}
          >
            <span className={`ide-tree-chevron ${isFolder && isExpanded ? "is-open" : ""}`}>
              {isFolder && (
                <svg viewBox="0 0 16 16" aria-hidden="true">
                  <path d="M6 4l4 4-4 4" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
                </svg>
              )}
            </span>
            {isFolder ? <FolderIcon open={isExpanded} /> : <FileIcon name={item.name} />}
            <span className="ide-tree-name">{item.name}</span>
            {!isFolder && <span className="ide-tree-size">{formatBytes(item.sizeBytes)}</span>}
          </button>
          {isFolder && isExpanded && item.children.length > 0 && (
            <div className="ide-tree-children">{renderTreeItems(item.children, depth + 1)}</div>
          )}
        </div>
      );
    });
  }

  async function runStartAnalysis() {
    if (!userId) { setTestError("Missing user session."); return; }
    setTestBusy(true); setTestError("");
    try { const r = await repoService.startAnalysis({ repoId, userId, aiProvider: testProvider }); setAnalysisResult(r); setAnalysisId(r.id || ""); }
    catch (err) { setTestError(err.response?.data?.message || "Failed to start analysis."); }
    finally { setTestBusy(false); }
  }

  async function runFetchStages() {
    if (!analysisId.trim()) { setTestError("Enter analysis ID first."); return; }
    setTestBusy(true); setTestError("");
    try { setStageResult(await repoService.getAnalysisStages(analysisId.trim())); }
    catch (err) { setTestError(err.response?.data?.message || "Failed to fetch stages."); }
    finally { setTestBusy(false); }
  }

  const pathSegments = activeFile ? activeFile.path.split("/").filter(Boolean) : [];

  return (
    <div className="ide-root">
      {status === LOAD.LOADING && (
        <main className="workspace-state"><span className="workspace-loader" /><p>Opening workspace...</p></main>
      )}
      {status === LOAD.PROCESSING && (
        <main className="workspace-state"><span className="workspace-loader" /><p>Building repository workspace, this may take a moment...</p></main>
      )}
      {status === LOAD.ERROR && (
        <main className="workspace-state is-error"><p>{error}</p><Link to="/analyze">Return to scan screen</Link></main>
      )}
      {status === LOAD.SUCCESS && (
        <>
          <div className="ide-frame">
            {/* ── Activity bar (far left) ── */}
            <nav className="ide-activitybar" aria-label="Workspace navigation">
              {DOCK_ITEMS.map((item) => (
                <button
                  key={item.id}
                  type="button"
                  className={`ide-activity-btn ${activeSection === item.id ? "is-active" : ""}`}
                  onClick={() => handleActivityClick(item.id)}
                  title={item.id === "files" && activeSection === "files"
                    ? (explorerOpen ? "Hide Explorer" : "Show Explorer")
                    : item.label}
                >
                  <ActivityIcon id={item.id} />
                </button>
              ))}
              <div className="ide-activity-spacer" />
              <Link className="ide-activity-btn ide-activity-home" to="/analyze" title="Back to dashboard">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                  <path d="M3 11.5 12 4l9 7.5" />
                  <path d="M5.5 10v9.5h13V10" />
                </svg>
              </Link>
            </nav>

            {/* ── Explorer sidebar (collapsible) ── */}
            {activeSection === "files" && explorerOpen && (
              <aside className="ide-explorer">
                <div className="ide-explorer-title">Explorer</div>
                <div className="ide-explorer-repo">
                  <svg className="ide-explorer-repo-icon" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.2" aria-hidden="true">
                    <path d="M3 1.8h8.2a1.6 1.6 0 0 1 1.6 1.6v9.2a1.6 1.6 0 0 1-1.6 1.6H4.6A1.6 1.6 0 0 1 3 12.6z" />
                    <path d="M3 11.4h9.8M5.6 4.6h4.8" strokeLinecap="round" />
                  </svg>
                  <span className="ide-explorer-repo-name">{repoInfo?.name || "Repository"}</span>
                  <span className="ide-explorer-repo-meta">{files.length}</span>
                </div>
                <div className="ide-tree">{renderTreeItems(fileTree)}</div>
              </aside>
            )}

            {/* ── Editor area ── */}
            <section className="ide-editor">
              {activeSection === "files" && (
                <>
                  <div className="ide-tabbar">
                    <div className="ide-tabbar-tabs">
                      {openTabs.length > 0 ? (
                        openTabs.map((tab) => (
                          <div
                            key={tab.id}
                            role="tab"
                            tabIndex={0}
                            aria-selected={tab.id === activeTabId}
                            className={`ide-tab ${tab.id === activeTabId ? "is-active" : ""}`}
                            title={tab.path}
                            onClick={() => setActiveTabId(tab.id)}
                            onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") setActiveTabId(tab.id); }}
                            onMouseDown={(e) => { if (e.button === 1) closeTab(tab.id, e); }}
                          >
                            <FileIcon name={tab.name} />
                            <span className="ide-tab-name">{tab.name}</span>
                            <button
                              type="button"
                              className="ide-tab-close"
                              title="Close (middle-click also works)"
                              onClick={(e) => closeTab(tab.id, e)}
                            >
                              <CloseIcon />
                            </button>
                          </div>
                        ))
                      ) : (
                        <div className="ide-tab-hint">Select a file to visualize its architecture</div>
                      )}
                    </div>
                    <div className="ide-tabbar-actions">
                      <span className="ide-engine-chip" title="Diagrams are generated by Groq (gpt-oss-120b) with automatic key rotation">
                        <span className="ide-engine-dot" />
                        Groq · GPT-OSS 120B
                      </span>
                      <button
                        type="button"
                        className="ide-icon-btn"
                        title="Open active file to the side"
                        disabled={!activeFile}
                        onClick={openToSide}
                      >
                        <SplitIcon />
                      </button>
                      <button
                        type="button"
                        className="ide-icon-btn"
                        title={explorerOpen ? "Hide Explorer (full-screen diagrams)" : "Show Explorer"}
                        onClick={() => setExplorerOpen((o) => !o)}
                      >
                        <SidebarIcon />
                      </button>
                    </div>
                  </div>

                  <div className={`ide-breadcrumbs ${pathSegments.length === 0 ? "is-empty" : ""}`}>
                    {pathSegments.length > 0 ? (
                      pathSegments.map((seg, i) => (
                        <span key={`${seg}-${i}`} className="ide-crumb">
                          {i === pathSegments.length - 1 && <FileIcon name={seg} />}
                          <span className={i === pathSegments.length - 1 ? "ide-crumb-leaf" : ""}>{seg}</span>
                          {i < pathSegments.length - 1 && (
                            <svg className="ide-crumb-sep" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                              <path d="M6 4l4 4-4 4" />
                            </svg>
                          )}
                        </span>
                      ))
                    ) : (
                      <span className="ide-crumb-placeholder">{repoInfo ? `${repoInfo.owner} / ${repoInfo.name}` : "workspace"}</span>
                    )}
                  </div>

                  <div className="ide-panes">
                    <div className="ide-pane">
                      <div className="ide-pane-body">
                        <ExplainPane repoId={repoId} file={activeFile} cacheRef={explainCache} />
                      </div>
                    </div>
                    {splitFile && (
                      <div className="ide-pane ide-pane--split">
                        <div className="ide-pane-head">
                          <FileIcon name={splitFile.name} />
                          <span className="ide-pane-head-name" title={splitFile.path}>{splitFile.name}</span>
                          <button
                            type="button"
                            className="ide-tab-close"
                            title="Close split view"
                            onClick={() => setSplitFileId(null)}
                          >
                            <CloseIcon />
                          </button>
                        </div>
                        <div className="ide-pane-body">
                          <ExplainPane repoId={repoId} file={splitFile} cacheRef={explainCache} />
                        </div>
                      </div>
                    )}
                  </div>
                </>
              )}

              {activeSection === "settings" && (
                <div className="workspace-test-area">
                  <div className="workspace-test-head">
                    <h3>Pipeline Testing</h3>
                    <p>Run analysis APIs for this repository without leaving the workspace.</p>
                  </div>
                  <div className="workspace-test-grid">
                    <label>AI Provider<input value={testProvider} onChange={(e) => setTestProvider(e.target.value)} /></label>
                    <label>Analysis ID<input value={analysisId} onChange={(e) => setAnalysisId(e.target.value)} placeholder="auto-filled after start analysis" /></label>
                  </div>
                  <div className="workspace-test-actions">
                    <button type="button" onClick={runStartAnalysis} disabled={testBusy}>Start Analysis</button>
                    <button type="button" onClick={runFetchStages} disabled={testBusy}>Get Stages</button>
                  </div>
                  {testError && <p className="workspace-test-error">{testError}</p>}
                  <div className="workspace-test-results">
                    <ResultBox title="Analysis Response" value={analysisResult} />
                    <ResultBox title="Stage Response" value={stageResult} />
                  </div>
                </div>
              )}

              {activeSection !== "files" && activeSection !== "settings" && (
                <div className="ide-coming-soon">
                  <div className="ide-coming-soon-icon"><ActivityIcon id={activeSection} /></div>
                  <h3>{activeDockItem?.label}</h3>
                  <p>This panel ships in a future release. The Explorer is where the magic happens today.</p>
                </div>
              )}
            </section>
          </div>

          {/* ── Status bar ── */}
          <footer className="ide-statusbar">
            <div className="ide-status-group">
              <span className="ide-status-item ide-status-item--accent">
                <BranchIcon />
                {repoInfo ? `${repoInfo.owner}/${repoInfo.name}` : "workspace"}
              </span>
              <span className="ide-status-item">{files.length} files</span>
              <span className="ide-status-item">{folders.length} folders</span>
              {repoInfo?.sizeKb > 0 && (
                <span className="ide-status-item">{formatBytes(repoInfo.sizeKb * 1024)}</span>
              )}
            </div>
            <div className="ide-status-group">
              {openTabs.length > 0 && (
                <span className="ide-status-item">{openTabs.length} open {openTabs.length === 1 ? "tab" : "tabs"}</span>
              )}
              {activeFile && (
                <span className="ide-status-item">
                  {activeFile.name} · {formatBytes(activeFile.sizeBytes)}
                </span>
              )}
              <span className="ide-status-item ide-status-item--engine">Groq</span>
              <span className="ide-status-item ide-status-brand">RepoMind</span>
            </div>
          </footer>
        </>
      )}
    </div>
  );
}

function ResultBox({ title, value }) {
  return (
    <article className="workspace-result-box">
      <h4>{title}</h4>
      <pre>{value ? JSON.stringify(value, null, 2) : "No data yet."}</pre>
    </article>
  );
}
