import { describe, it, expect, beforeEach } from 'vitest';
import { useAuthStore } from './authStore';
import { act } from '@testing-library/react';

const mockUser = {
  id: 'uuid-123',
  username: 'testuser',
  avatarUrl: 'https://avatars.com/u/1',
  email: 'test@example.com',
  plan: 'FREE',
  createdAt: null,
};

beforeEach(() => {
  act(() => useAuthStore.setState({ accessToken: null, user: null, isLoggedIn: false }));
});

describe('login', () => {
  it('sets accessToken, user, and isLoggedIn to true', () => {
    act(() => useAuthStore.getState().login('tok123', mockUser));

    const state = useAuthStore.getState();
    expect(state.accessToken).toBe('tok123');
    expect(state.isLoggedIn).toBe(true);
    expect(state.user.username).toBe('testuser');
    expect(state.user.id).toBe('uuid-123');
    expect(state.user.plan).toBe('FREE');
  });

  it('fills missing user fields with null/FREE defaults', () => {
    act(() => useAuthStore.getState().login('tok', { id: 'u1' }));

    const { user } = useAuthStore.getState();
    expect(user.username).toBeNull();
    expect(user.avatarUrl).toBeNull();
    expect(user.plan).toBe('FREE');
  });
});

describe('logout', () => {
  it('clears token, user, and sets isLoggedIn false', () => {
    act(() => useAuthStore.getState().login('tok', mockUser));
    act(() => useAuthStore.getState().logout());

    const state = useAuthStore.getState();
    expect(state.accessToken).toBeNull();
    expect(state.user).toBeNull();
    expect(state.isLoggedIn).toBe(false);
  });
});

describe('checkAuth', () => {
  it('returns true when token is set', () => {
    act(() => useAuthStore.getState().login('tok', mockUser));
    expect(useAuthStore.getState().checkAuth()).toBe(true);
  });

  it('returns false when token is null', () => {
    expect(useAuthStore.getState().checkAuth()).toBe(false);
  });
});

describe('getToken', () => {
  it('returns the access token', () => {
    act(() => useAuthStore.getState().login('my-token', mockUser));
    expect(useAuthStore.getState().getToken()).toBe('my-token');
  });

  it('returns null when not logged in', () => {
    expect(useAuthStore.getState().getToken()).toBeNull();
  });
});

describe('updateUser', () => {
  it('merges partial update into existing user', () => {
    act(() => useAuthStore.getState().login('tok', mockUser));
    act(() => useAuthStore.getState().updateUser({ plan: 'PRO' }));

    const { user } = useAuthStore.getState();
    expect(user.plan).toBe('PRO');
    expect(user.username).toBe('testuser'); // unchanged
  });

  it('does nothing when user is null', () => {
    act(() => useAuthStore.getState().updateUser({ plan: 'PRO' }));
    expect(useAuthStore.getState().user).toBeNull();
  });
});
