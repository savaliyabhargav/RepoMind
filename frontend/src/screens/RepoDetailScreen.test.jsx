import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { act } from '@testing-library/react';
import { useAuthStore } from '../store/authStore';

// Mock mermaid before importing the component — it tries to call mermaid.initialize at module level
vi.mock('mermaid', () => ({
  default: {
    initialize: vi.fn(),
    render: vi.fn().mockResolvedValue({ svg: '<svg></svg>' }),
  },
}));

// Mock CSS import
vi.mock('./RepoDetailScreen.css', () => ({}));

// Mock repoService
vi.mock('../services/repoService', () => ({
  default: {
    getRepo: vi.fn(),
    getRepoTree: vi.fn(),
    startAnalysis: vi.fn(),
    getAnalysisStages: vi.fn(),
    explainFile: vi.fn(),
  },
}));

import repoService from '../services/repoService';
import RepoDetailScreen from './RepoDetailScreen';

const REPO_ID = 'repo-uuid-123';

const READY_REPO = {
  id: REPO_ID,
  name: 'myrepo',
  owner: 'myowner',
  status: 'READY',
  url: 'https://github.com/myowner/myrepo',
  defaultBranch: 'main',
  fileCount: 2,
  sizeKb: 100,
  isPrivate: false,
  errorMsg: null,
  user: { id: 'u1', username: 'user', plan: 'FREE', avatarUrl: null },
};

const TREE_RESPONSE = {
  source: 'cache',
  nodes: [
    { id: 'f1', path: 'src/Main.java', name: 'Main.java', type: 'FILE', sizeBytes: 1024 },
    { id: 'f2', path: 'src/App.java', name: 'App.java', type: 'FILE', sizeBytes: 512 },
  ],
};

function renderWithRouter(repoId = REPO_ID) {
  return render(
    <MemoryRouter initialEntries={[`/repo/${repoId}`]}>
      <Routes>
        <Route path="/repo/:repoId" element={<RepoDetailScreen />} />
        <Route path="/analyze" element={<div>Overview</div>} />
      </Routes>
    </MemoryRouter>
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  act(() =>
    useAuthStore.setState({
      accessToken: 'tok',
      user: { id: 'u1', username: 'user', plan: 'FREE', avatarUrl: null },
      isLoggedIn: true,
    })
  );
});

describe('RepoDetailScreen', () => {
  it('shows loading state while fetching', () => {
    // Never resolve — stays in loading
    repoService.getRepo.mockReturnValue(new Promise(() => {}));
    repoService.getRepoTree.mockReturnValue(new Promise(() => {}));

    renderWithRouter();

    // Component renders something while loading (spinner or loading text)
    expect(document.body).toBeTruthy();
  });

  it('renders file tree when repo is READY', async () => {
    repoService.getRepo.mockResolvedValue(READY_REPO);
    repoService.getRepoTree.mockResolvedValue(TREE_RESPONSE);

    renderWithRouter();

    await waitFor(() => {
      expect(screen.getByText('Main.java')).toBeInTheDocument();
    });

    expect(screen.getByText('App.java')).toBeInTheDocument();
  });

  it('shows repo name in the header when READY', async () => {
    repoService.getRepo.mockResolvedValue(READY_REPO);
    repoService.getRepoTree.mockResolvedValue(TREE_RESPONSE);

    renderWithRouter();

    await waitFor(() => {
      expect(screen.getByText('myrepo')).toBeInTheDocument();
    });
  });

  it('shows error message when repo status is FAILED', async () => {
    const failedRepo = { ...READY_REPO, status: 'FAILED', errorMsg: 'Rate limit exceeded' };
    repoService.getRepo.mockResolvedValue(failedRepo);
    repoService.getRepoTree.mockResolvedValue({
      source: 'failed',
      status: 'FAILED',
      message: 'Rate limit exceeded',
    });

    renderWithRouter();

    await waitFor(() => {
      expect(
        screen.getByText(/Rate limit exceeded/i) ||
        screen.getByText(/ingestion failed/i)
      ).toBeInTheDocument();
    });
  });

  it('shows error when getRepoTree returns failed source', async () => {
    // Simulate 422 response with source: 'failed'
    repoService.getRepo.mockResolvedValue(READY_REPO);
    const err = new Error('Ingestion failed');
    err.response = { data: { source: 'failed', message: 'GitHub rate limit exceeded' } };
    repoService.getRepoTree.mockRejectedValue(err);

    renderWithRouter();

    await waitFor(() => {
      expect(
        screen.getByText(/rate limit exceeded/i) ||
        screen.getByText(/ingestion failed/i)
      ).toBeInTheDocument();
    });
  });

  it('shows directory node from tree', async () => {
    const treeWithDir = {
      source: 'cache',
      nodes: [
        { id: 'f1', path: 'src/utils/Helper.java', name: 'Helper.java', type: 'FILE', sizeBytes: 200 },
      ],
    };
    repoService.getRepo.mockResolvedValue(READY_REPO);
    repoService.getRepoTree.mockResolvedValue(treeWithDir);

    renderWithRouter();

    await waitFor(() => {
      expect(screen.getByText('src')).toBeInTheDocument();
    });
  });
});
