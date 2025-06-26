import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App'
import { AuthService } from './services/auth'
import 'antd/dist/reset.css'

console.log('main.tsx: Initializing AuthService...');
const authService = AuthService.getInstance();
(window as any).authService = authService;

const authData = localStorage.getItem('nemakiware_auth');
if (authData) {
  console.log('main.tsx: Found auth data in localStorage:', authData);
  try {
    const auth = JSON.parse(authData);
    console.log('main.tsx: Parsed auth data:', auth);
    if (auth.token && auth.repositoryId && auth.username) {
      console.log('main.tsx: AuthService should now have auth data');
    }
  } catch (e) {
    console.error('main.tsx: Failed to parse auth data:', e);
  }
} else {
  console.log('main.tsx: No auth data found in localStorage');
}

console.log('main.tsx: AuthService initialized and exposed:', authService);
console.log('main.tsx: window.authService check:', (window as any).authService);
console.log('main.tsx: AuthService methods:', Object.getOwnPropertyNames(Object.getPrototypeOf(authService)));

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
)
