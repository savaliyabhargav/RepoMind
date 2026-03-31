import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import './index.css'; // Global styles and Tailwind
import App from './App.jsx'; // The main router we will build next

// Standard React 19 mounting logic
createRoot(document.getElementById('root')).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
