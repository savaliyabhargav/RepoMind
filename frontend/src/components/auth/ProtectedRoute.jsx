import { Navigate, useLocation } from "react-router-dom";
import { useAuthStore } from "../../store/authStore";

// ─── ProtectedRoute ──────────────────────────────────────────────────────────
//
//  Wraps any route that requires authentication.
//  If the user is not logged in, redirects to "/" (LandingScreen).
//  Preserves the attempted URL so we can redirect back after login if needed.
//
//  Usage in App.jsx:
//    <Route path="/analyze" element={
//      <ProtectedRoute>
//        <OverviewScreen />
//      </ProtectedRoute>
//    } />
//
// ─────────────────────────────────────────────────────────────────────────────

export default function ProtectedRoute({ children }) {
  const isLoggedIn = useAuthStore((s) => s.isLoggedIn);
  const location = useLocation();

  if (!isLoggedIn) {
    // Redirect to landing, saving where the user tried to go
    return <Navigate to="/" state={{ from: location }} replace />;
  }

  return children;
}