import { useEffect, useMemo, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import {
  ApiError,
  getNotificationPreference,
  getNotifications,
  markNotificationRead,
  updateNotificationPreference,
  type NotificationItem,
  type NotificationPage,
  type NotificationPreference,
  type NotificationReadStatus,
  type UpdateNotificationPreference,
  type VersionedNotificationPreference,
} from "../api/client";

type LoadState =
  | { kind: "loading" }
  | { kind: "ready"; page: NotificationPage; preference: VersionedNotificationPreference }
  | { kind: "failed" | "forbidden" };

const categoryLabels: Record<NotificationItem["category"], string> = {
  DUE_SOON: "Due soon",
  OVERDUE: "Overdue",
  HOLD_READY: "Ready for pickup",
  ACCOUNT_STATUS: "Account",
  GENERAL: "Library update",
};

const dateFormatter = new Intl.DateTimeFormat(undefined, {
  dateStyle: "medium",
  timeStyle: "short",
});

export function NotificationsPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const status = readStatus(searchParams.get("status"));
  const [state, setState] = useState<LoadState>({ kind: "loading" });
  const [draft, setDraft] = useState<UpdateNotificationPreference | null>(null);
  const [pendingRead, setPendingRead] = useState<string | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const [saving, setSaving] = useState(false);
  const [feedback, setFeedback] = useState<{ tone: "success" | "error"; message: string } | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    setState({ kind: "loading" });
    setFeedback(null);
    Promise.all([
      getNotifications(status, undefined, controller.signal),
      getNotificationPreference(controller.signal),
    ]).then(([page, preference]) => {
      if (controller.signal.aborted) return;
      if (page.memberId !== preference.preference.memberId) throw new ApiError(502);
      setDraft(toUpdate(preference.preference));
      setState({ kind: "ready", page, preference });
    }).catch((error: unknown) => {
      if (controller.signal.aborted) return;
      if (error instanceof ApiError && error.status === 401) {
        window.location.assign("/oauth2/authorization/institutional");
        return;
      }
      setState({ kind: error instanceof ApiError && error.status === 403 ? "forbidden" : "failed" });
    });
    return () => controller.abort();
  }, [status]);

  const unreadCount = useMemo(() => state.kind === "ready"
    ? state.page.items.filter((item) => item.readAt === null).length
    : 0, [state]);

  if (state.kind === "loading") return <NotificationsSkeleton />;
  if (state.kind !== "ready" || !draft) return <NotificationsError forbidden={state.kind === "forbidden"} />;

  const { page, preference } = state;
  const dirty = !samePreference(draft, preference.preference);

  async function loadMore() {
    if (state.kind !== "ready" || !state.page.nextCursor || loadingMore) return;
    setLoadingMore(true);
    setFeedback(null);
    try {
      const next = await getNotifications(status, state.page.nextCursor);
      if (next.memberId !== state.page.memberId) throw new ApiError(502);
      const existing = new Set(state.page.items.map((item) => item.notificationId));
      if (next.items.some((item) => existing.has(item.notificationId))) throw new ApiError(502);
      setState({ kind: "ready", preference: state.preference, page: {
        ...next,
        items: [...state.page.items, ...next.items],
      } });
    } catch (error: unknown) {
      handleSession(error);
      setFeedback({ tone: "error", message: "More notifications could not be loaded. The current inbox is still available." });
    } finally {
      setLoadingMore(false);
    }
  }

  async function markRead(item: NotificationItem) {
    if (pendingRead || item.readAt !== null) return;
    setPendingRead(item.notificationId);
    setFeedback(null);
    try {
      const updated = await markNotificationRead(item.notificationId);
      setState((current) => current.kind !== "ready" ? current : {
        ...current,
        page: {
          ...current.page,
          items: status === "UNREAD"
            ? current.page.items.filter((candidate) => candidate.notificationId !== item.notificationId)
            : current.page.items.map((candidate) => candidate.notificationId === item.notificationId ? updated : candidate),
        },
      });
    } catch (error: unknown) {
      handleSession(error);
      setFeedback({ tone: "error", message: "This notification could not be marked as read." });
    } finally {
      setPendingRead(null);
    }
  }

  async function savePreferences(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (state.kind !== "ready" || !draft || saving || !dirty) return;
    setSaving(true);
    setFeedback(null);
    try {
      const updated = await updateNotificationPreference(
        state.preference.preference.memberId,
        state.preference.entityTag,
        draft,
      );
      setState({ kind: "ready", page: state.page, preference: updated });
      setDraft(toUpdate(updated.preference));
      setFeedback({ tone: "success", message: "Notification preferences saved." });
    } catch (error: unknown) {
      if (error instanceof ApiError && (error.status === 409 || error.status === 428)) {
        try {
          const latest = await getNotificationPreference();
          if (latest.preference.memberId !== state.page.memberId) throw new ApiError(502);
          setState({ kind: "ready", page: state.page, preference: latest });
          setDraft(toUpdate(latest.preference));
          setFeedback({ tone: "error", message: "Preferences changed elsewhere. The latest settings are shown; review them before saving again." });
        } catch (refreshError: unknown) {
          handleSession(refreshError);
          setFeedback({ tone: "error", message: "Preferences changed elsewhere and could not be refreshed." });
        }
      } else {
        handleSession(error);
        setFeedback({ tone: "error", message: "Preferences could not be saved. Your previous settings remain active." });
      }
    } finally {
      setSaving(false);
    }
  }

  function toggle(field: keyof UpdateNotificationPreference) {
    setDraft((current) => current ? { ...current, [field]: !current[field] } : current);
  }

  return (
    <article className="notification-shell">
      <header className="notification-heading">
        <p className="eyebrow">My account</p>
        <h1>Notifications</h1>
        <p>{unreadCount === 0 ? "No unread messages on this page." : `${unreadCount} unread ${unreadCount === 1 ? "message" : "messages"} on this page.`}</p>
      </header>

      {feedback && <output className={`notification-feedback command-${feedback.tone}`}>{feedback.message}</output>}

      <div className="notification-layout">
        <section aria-labelledby="inbox-title">
          <div className="notification-section-heading">
            <h2 id="inbox-title">Inbox</h2>
            <nav className="notification-filters" aria-label="Filter notifications">
              {(["ALL", "UNREAD", "READ"] as const).map((value) => (
                <button key={value} type="button" aria-pressed={status === value} onClick={() => setSearchParams(value === "ALL" ? {} : { status: value.toLowerCase() })}>
                  {value === "ALL" ? "All" : value === "UNREAD" ? "Unread" : "Read"}
                </button>
              ))}
            </nav>
          </div>
          {page.items.length === 0 ? (
            <div className="notification-empty"><h3>No notifications here.</h3><p>{status === "ALL" ? "Library updates will appear here." : `There are no ${status.toLowerCase()} messages.`}</p></div>
          ) : (
            <ol className="notification-list">
              {page.items.map((item) => (
                <li key={item.notificationId} className={item.readAt === null ? "notification-unread" : undefined}>
                  <div className="notification-meta"><span>{categoryLabels[item.category]}</span><time dateTime={item.createdAt}>{dateFormatter.format(new Date(item.createdAt))}</time></div>
                  <h3>{item.subject}</h3>
                  <p>{item.body}</p>
                  {item.readAt === null && <button type="button" onClick={() => void markRead(item)} disabled={pendingRead !== null}>{pendingRead === item.notificationId ? "Marking…" : "Mark as read"}</button>}
                </li>
              ))}
            </ol>
          )}
          {page.nextCursor && <button className="secondary-action" type="button" onClick={() => void loadMore()} disabled={loadingMore}>{loadingMore ? "Loading…" : "Load more"}</button>}
        </section>

        <form className="notification-preferences" onSubmit={(event) => void savePreferences(event)}>
          <div>
            <p className="eyebrow">Delivery</p>
            <h2>Email preferences</h2>
            <p>Choose which library events may be sent to your institutional email.</p>
          </div>
          <PreferenceToggle label="Email notifications" checked={draft.emailEnabled} onChange={() => toggle("emailEnabled")} />
          <PreferenceToggle label="Loans due soon" checked={draft.dueSoonEnabled} onChange={() => toggle("dueSoonEnabled")} />
          <PreferenceToggle label="Overdue loans" checked={draft.overdueEnabled} onChange={() => toggle("overdueEnabled")} />
          <PreferenceToggle label="Reservations ready" checked={draft.holdReadyEnabled} onChange={() => toggle("holdReadyEnabled")} />
          <PreferenceToggle label="Account status" checked={draft.accountStatusEnabled} onChange={() => toggle("accountStatusEnabled")} />
          <button type="submit" disabled={!dirty || saving}>{saving ? "Saving…" : "Save preferences"}</button>
        </form>
      </div>
    </article>
  );
}

function PreferenceToggle({ label, checked, onChange }: { label: string; checked: boolean; onChange: () => void }) {
  return <label className="preference-toggle"><span>{label}</span><input type="checkbox" checked={checked} onChange={onChange} /></label>;
}

function toUpdate(preference: NotificationPreference): UpdateNotificationPreference {
  return {
    emailEnabled: preference.emailEnabled,
    dueSoonEnabled: preference.dueSoonEnabled,
    overdueEnabled: preference.overdueEnabled,
    holdReadyEnabled: preference.holdReadyEnabled,
    accountStatusEnabled: preference.accountStatusEnabled,
  };
}

function samePreference(update: UpdateNotificationPreference, preference: NotificationPreference) {
  return update.emailEnabled === preference.emailEnabled && update.dueSoonEnabled === preference.dueSoonEnabled &&
    update.overdueEnabled === preference.overdueEnabled && update.holdReadyEnabled === preference.holdReadyEnabled &&
    update.accountStatusEnabled === preference.accountStatusEnabled;
}

function readStatus(value: string | null): NotificationReadStatus {
  return value === "read" ? "READ" : value === "unread" ? "UNREAD" : "ALL";
}

function handleSession(error: unknown) {
  if (error instanceof ApiError && error.status === 401) window.location.assign("/oauth2/authorization/institutional");
}

function NotificationsSkeleton() {
  return <div className="notification-skeleton" aria-label="Loading notifications"><span /><span /><span /><span /></div>;
}

function NotificationsError({ forbidden }: { forbidden: boolean }) {
  return <section className="status-page"><p className="eyebrow">Notifications</p><h1>{forbidden ? "Notifications are not available for this account." : "Notifications could not be loaded."}</h1><p>{forbidden ? "Contact library staff if you believe this is incorrect." : "Your settings were not changed. Try again in a moment."}</p>{!forbidden && <button type="button" onClick={() => window.location.reload()}>Try again</button>}<Link to="/catalog">Return to catalog</Link></section>;
}
