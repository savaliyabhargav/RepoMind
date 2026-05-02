import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import repoService from "../services/repoService";
import "./RepoDetailScreen.css";

const LOAD = {
  LOADING: "loading",
  SUCCESS: "success",
  ERROR: "error",
};

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
  const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
  const value = bytes / 1024 ** index;
  return `${value.toFixed(value >= 10 || index === 0 ? 0 : 1)} ${units[index]}`;
}

function getFileExtension(path = "") {
  const name = path.split("/").pop() || "";
  const index = name.lastIndexOf(".");
  return index > 0 ? name.slice(index + 1).toUpperCase() : "FILE";
}

export default function RepoDetailScreen() {
  const { repoId } = useParams();
  const [repo, setRepo] = useState(null);
  const [nodes, setNodes] = useState([]);
  const [status, setStatus] = useState(LOAD.LOADING);
  const [error, setError] = useState("");
  const [activeSection, setActiveSection] = useState("files");
  const [selectedFileId, setSelectedFileId] = useState(null);

  useEffect(() => {
    let isMounted = true;

    async function loadRepoDetail() {
      setStatus(LOAD.LOADING);
      setError("");

      try {
        const [repoData, treeData] = await Promise.all([
          repoService.getRepo(repoId),
          repoService.getRepoTree(repoId),
        ]);

        if (!isMounted) return;
        setRepo(repoData);
        setNodes(treeData);
        setStatus(LOAD.SUCCESS);
      } catch (err) {
        if (!isMounted) return;
        setError(err.response?.data?.message || "Unable to load repository workspace.");
        setStatus(LOAD.ERROR);
      }
    }

    loadRepoDetail();

    return () => {
      isMounted = false;
    };
  }, [repoId]);

  const files = nodes.filter((node) => node.type === "FILE");
  const folders = nodes.filter((node) => node.type === "DIRECTORY");
  const selectedFile = files.find((node) => node.id === selectedFileId);
  const activeDockItem = DOCK_ITEMS.find((item) => item.id === activeSection);

  return (
    <div className="workspace-root">
      <div className="workspace-wallpaper" />

      <header className="workspace-menu-bar">
        <div className="workspace-menu-left">
          <span className="workspace-apple">RepoMind</span>
          <span>Workspace</span>
          <span>File</span>
          <span>View</span>
        </div>
        <div className="workspace-menu-right">
          <span>{repo?.status || "Loading"}</span>
          <Link to="/analyze">New scan</Link>
        </div>
      </header>

      {status === LOAD.LOADING && (
        <main className="workspace-state">
          <span className="workspace-loader" />
          <p>Opening workspace...</p>
        </main>
      )}

      {status === LOAD.ERROR && (
        <main className="workspace-state is-error">
          <p>{error}</p>
          <Link to="/analyze">Return to scan screen</Link>
        </main>
      )}

      {status === LOAD.SUCCESS && repo && (
        <main className="workspace-window">
          <section className="workspace-titlebar">
            <div className="workspace-traffic">
              <span className="traffic-red" />
              <span className="traffic-yellow" />
              <span className="traffic-green" />
            </div>
            <div className="workspace-title">
              <strong>{repo.owner}/{repo.name}</strong>
              <span>{repo.defaultBranch || "main"} branch</span>
            </div>
            <a href={repo.url} target="_blank" rel="noreferrer">GitHub</a>
          </section>

          <section className="workspace-content">
            {activeSection === "files" && (
              <aside className="workspace-sidebar">
                <div className="workspace-sidebar-head">
                  <span>Explorer</span>
                  <strong>{files.length} files</strong>
                </div>

                <div className="workspace-file-list">
                  {nodes.map((node) => {
                    const isFile = node.type === "FILE";
                    const isSelected = node.id === selectedFileId;

                    return (
                      <button
                        key={node.id}
                        type="button"
                        className={`workspace-file-row ${isFile ? "is-file" : "is-folder"} ${isSelected ? "is-selected" : ""}`}
                        style={{ "--node-depth": node.depth ?? 0 }}
                        onClick={() => isFile && setSelectedFileId(node.id)}
                        disabled={!isFile}
                      >
                        <span className="workspace-file-icon">{isFile ? getFileExtension(node.path) : "DIR"}</span>
                        <span className="workspace-file-name">{node.path}</span>
                        {isFile && <span className="workspace-file-size">{formatBytes(node.sizeBytes)}</span>}
                      </button>
                    );
                  })}
                </div>
              </aside>
            )}

            <section className="workspace-stage">
              <div className="workspace-stage-toolbar">
                <div>
                  <span>{activeDockItem?.label}</span>
                  <strong>{selectedFile ? selectedFile.name : "Workspace"}</strong>
                </div>
                <span>{files.length} files / {folders.length} folders</span>
              </div>

              <div className="workspace-blank-canvas">
                {activeSection === "files" && selectedFile && (
                  <div className="workspace-selection-hint">
                    <span>{selectedFile.path}</span>
                  </div>
                )}
              </div>
            </section>
          </section>
        </main>
      )}

      <nav className="workspace-dock" aria-label="Workspace navigation">
        {DOCK_ITEMS.map((item) => (
          <button
            key={item.id}
            type="button"
            className={`workspace-dock-item ${activeSection === item.id ? "is-active" : ""}`}
            onClick={() => setActiveSection(item.id)}
            title={item.label}
            aria-label={item.label}
          >
            <span>{item.symbol}</span>
          </button>
        ))}
      </nav>
    </div>
  );
}
