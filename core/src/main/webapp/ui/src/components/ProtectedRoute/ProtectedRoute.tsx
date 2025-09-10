import React from 'react';
import { useAuth } from '../../contexts/AuthContext';
import { Login } from '../Login/Login';

interface ProtectedRouteProps {
  children: React.ReactNode;
}

export function ProtectedRoute({ children }: ProtectedRouteProps) {
  const { isAuthenticated } = useAuth();

  if (!isAuthenticated) {
    return (
      <Login onLogin={(auth: any) => {
        // Authentication is handled by AuthContext, just reload the page
        console.log('ProtectedRoute: Login successful, reloading page');
        window.location.reload();
      }} />
    );
  }

  return (
    <ErrorBoundary>
      {children}
    </ErrorBoundary>
  );
}

// Error boundary to catch any unhandled errors and redirect to login
class ErrorBoundary extends React.Component<
  { children: React.ReactNode },
  { hasError: boolean }
> {
  constructor(props: { children: React.ReactNode }) {
    super(props);
    this.state = { hasError: false };
  }

  static getDerivedStateFromError(_: Error) {
    return { hasError: true };
  }

  componentDidCatch(error: Error, errorInfo: React.ErrorInfo) {
    console.error('ErrorBoundary caught an error:', error, errorInfo);
    
    // Check if this is an authentication-related error
    if (error.message.includes('401') || error.message.includes('Unauthorized')) {
      // Clear authentication and redirect to login
      localStorage.removeItem('nemakiware_auth');
      window.location.href = '/core/ui/dist/';
    }
  }

  render() {
    if (this.state.hasError) {
      return (
        <Login onLogin={(auth: any) => {
          console.log('ErrorBoundary: Login successful, reloading page');
          window.location.reload();
        }} />
      );
    }

    return this.props.children;
  }
}