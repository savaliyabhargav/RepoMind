import { api } from "./authService";

const repoService = {
  ingestRepo: async (url, userId) => {
    const response = await api.post("/repo/ingest", { url, userId });
    return response.data;
  },

  getRepo: async (repoId) => {
    const response = await api.get(`/repo/${repoId}`);
    return response.data;
  },

  getRepoTree: async (repoId) => {
    const response = await api.get(`/repo/${repoId}/tree`);
    return response.data;
  },

  startAnalysis: async ({ repoId, userId, aiProvider = "NVIDIA_DEV" }) => {
    const response = await api.post("/analyses", { repoId, userId, aiProvider });
    return response.data;
  },

  getAnalysisStages: async (analysisId) => {
    const response = await api.get(`/analyses/${analysisId}/stages`);
    return response.data;
  },

  explainFile: async (repoId, fileId, aiProvider = "NVIDIA_DEV") => {
    const response = await api.get(`/repo/${repoId}/files/${fileId}/explain`, {
      params: { aiProvider },
    });
    return response.data;
  },
};

export default repoService;
