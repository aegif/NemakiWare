/**
 * MSAL popup redirect bridge entry point.
 * This script runs in the popup window opened by MSAL's loginPopup().
 * It broadcasts the auth response back to the parent window via BroadcastChannel
 * and then closes the popup.
 */
import { broadcastResponseToMainFrame } from '@azure/msal-browser/redirect-bridge';

broadcastResponseToMainFrame().catch((err) => {
  console.error('auth-popup: broadcastResponseToMainFrame failed:', err);
});
