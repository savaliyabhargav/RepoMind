import { describe, it, expect, vi, beforeEach } from 'vitest';

// Mock the api instance exported from authService before importing repoService
vi.mock('./authService', () => ({
  api: {
    post: vi.fn(),
    get: vi.fn(),
  },
  default: {},
}));

import { api } from './authService';
import repoService from './repoService';

beforeEach(() => {
  vi.clearAllMocks();
});

describe('repoService.ingestRepo', () => {
  it('POSTs to /repo/ingest with url and userId', async () => {
    api.post.mockResolvedValue({ data: { repoId: 'r1', status: 'PENDING' } });

    const result = await repoService.ingestRepo('https://github.com/o/r', 'user-1');

    expect(api.post).toHaveBeenCalledWith('/repo/ingest', {
      url: 'https://github.com/o/r',
      userId: 'user-1',
    });
    expect(result.repoId).toBe('r1');
    expect(result.status).toBe('PENDING');
  });
});

describe('repoService.listRepos', () => {
  it('GETs /repo with userId param', async () => {
    api.get.mockResolvedValue({ data: [{ id: 'r1' }, { id: 'r2' }] });

    const result = await repoService.listRepos('user-42');

    expect(api.get).toHaveBeenCalledWith('/repo', { params: { userId: 'user-42' } });
    expect(result).toHaveLength(2);
  });
});

describe('repoService.getRepo', () => {
  it('GETs /repo/:repoId', async () => {
    api.get.mockResolvedValue({ data: { id: 'r1', status: 'READY' } });

    const result = await repoService.getRepo('r1');

    expect(api.get).toHaveBeenCalledWith('/repo/r1');
    expect(result.status).toBe('READY');
  });
});

describe('repoService.getRepoTree', () => {
  it('GETs /repo/:repoId/tree', async () => {
    api.get.mockResolvedValue({ data: { source: 'cache', nodes: [] } });

    const result = await repoService.getRepoTree('r1');

    expect(api.get).toHaveBeenCalledWith('/repo/r1/tree');
    expect(result.source).toBe('cache');
  });
});

describe('repoService.startAnalysis', () => {
  it('POSTs to /analyses with repoId, userId, and default aiProvider', async () => {
    api.post.mockResolvedValue({ data: { analysisId: 'a1' } });

    const result = await repoService.startAnalysis({ repoId: 'r1', userId: 'u1' });

    expect(api.post).toHaveBeenCalledWith('/analyses', {
      repoId: 'r1',
      userId: 'u1',
      aiProvider: 'NVIDIA_DEV',
    });
    expect(result.analysisId).toBe('a1');
  });

  it('uses custom aiProvider when provided', async () => {
    api.post.mockResolvedValue({ data: {} });

    await repoService.startAnalysis({ repoId: 'r1', userId: 'u1', aiProvider: 'GROQ' });

    expect(api.post).toHaveBeenCalledWith('/analyses', expect.objectContaining({
      aiProvider: 'GROQ',
    }));
  });
});

describe('repoService.getAnalysisStages', () => {
  it('GETs /analyses/:id/stages', async () => {
    api.get.mockResolvedValue({ data: [{ stage: 'BLUEPRINT' }] });

    const result = await repoService.getAnalysisStages('a1');

    expect(api.get).toHaveBeenCalledWith('/analyses/a1/stages');
    expect(result[0].stage).toBe('BLUEPRINT');
  });
});

describe('repoService.explainFile', () => {
  it('GETs /repo/:repoId/files/:fileId/explain with aiProvider param', async () => {
    api.get.mockResolvedValue({ data: { explanation: 'This file handles auth.' } });

    const result = await repoService.explainFile('r1', 'f1');

    expect(api.get).toHaveBeenCalledWith('/repo/r1/files/f1/explain', {
      params: { aiProvider: 'NVIDIA_DEV' },
    });
    expect(result.explanation).toBeTruthy();
  });
});
