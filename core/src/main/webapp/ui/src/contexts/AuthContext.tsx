import React, { createContext, useContext, useState, useCallback } from 'react';
import { AuthService, AuthToken } from '../services/auth';

interface AuthContextType {
  isAuthenticated: boolean;
  isLoading: boolean;
  authToken: AuthToken | null;
  login: (username: string, password: string, repositoryId: string) => Promise<void>;
  logout: () => void;
  handleAuthError: (error: any) => void;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [isAuthenticated, setIsAuthenticated] = useState(false);
  const [isLoading, setIsLoading] = useState(true);
  const [authToken, setAuthToken] = useState<AuthToken | null>(null);

  // Initialize authentication state from localStorage
  React.useEffect(() => {
    const checkAuthState = () => {
      const authService = AuthService.getInstance();
      const currentAuth = authService.getCurrentAuth();

      if (currentAuth) {
        console.log('AuthContext: Found auth data:', currentAuth);
        setAuthToken(currentAuth);
        setIsAuthenticated(true);
      } else {
        console.log('AuthContext: No auth data found');
        setAuthToken(null);
        setIsAuthenticated(false);
      }

      // Mark initialization as complete
      setIsLoading(false);
    };

    // Initial check
    checkAuthState();

    // Listen for localStorage changes (from Login component)
    const handleStorageChange = (e: StorageEvent) => {
      if (e.key === 'nemakiware_auth') {
        console.log('AuthContext: localStorage changed for nemakiware_auth');
        checkAuthState();
      }
    };

    window.addEventListener('storage', handleStorageChange);

    // Listen for custom events for immediate updates
    const handleAuthUpdate = () => {
      console.log('AuthContext: Received auth update event');
      checkAuthState();
    };

    window.addEventListener('authStateChanged', handleAuthUpdate);

    return () => {
      window.removeEventListener('storage', handleStorageChange);
      window.removeEventListener('authStateChanged', handleAuthUpdate);
    };
  }, []);

  const login = useCallback(async (username: string, password: string, repositoryId: string) => {
    try {
      const authService = AuthService.getInstance();
      const auth = await authService.login(username, password, repositoryId);
      setAuthToken(auth);
      setIsAuthenticated(true);
    } catch (error) {
      console.error('Login failed:', error);
      throw error;
    }
  }, []);

  const logout = useCallback(() => {
    const authService = AuthService.getInstance();
    authService.logout();
    setAuthToken(null);
    setIsAuthenticated(false);

    // Clear any error states and redirect to login
    window.location.href = '/core/ui/dist/index.html';
  }, []);

  const handleAuthError = useCallback((error: any) => {
    console.warn('Authentication error detected:', error);

    // CRITICAL FIX (2025-10-22): Only handle authentication errors (401, 403)
    // DO NOT handle 404 Not Found errors - these are not authentication failures
    // 404 errors should be handled by components, not force logout
    const status = error?.status;
    const message = error?.message || '';

    if (status === 401 || message.includes('401') || message.includes('Unauthorized')) {
      console.log('401 Unauthorized error detected - redirecting to login');
      logout();
    } else if (status === 403 || message.includes('403') || message.includes('Forbidden')) {
      console.log('403 Forbidden error detected - redirecting to login');
      logout();
    }
    // REMOVED: 404 handling - not an authentication error
  }, [logout]);

  const value = {
    isAuthenticated,
    isLoading,
    authToken,
    login,
    logout,
    handleAuthError
  };

  return (
    <AuthContext.Provider value={value}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (context === undefined) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
}