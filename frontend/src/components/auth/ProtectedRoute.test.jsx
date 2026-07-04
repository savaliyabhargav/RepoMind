import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { act } from '@testing-library/react';
import { useAuthStore } from '../../store/authStore';
import ProtectedRoute from './ProtectedRoute';

beforeEach(() => {
  act(() => useAuthStore.setState({ accessToken: null, user: null, isLoggedIn: false }));
});

function renderWithRouter(initialPath = '/protected') {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <Routes>
        <Route
          path="/protected"
          element={
            <ProtectedRoute>
              <div>Secret content</div>
            </ProtectedRoute>
          }
        />
        <Route path="/" element={<div>Landing page</div>} />
      </Routes>
    </MemoryRouter>
  );
}

describe('ProtectedRoute', () => {
  it('renders children when user is logged in', () => {
    act(() =>
      useAuthStore.setState({
        accessToken: 'tok',
        user: { id: 'u1', username: 'user', plan: 'FREE' },
        isLoggedIn: true,
      })
    );

    renderWithRouter();

    expect(screen.getByText('Secret content')).toBeInTheDocument();
  });

  it('redirects to "/" when user is not logged in', () => {
    renderWithRouter();

    expect(screen.queryByText('Secret content')).not.toBeInTheDocument();
    expect(screen.getByText('Landing page')).toBeInTheDocument();
  });

  it('does not render children when isLoggedIn is false even with a token present', () => {
    act(() =>
      useAuthStore.setState({
        accessToken: 'tok',
        user: null,
        isLoggedIn: false,
      })
    );

    renderWithRouter();

    expect(screen.queryByText('Secret content')).not.toBeInTheDocument();
  });
});
