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

function buildFileTree(nodes) {
  const root = {
    id: "root",
    name: "Repository",
    path: "",
    type: "DIRECTORY",
    children: new Map(),
  };

  nodes.forEach((node) => {
    const parts = node.path.split("/").filter(Boolean);
    let current = root;

    parts.forEach((part, index) => {
      const isLeaf = index === parts.length - 1;
      const path = parts.slice(0, index + 1).join("/");

      if (!current.children.has(part)) {
        current.children.set(part, {
          id: isLeaf ? node.id : path,
          name: part,
          path,
          type: isLeaf ? node.type : "DIRECTORY",
          sizeBytes: isLeaf ? node.sizeBytes : 0,
          children: new Map(),
        });
      }

      const next = current.children.get(part);
      if (isLeaf) {
        next.id = node.id;
        next.type = node.type;
        next.sizeBytes = node.sizeBytes;
      }
      current = next;
    });
  });

  function sortChildren(item) {
    return Array.from(item.children.values())
      .sort((a, b) => {
        if (a.type !== b.type) return a.type === "DIRECTORY" ? -1 : 1;
        return a.name.localeCompare(b.name);
      })
      .map((child) => ({
        ...child,
        children: sortChildren(child),
      }));
  }

  return sortChildren(root);
}

function collectFolderPaths(items, paths = []) {
  items.forEach((item) => {
    if (item.type === "DIRECTORY") {
      paths.push(item.path);
      collectFolderPaths(item.children, paths);
    }
  });
  return paths;
}

export default function RepoDetailScreen() {
  const { repoId } = useParams();
  const [nodes, setNodes] = useState([]);
  const [status, setStatus] = useState(LOAD.LOADING);
  const [error, setError] = useState("");
  const [activeSection, setActiveSection] = useState("files");
  const [selectedFileId, setSelectedFileId] = useState(null);
  const [expandedFolders, setExpandedFolders] = useState(new Set());

  useEffect(() => {
    let isMounted = true;

    async function loadRepoDetail() {
      setStatus(LOAD.LOADING);
      setError("");

      try {
        const [, treeData] = await Promise.all([
          repoService.getRepo(repoId),
          repoService.getRepoTree(repoId),
        ]);

        if (!isMounted) return;
        const tree = buildFileTree(treeData);
        setNodes(treeData);
        setExpandedFolders(new Set(collectFolderPaths(tree)));
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
  const fileTree = buildFileTree(nodes);

  function toggleFolder(path) {
    setExpandedFolders((current) => {
      const next = new Set(current);
      if (next.has(path)) {
        next.delete(path);
      } else {
        next.add(path);
      }
      return next;
    });
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
            onClick={() => {
              if (isFolder) {
                toggleFolder(item.path);
              } else {
                setSelectedFileId(item.id);
              }
            }}
          >
            <span className={`workspace-tree-chevron ${isFolder && isExpanded ? "is-open" : ""}`}>
              {isFolder ? ">" : ""}
            </span>
            <span className="workspace-tree-name">{item.name}</span>
            {!isFolder && <span className="workspace-tree-size">{formatBytes(item.sizeBytes)}</span>}
          </button>

          {isFolder && isExpanded && item.children.length > 0 && (
            <div className="workspace-tree-children">
              {renderTreeItems(item.children, depth + 1)}
            </div>
          )}
        </div>
      );
    });
  }

  return (
    <div className="workspace-root">
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

      {status === LOAD.SUCCESS && (
        <main className={`workspace-shell ${activeSection !== "files" ? "is-blank-section" : ""}`}>
          {activeSection === "files" && (
            <aside className="workspace-file-panel">
              <div className="workspace-file-panel-head">
                <span>Files</span>
                <strong>{files.length} files / {folders.length} folders</strong>
              </div>

              <div className="workspace-file-scroll">
                {renderTreeItems(fileTree)}
              </div>
            </aside>
          )}

          <section className="workspace-main-panel">
            <div className="workspace-main-head">
              <div>
                <span>{activeDockItem?.label}</span>
                <strong>{selectedFile ? selectedFile.name : "Workspace"}</strong>
              </div>
              {activeSection === "files" && selectedFile && <span>{selectedFile.path}</span>}
            </div>

            <div className="workspace-empty-area" />
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
            aria-label={item.label}
          >
            <span>{item.symbol}</span>
          </button>
        ))}
      </nav>
    </div>
  );
}
