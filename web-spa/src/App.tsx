import { Component, useEffect, useState, type ErrorInfo, type ReactNode } from "react";
import { Link, Navigate, Route, Routes } from "react-router-dom";
import { LearningResourceDetailPage } from "./pages/LearningResourceDetailPage";
import { LearningResourcesPage } from "./pages/LearningResourcesPage";
import { getSession, logout, type Session } from "./api/client";
import logoUrl from "../../public/images/mundiapolis-logo-transparent.png";

const resourcesEnabled = import.meta.env.VITE_ENABLE_LEARNING_RESOURCES === "true";

export function App() {
  const [session, setSession] = useState<Session | null>(null);
  const [sessionFailed, setSessionFailed] = useState(false);

  useEffect(() => {
    void getSession().then(setSession).catch(() => setSessionFailed(true));
  }, []);

  if (sessionFailed) return <ServiceUnavailable />;
  if (!session) return <ShellSkeleton />;
  if (!session.authenticated) {
    return (
      <section className="status-page">
        <p className="eyebrow">Mundiapolis Library</p>
        <h1>Sign in to continue.</h1>
        <p>Use your institutional account to access verified learning material.</p>
        <a className="primary-action" href="/oauth2/authorization/institutional">Sign in</a>
      </section>
    );
  }
  return (
    <AppErrorBoundary>
      <a className="skip-link" href="#main-content">Skip to content</a>
      <header className="site-header">
        <Link className="brand" to="/resources" aria-label="Mundiapolis Library home">
          <img src={logoUrl} alt="Mundiapolis University" />
          <span>Library</span>
        </Link>
        <div className="session-summary">
          {session.displayName && <span>{session.displayName}</span>}
          <button className="session-action" type="button" onClick={() => void logout().then(() => window.location.assign("/")).catch(() => window.location.reload())}>Sign out</button>
        </div>
      </header>
      <main id="main-content">
        {resourcesEnabled ? (
          <Routes>
            <Route path="/resources" element={<LearningResourcesPage />} />
            <Route path="/resources/:resourceId" element={<LearningResourceDetailPage />} />
            <Route path="*" element={<Navigate replace to="/resources" />} />
          </Routes>
        ) : (
          <MigrationHold />
        )}
      </main>
    </AppErrorBoundary>
  );
}

function ShellSkeleton() {
  return <div className="shell-skeleton" aria-label="Loading library"><span /><span /><span /></div>;
}

function ServiceUnavailable() {
  return (
    <section className="status-page">
      <h1>The library is temporarily unavailable.</h1>
      <p>Your session was not changed. Try refreshing in a moment.</p>
      <button type="button" onClick={() => window.location.reload()}>Refresh</button>
    </section>
  );
}

function MigrationHold() {
  return (
    <section className="status-page" aria-labelledby="migration-title">
      <p className="eyebrow">Migration safeguard</p>
      <h1 id="migration-title">Open learning is not enabled on this release.</h1>
      <p>The current library remains available while catalog reconciliation is completed.</p>
    </section>
  );
}

class AppErrorBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error("SPA render failure", error.name, info.componentStack);
  }

  render() {
    if (this.state.failed) {
      return (
        <main className="status-page">
          <h1>We could not load the library.</h1>
          <p>Refresh the page. If the problem continues, return later.</p>
          <button type="button" onClick={() => window.location.reload()}>Refresh</button>
        </main>
      );
    }
    return this.props.children;
  }
}
