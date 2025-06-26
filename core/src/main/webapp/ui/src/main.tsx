import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App'
import { AuthService } from './services/auth'
import 'antd/dist/reset.css'

const authService = AuthService.getInstance();
(window as any).authService = authService;

console.log('AuthService initialized:', authService);

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
)
