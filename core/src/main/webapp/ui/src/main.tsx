import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App'
import { AuthService } from './services/auth'
import 'antd/dist/reset.css'
import './index.css'

const authService = AuthService.getInstance();
(window as any).authService = authService;

const authData = localStorage.getItem('nemakiware_auth');
if (authData) {
  try {
    const auth = JSON.parse(authData);
    if (auth.token && auth.repositoryId && auth.username) {
      // AuthService will automatically load from localStorage
    }
  } catch (e) {
    console.error('main.tsx: Failed to parse auth data:', e);
  }
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
)
