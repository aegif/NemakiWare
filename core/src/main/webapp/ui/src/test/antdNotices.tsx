import { act } from '@testing-library/react';
import { ConfigProvider, message, notification } from 'antd';

/**
 * Raised when the harness, not a lock, is broken. The negative-control runner reads a failure with a line that starts
 * with a name ending in HarnessBroken as breakage, and breakage wins over a lock's own assertion in the same testcase,
 * so a control cannot be scored as firing on a lock that ran over a broken harness.
 */
export class HarnessBroken extends Error {
  constructor(text: string) {
    super(text);
    this.name = 'HarnessBroken';
  }
}

/**
 * antd draws its static notices (message, notification) in roots of their own, outside act(). With motion on, destroy()
 * starts a leave that only an animationend or a transitionend ends, and jsdom fires neither: the notice stays mounted,
 * and until its duration runs out its timer updates React on every frame. Work that runs after the file's jsdom is gone
 * is "ReferenceError: window is not defined" from react-dom, an unhandled error that failed a CI run with every lock
 * green (b290733ab; the same as AccountSettings.test.tsx on 8f0a7c2b9). With motion off, destroy() inside act unmounts
 * the notices, and neither they nor their timers outlive the lock. Call at the top of a test file.
 */
export function turnAntdMotionOff(): void {
  ConfigProvider.config({
    holderRender: (children) => <ConfigProvider theme={{ token: { motion: false } }}>{children}</ConfigProvider>,
  });
}

/** Puts the global setting back; vitest isolates test files by default, and this does it regardless. Call in afterAll. */
export function turnAntdMotionBackOn(): void {
  ConfigProvider.config({ holderRender: undefined });
}

/**
 * Unmounts every static notice inside act, and raises HarnessBroken if one is still mounted. Call LAST in afterEach,
 * after the stand-ins are reset, so a raise has left the rest of the cleanup done. The notice it reports stays
 * mounted, and every later lock of the file reports it too.
 */
export async function destroyAntdNotices(): Promise<void> {
  await act(async () => {
    message.destroy();
    notification.destroy();
  });
  const left = document.querySelectorAll('.ant-message-notice, .ant-notification-notice').length;
  if (left !== 0) {
    throw new HarnessBroken(`${left} antd notice(s) outlived the lock; their timers keep updating React after the hook`);
  }
}
