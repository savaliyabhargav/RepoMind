import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { act } from '@testing-library/react';
import { useAuthStore } from '../../store/authStore';

// Mock authService before importing the component
vi.mock('../../services/authService', () => ({
  default: {
    exchangeCode: vi.fn(),
    refreshToken: vi.fn(),
    logoutApi: vi.fn(),
  },
  api: { post: vi.fn(), get: vi.fn() },
}));

import authService from '../../services/authService';
import LoginCallback from './LoginCallback';

beforeEach(() => {
  vi.clearAllMocks();
  act(() => useAuthStore.setState({ accessToken: null, user: null, isLoggedIn: false }));
  localStorage.clear();
});

afterEach(() => {
  // A failed assertion inside a fake-timer test must not leak fake timers
  // into the rest of the suite — waitFor() hangs forever under fake timers.
  vi.useRealTimers();
});

function renderCallback(search = '') {
  return render(
    <MemoryRouter initialEntries={[`/auth/callback${search}`]}>
      <Routes>
        <Route path="/auth/callback" element={<LoginCallback />} />
        <Route path="/" element={<div>Landing</div>} />
        <Route path="/analyze" element={<div>Dashboard</div>} />
      </Routes>
    </MemoryRouter>
  );
}

describe('LoginCallback', () => {
  it('shows a pending state while the code exchange is in flight', () => {
    // Never resolves — the component stays in its pending UI. The effect runs
    // synchronously past VERIFYING into EXCHANGING, so assert the visible state.
    authService.exchangeCode.mockReturnValue(new Promise(() => {}));
    localStorage.setItem('github_oauth_state', 'xyz');

    renderCallback('?code=abc&state=xyz');

    expect(screen.getByText(/Authenticating/i)).toBeInTheDocument();
  });

  it('navigates to /analyze on successful auth', async () => {
    localStorage.setItem('github_oauth_state', 'state123');
    authService.exchangeCode.mockResolvedValue({
      token: 'jwt-token',
      user: { id: 'u1', username: 'user', plan: 'FREE' },
    });

    renderCallback('?code=authcode&state=state123');

    await waitFor(() => {
      expect(screen.getByText(/You're in/i)).toBeInTheDocument();
    });

    // Component redirects after a 1s success splash — use real timers and
    // a generous waitFor instead of fake timers (which break waitFor itself).
    await waitFor(() => {
      expect(screen.getByText('Dashboard')).toBeInTheDocument();
    }, { timeout: 3000 });
  });

  it('stores user in auth store after successful login', async () => {
    localStorage.setItem('github_oauth_state', 'state456');
    authService.exchangeCode.mockResolvedValue({
      token: 'my-jwt',
      user: { id: 'u2', username: 'newuser', plan: 'FREE' },
    });

    renderCallback('?code=code&state=state456');

    await waitFor(() => {
      expect(useAuthStore.getState().isLoggedIn).toBe(true);
      expect(useAuthStore.getState().accessToken).toBe('my-jwt');
    });
  });

  it('shows error state when GitHub returns an error param', async () => {
    renderCallback('?error=access_denied');

    await waitFor(() => {
      expect(screen.getByText(/Authentication failed/i)).toBeInTheDocument();
      expect(screen.getByText(/cancelled/i)).toBeInTheDocument();
    });
  });

  it('shows error when no code param present', async () => {
    renderCallback('');

    await waitFor(() => {
      expect(screen.getByText(/Authentication failed/i)).toBeInTheDocument();
      expect(screen.getByText(/No authorisation code/i)).toBeInTheDocument();
    });
  });

  it('shows error when exchangeCode throws', async () => {
    localStorage.setItem('github_oauth_state', 'state789');
    authService.exchangeCode.mockRejectedValue(new Error('Server error'));

    renderCallback('?code=badcode&state=state789');

    await waitFor(() => {
      expect(screen.getByText(/Authentication failed/i)).toBeInTheDocument();
      expect(screen.getByText(/Server error/i)).toBeInTheDocument();
    });
  });

  it('shows retry button on error and navigates to / when clicked', async () => {
    renderCallback('?error=access_denied');

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /Back to login/i })).toBeInTheDocument();
    });

    await userEvent.click(screen.getByRole('button', { name: /Back to login/i }));

    await waitFor(() => {
      expect(screen.getByText('Landing')).toBeInTheDocument();
    });
  });
});
