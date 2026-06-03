/**
 * PreviewErrorBoundary
 *
 * A small React error boundary that isolates preview-component crashes.
 * Third-party viewers (notably react-pdf / pdf.js) can throw synchronously
 * during the render phase — e.g. when a worker is torn down between renders
 * and a stale call dereferences a null message handler
 * ("Cannot read properties of null (reading 'sendWithPromise')"). Without a
 * boundary such a throw propagates up and blanks the whole page. Wrapping the
 * preview area keeps the failure local: the user sees an inline error Alert
 * (with a retry) instead of losing the entire screen.
 *
 * The boundary also resets itself when its `resetKey` changes, so navigating
 * to a different document re-renders the preview cleanly after a prior error.
 */
import React from 'react';
import { Alert, Button } from 'antd';

interface PreviewErrorBoundaryProps {
  children: React.ReactNode;
  /** Localized message / description for the fallback Alert. */
  message: string;
  description: string;
  retryLabel: string;
  /** Changing this value clears a previous error (e.g. on document change). */
  resetKey?: string | number;
}

interface PreviewErrorBoundaryState {
  hasError: boolean;
}

export class PreviewErrorBoundary extends React.Component<
  PreviewErrorBoundaryProps,
  PreviewErrorBoundaryState
> {
  constructor(props: PreviewErrorBoundaryProps) {
    super(props);
    this.state = { hasError: false };
  }

  static getDerivedStateFromError(): PreviewErrorBoundaryState {
    return { hasError: true };
  }

  componentDidUpdate(prevProps: PreviewErrorBoundaryProps) {
    // Clear the error when the previewed item changes so the new item gets a
    // fresh render attempt instead of staying on the error fallback.
    if (this.state.hasError && prevProps.resetKey !== this.props.resetKey) {
      this.setState({ hasError: false });
    }
  }

  componentDidCatch(error: Error) {
    // Keep a console trace for diagnostics; the UI degrades gracefully.
    console.error('Preview render error caught by boundary:', error);
  }

  private handleRetry = () => {
    this.setState({ hasError: false });
  };

  render() {
    if (this.state.hasError) {
      return (
        <Alert
          message={this.props.message}
          description={
            <span>
              {this.props.description}
              <span style={{ marginLeft: 12 }}>
                <Button size="small" onClick={this.handleRetry}>
                  {this.props.retryLabel}
                </Button>
              </span>
            </span>
          }
          type="error"
          showIcon
        />
      );
    }
    return this.props.children;
  }
}
