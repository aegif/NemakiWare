import React from 'react';
import type { TooltipProps } from 'antd';

/**
 * A label for controls that live inside a row which can disappear underneath them.
 *
 * This deliberately does NOT render an Ant Design `Tooltip`. The document list's action
 * column has nine icon buttons per row, and acting on one of them (check-in, delete,
 * cancel check-out) re-renders or removes the row while that button's tooltip is still
 * mounted. Ant Design tears the popup down through a portal, a CSSMotion wrapper and a
 * resize observer, and when the row goes first, one of those removals targets a node whose
 * parent is already gone:
 *
 *   NotFoundError: Failed to execute 'removeChild' on 'Node':
 *   The node to be removed is not a child of this node.
 *
 * React cannot recover from that during commit, so the app's ErrorBoundary replaces the
 * whole page with "エラーが発生しました" — a cosmetic teardown costing the user their view.
 * Reproduced against the running UI: check in a document, then navigate; roughly two runs
 * in three ended on the error screen, and the captured componentStack pointed at the
 * Tooltip popup inside the action column's `<td>`.
 *
 * `unique={false}` (Ant Design v6 defaults Tooltip to a SHARED popup container),
 * `destroyOnHidden` and `motion={{ motionName: '' }}` were tried together, and the crash
 * still reproduced from this exact component — so it is neither the shared container nor
 * the animation. The reliable fix is to have no popup here: the native `title` attribute is
 * plain DOM owned by the button itself, with nothing to unmount separately.
 *
 * The cost is that these labels look like the browser's rather than Ant Design's and appear
 * after the OS delay. Everywhere else in the app keeps the styled Tooltip; this component
 * exists for controls inside rows that can vanish while the pointer is on them.
 */
export const RowActionTooltip: React.FC<TooltipProps> = ({ title, children }) => {
  const label = typeof title === 'string' && title !== '' ? title : undefined;
  if (label === undefined) {
    return <>{children}</>;
  }
  // A cell often renders plain text rather than an element (`<Tooltip title={v}>{v}</Tooltip>`).
  // Wrap it, rather than dropping the label on the floor.
  if (!React.isValidElement(children)) {
    return <span title={label}>{children}</span>;
  }
  return React.cloneElement(children as React.ReactElement<{ title?: string }>, { title: label });
};

export default RowActionTooltip;
