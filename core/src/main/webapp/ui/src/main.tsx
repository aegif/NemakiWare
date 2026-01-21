import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App'
import { AuthService } from './services/auth'
import 'antd/dist/reset.css'
import './index.css'
// Initialize i18n before rendering the app
import './i18n'

const authService = AuthService.getInstance();
(window as any).authService = authService;

// CRITICAL DEBUG (2025-12-18): Global function to cleanup overlays
// Users can call window.cleanupOverlays() from browser console if screen is gray
(window as any).cleanupOverlays = () => {
  console.log('=== Manual Overlay Cleanup Started ===');

  // List ALL elements that could cause overlay issues
  const problemElements = document.querySelectorAll(
    '.ant-modal-mask, .ant-modal-wrap, .ant-modal-root, ' +
    '.ant-drawer-mask, .ant-drawer-wrap, ' +
    '.ant-image-preview-mask, .ant-image-preview-wrap, ' +
    '.ant-spin-blur, .ant-spin-nested-loading, ' +
    '[class*="ant-"][class*="-mask"], ' +
    '[style*="position: fixed"], [style*="position:fixed"]'
  );

  console.log(`Found ${problemElements.length} potential overlay elements`);

  problemElements.forEach((el, index) => {
    console.log(`${index + 1}. Element:`, el.className || el.tagName, el);
    const style = window.getComputedStyle(el);
    if (style.position === 'fixed' || style.position === 'absolute') {
      if (el.classList.contains('ant-spin-blur')) {
        el.classList.remove('ant-spin-blur');
        console.log('   -> Removed ant-spin-blur class');
      } else if (el.parentElement?.id !== 'root') {
        el.remove();
        console.log('   -> Removed element');
      }
    }
  });

  // Reset body styles
  document.body.style.overflow = '';
  document.body.style.paddingRight = '';
  document.body.style.position = '';
  document.body.style.width = '';
  document.body.style.top = '';
  document.body.classList.remove('ant-scrolling-effect');
  console.log('Body styles reset');

  // Reset root styles
  const root = document.getElementById('root');
  if (root) {
    root.style.pointerEvents = '';
    root.style.filter = '';
    root.style.opacity = '';
    console.log('Root styles reset');
  }

  console.log('=== Manual Overlay Cleanup Complete ===');
  console.log('If screen is still gray, check Elements tab for remaining overlays');
};

// CRITICAL DEBUG (2025-12-18): Function to diagnose overlay issues
(window as any).diagnoseOverlay = () => {
  console.log('=== Overlay Diagnosis ===');

  // Check body styles
  const bodyStyle = window.getComputedStyle(document.body);
  console.log('Body styles:', {
    overflow: bodyStyle.overflow,
    position: bodyStyle.position,
    pointerEvents: bodyStyle.pointerEvents,
    filter: bodyStyle.filter
  });

  // Check root styles
  const root = document.getElementById('root');
  if (root) {
    const rootStyle = window.getComputedStyle(root);
    console.log('Root styles:', {
      pointerEvents: rootStyle.pointerEvents,
      filter: rootStyle.filter,
      opacity: rootStyle.opacity
    });
  }

  // Find all fixed/absolute positioned elements
  const allElements = document.querySelectorAll('*');
  const fixedElements: Element[] = [];
  allElements.forEach(el => {
    const style = window.getComputedStyle(el);
    if ((style.position === 'fixed' || style.position === 'absolute') &&
        parseInt(style.zIndex) > 100) {
      fixedElements.push(el);
    }
  });

  console.log(`Found ${fixedElements.length} high z-index fixed/absolute elements:`);
  fixedElements.forEach((el, i) => {
    const style = window.getComputedStyle(el);
    console.log(`${i + 1}.`, {
      tag: el.tagName,
      class: el.className,
      zIndex: style.zIndex,
      position: style.position,
      background: style.background?.substring(0, 50)
    });
  });

  console.log('=== End Diagnosis ===');
};

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
