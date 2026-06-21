import { useCallback, useEffect, useRef, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { useAuthStore } from "../store/authStore";
import repoService from "../services/repoService";
import mermaid from "mermaid";
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

const LOAD = { LOADING: "loading", SUCCESS: "success", ERROR: "error" };

const DOCK_ITEMS = [
  { id: "files", label: "Files", symbol: "F" },
  { id: "overview", label: "Overview", symbol: "O" },
  { id: "uml", label: "UML", symbol: "U" },
  { id: "chat", label: "Chat", symbol: "C" },
  { id: "settings", label: "Settings", symbol: "S" },
];

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

// ─── Mermaid sanitizer ───────────────────────────────────────────────────────
// Models often emit subtly broken Mermaid. Fix the most common mistakes before
// passing to mermaid.render() so diagrams render instead of showing raw code.
function fixMermaidCode(raw) {
  if (!raw) return raw;
  let s = raw
    // Some models double-escape newlines: literal \n → actual newline
    .replace(/\\n/g, "\n")
    // Strip markdown fences that slipped through JSON cleanup
    .replace(/^```(?:mermaid)?\s*/m, "")
    .replace(/\n?```\s*$/m, "")
    .trim();

  // classDiagram: GPT-style models often use colon syntax which Mermaid rejects.
  // Wrong: +fieldName: Type  →  Correct: +fieldName Type
  // Wrong: +method(p: T): R  →  Correct: +method(p T) R
  if (/classDiagram/.test(s)) {
    s = s
      // Fix method param colons: (param: Type, p2: T) → (param Type, p2 T)
      .replace(/\(([^)]*)\)/g, (_, params) =>
        "(" + params.replace(/(\w[\w<>[\]*?]*)\s*:\s*/g, "$1 ") + ")"
      )
      // Fix method return colon: +method(...): ReturnType → +method(...) ReturnType
      .replace(/(\))\s*:\s*(\S)/g, "$1 $2")
      // Fix field colon: +fieldName: Type → +fieldName Type (only at line level)
      .replace(/^(\s+[+\-#~][\w<>[\]*?]+)\s*:\s*(.+)$/gm, "$1 $2");
  }

  // sequenceDiagram: strip colons from Note text which the parser mis-tokenises
  // (keep the "Note over/right/left" keyword intact, just clean the value)
  // Nothing to do here yet — add as needed.

  return s;
}

// ─── Blueprint Infinite Canvas ────────────────────────────────────────────────

function BlueprintCanvas({ explain, loading, error }) {
  const viewportRef = useRef(null);
  const [transform, setTransform] = useState({ x: 0, y: 0, scale: 1 });
  const isDragging = useRef(false);
  const lastMouse = useRef({ x: 0, y: 0 });

  const [svg, setSvg] = useState("");
  const [svgError, setSvgError] = useState(false);
  const [svgRenderErr, setSvgRenderErr] = useState("");
  const mermaidSeq = useRef(0);
  const svgNodeRef = useRef(null);

  // Center view on first mount
  useEffect(() => {
    const vp = viewportRef.current;
    if (vp) setTransform({ x: Math.round(vp.clientWidth / 2), y: Math.round(vp.clientHeight / 2), scale: 1 });
  }, []);

  // Re-center and re-render when explain changes
  useEffect(() => {
    setSvg("");
    setSvgError(false);
    setSvgRenderErr("");
    if (!explain?.mermaidCode) return;

    const vp = viewportRef.current;
    if (vp) setTransform({ x: Math.round(vp.clientWidth / 2), y: Math.round(vp.clientHeight / 2), scale: 1 });

    let active = true;
    const id = `bp-${Date.now()}-${++mermaidSeq.current}`;
    const cleaned = fixMermaidCode(explain.mermaidCode);
    mermaid
      .render(id, cleaned)
      .then(({ svg: s }) => { if (active) setSvg(s); })
      .catch((err) => {
        if (!active) return;
        const msg = err?.message || String(err);
        console.error("[mermaid render error]", msg, "\n--- code ---\n", cleaned);
        setSvgRenderErr(msg);
        setSvgError(true);
      });
    return () => { active = false; };
  }, [explain?.fileId, explain?.mermaidCode]);

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
          <div className="bp-state-icon">⬡</div>
          <p>Select a file from the panel to see its visual explanation</p>
        </div>
      )}

      {loading && (
        <div className="bp-state-overlay">
          <span className="workspace-loader" />
          <p>Generating diagram<span className="bp-ellipsis" /></p>
        </div>
      )}

      {error && !loading && (
        <div className="bp-state-overlay bp-state-overlay--error">
          <div className="bp-state-icon">⚠</div>
          <p>{error}</p>
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
                <p className="bp-node-raw-label">Render failed — check console for details</p>
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
      </div>
    </div>
  );
}

// ─── Main screen ──────────────────────────────────────────────────────────────

export default function RepoDetailScreen() {
  const { repoId } = useParams();
  const userId = useAuthStore((s) => s.user?.id);
  const [nodes, setNodes] = useState([]);
  const [status, setStatus] = useState(LOAD.LOADING);
  const [error, setError] = useState("");
  const [activeSection, setActiveSection] = useState("files");
  const [selectedFileId, setSelectedFileId] = useState(null);
  const [expandedFolders, setExpandedFolders] = useState(new Set());

  const explainCache = useRef(new Map());
  const [explainData, setExplainData] = useState(null);
  const [explainLoading, setExplainLoading] = useState(false);
  const [explainError, setExplainError] = useState("");

  const [aiProvider, setAiProvider] = useState("GROQ");
  const [analysisId, setAnalysisId] = useState("");
  const [testBusy, setTestBusy] = useState(false);
  const [testError, setTestError] = useState("");
  const [analysisResult, setAnalysisResult] = useState(null);
  const [stageResult, setStageResult] = useState(null);

  useEffect(() => {
    let mounted = true;
    async function load() {
      setStatus(LOAD.LOADING); setError("");
      try {
        const [, treeData] = await Promise.all([repoService.getRepo(repoId), repoService.getRepoTree(repoId)]);
        if (!mounted) return;
        setNodes(treeData);
        setExpandedFolders(new Set(collectFolderPaths(buildFileTree(treeData))));
        setStatus(LOAD.SUCCESS);
      } catch (err) {
        if (!mounted) return;
        setError(err.response?.data?.message || "Unable to load repository workspace.");
        setStatus(LOAD.ERROR);
      }
    }
    load();
    return () => { mounted = false; };
  }, [repoId]);

  useEffect(() => {
    if (!selectedFileId) { setExplainData(null); setExplainLoading(false); setExplainError(""); return; }
    if (explainCache.current.has(selectedFileId)) {
      setExplainData(explainCache.current.get(selectedFileId));
      setExplainLoading(false); setExplainError(""); return;
    }
    let cancelled = false;
    setExplainLoading(true); setExplainData(null); setExplainError("");
    repoService.explainFile(repoId, selectedFileId, aiProvider)
      .then((data) => { if (!cancelled) { explainCache.current.set(selectedFileId, data); setExplainData(data); setExplainLoading(false); } })
      .catch((err) => { if (!cancelled) { setExplainError(err.response?.data?.message || "Failed to generate diagram."); setExplainLoading(false); } });
    return () => { cancelled = true; };
  }, [selectedFileId, repoId, aiProvider]);

  const files = nodes.filter((n) => n.type === "FILE");
  const folders = nodes.filter((n) => n.type === "DIRECTORY");
  const selectedFile = files.find((n) => n.id === selectedFileId);
  const activeDockItem = DOCK_ITEMS.find((item) => item.id === activeSection);
  const fileTree = buildFileTree(nodes);

  function toggleFolder(path) {
    setExpandedFolders((cur) => { const next = new Set(cur); next.has(path) ? next.delete(path) : next.add(path); return next; });
  }

  function renderTreeItems(items, depth = 0) {
    return items.map((item) => {
      const isFolder = item.type === "DIRECTORY";
      const isExpanded = expandedFolders.has(item.path);
      const isSelected = item.id === selectedFileId;
      return (
        <div key={item.path || item.id} className="workspace-tree-group">
          <button
            type="button"
            className={`workspace-tree-row ${isFolder ? "is-folder" : "is-file"} ${isSelected ? "is-selected" : ""}`}
            style={{ "--tree-depth": depth }}
            onClick={() => { if (isFolder) toggleFolder(item.path); else setSelectedFileId(item.id); }}
          >
            <span className={`workspace-tree-chevron ${isFolder && isExpanded ? "is-open" : ""}`}>{isFolder ? ">" : ""}</span>
            <span className="workspace-tree-name">{item.name}</span>
            {!isFolder && <span className="workspace-tree-size">{formatBytes(item.sizeBytes)}</span>}
          </button>
          {isFolder && isExpanded && item.children.length > 0 && (
            <div className="workspace-tree-children">{renderTreeItems(item.children, depth + 1)}</div>
          )}
        </div>
      );
    });
  }

  async function runStartAnalysis() {
    if (!userId) { setTestError("Missing user session."); return; }
    setTestBusy(true); setTestError("");
    try { const r = await repoService.startAnalysis({ repoId, userId, aiProvider }); setAnalysisResult(r); setAnalysisId(r.id || ""); }
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

  return (
    <div className="workspace-root">
      {status === LOAD.LOADING && (
        <main className="workspace-state"><span className="workspace-loader" /><p>Opening workspace...</p></main>
      )}
      {status === LOAD.ERROR && (
        <main className="workspace-state is-error"><p>{error}</p><Link to="/analyze">Return to scan screen</Link></main>
      )}
      {status === LOAD.SUCCESS && (
        <main className={`workspace-shell ${activeSection !== "files" ? "is-blank-section" : ""}`}>
          {activeSection === "files" && (
            <aside className="workspace-file-panel">
              <div className="workspace-file-panel-head">
                <span>Files</span>
                <strong>{files.length} files / {folders.length} folders</strong>
              </div>
              <div className="workspace-file-scroll">{renderTreeItems(fileTree)}</div>
            </aside>
          )}

          <section className="workspace-main-panel">
            <div className="workspace-main-head">
              <div>
                <span>{activeDockItem?.label}</span>
                <strong>{selectedFile ? selectedFile.name : "Workspace"}</strong>
              </div>
              {activeSection === "files" && (
                <div className="bp-provider-toggle">
                  {["GROQ", "GEMINI", "NVIDIA_DEV"].map((p) => (
                    <button
                      key={p}
                      type="button"
                      className={`bp-provider-btn ${aiProvider === p ? "is-active" : ""}`}
                      onClick={() => { setAiProvider(p); setSelectedFileId(null); explainCache.current.clear(); }}
                    >
                      {p === "GROQ" ? "Groq" : p === "GEMINI" ? "Gemini" : "NVIDIA"}
                    </button>
                  ))}
                </div>
              )}
            </div>

            {activeSection === "files" && (
              <BlueprintCanvas
                explain={explainData}
                loading={explainLoading}
                error={explainError}
              />
            )}

            {activeSection === "settings" && (
              <div className="workspace-test-area">
                <div className="workspace-test-head">
                  <h3>Pipeline Testing</h3>
                  <p>Run analysis APIs for this repository without leaving the workspace.</p>
                </div>
                <div className="workspace-test-grid">
                  <label>AI Provider<input value={aiProvider} onChange={(e) => setAiProvider(e.target.value)} /></label>
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
              <div className="workspace-empty-area" />
            )}
          </section>
        </main>
      )}

      <div className="workspace-dock-hotspot" />
      <nav className="workspace-dock" aria-label="Workspace navigation">
        {DOCK_ITEMS.map((item) => (
          <button
            key={item.id}
            type="button"
            className={`workspace-dock-item ${activeSection === item.id ? "is-active" : ""}`}
            onClick={() => setActiveSection(item.id)}
            title={item.label}
          >
            <span>{item.symbol}</span>
          </button>
        ))}
      </nav>
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
