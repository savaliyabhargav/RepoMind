import { BrowserRouter, Routes, Route, Navigate } from "react-router-dom";
import LandingScreen from "./screens/LandingScreen";
import LoginCallback from "./screens/auth/LoginCallback";
import OverviewScreen from "./screens/OverviewScreen";
import ProtectedRoute from "./components/auth/ProtectedRoute";

function App() {
  return (
    <BrowserRouter>
      <Routes>
        {/* Public — Landing / Login */}
        <Route path="/" element={<LandingScreen />} />

        {/* Public — GitHub OAuth callback handler */}
        <Route path="/auth/callback" element={<LoginCallback />} />

        {/* Protected — requires JWT in authStore */}
        <Route
          path="/analyze"
          element={
            <ProtectedRoute>
              <OverviewScreen />
            </ProtectedRoute>
          }
        />

        {/* Catch-all → home */}
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  );
}

export default App;