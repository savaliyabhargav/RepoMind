import { BrowserRouter, Routes, Route, Navigate } from "react-router-dom";
import LandingScreen from "./screens/LandingScreen";
import LoginCallback from "./screens/auth/LoginCallback";
import OverviewScreen from "./screens/OverviewScreen";
import RepoDetailScreen from "./screens/RepoDetailScreen";
import ProtectedRoute from "./components/auth/ProtectedRoute";

function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<LandingScreen />} />
        <Route path="/auth/callback" element={<LoginCallback />} />

        <Route
          path="/analyze"
          element={
            <ProtectedRoute>
              <OverviewScreen />
            </ProtectedRoute>
          }
        />

        <Route
          path="/analyze/repo/:repoId"
          element={
            <ProtectedRoute>
              <RepoDetailScreen />
            </ProtectedRoute>
          }
        />

        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  );
}

export default App;
