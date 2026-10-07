import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import {
  ApiError,
  changeAdminMemberStatus,
  getAdminMembers,
  type AccountStatus,
  type AdminMember,
  type AdminMemberPage,
} from "../api/client";

type LoadState =
  | { kind: "loading" }
  | { kind: "ready"; page: AdminMemberPage }
  | { kind: "forbidden" | "failed" };

const joinedFormatter = new Intl.DateTimeFormat(undefined, { dateStyle: "medium" });

export function AdminMembersPage() {
  const [status, setStatus] = useState<AccountStatus>("PENDING");
  const [state, setState] = useState<LoadState>({ kind: "loading" });
  const [selected, setSelected] = useState<AdminMember | null>(null);
  const [decision, setDecision] = useState<"APPROVED" | "REJECTED">("APPROVED");
  const [reason, setReason] = useState("");
  const [saving, setSaving] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [feedback, setFeedback] = useState<{ tone: "success" | "error"; message: string } | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    setState({ kind: "loading" });
    setSelected(null);
    setFeedback(null);
    void getAdminMembers(status, undefined, 25, controller.signal)
      .then((page) => { if (!controller.signal.aborted) setState({ kind: "ready", page }); })
      .catch((error: unknown) => {
        if (controller.signal.aborted) return;
        if (error instanceof ApiError && error.status === 401) {
          window.location.assign("/oauth2/authorization/institutional");
          return;
        }
        setState({ kind: error instanceof ApiError && error.status === 403 ? "forbidden" : "failed" });
      });
    return () => controller.abort();
  }, [status]);

  if (state.kind === "loading") return <div className="admin-skeleton" aria-label="Loading administration"><span /><span /><span /></div>;
  if (state.kind !== "ready") return <AdminError forbidden={state.kind === "forbidden"} />;

  async function loadMore() {
    if (state.kind !== "ready" || !state.page.nextCursor || loadingMore) return;
    setLoadingMore(true);
    try {
      const next = await getAdminMembers(status, state.page.nextCursor);
      const known = new Set(state.page.items.map((member) => member.memberId));
      if (next.items.some((member) => known.has(member.memberId))) throw new ApiError(502);
      setState({ kind: "ready", page: { ...next, items: [...state.page.items, ...next.items] } });
    } catch (error: unknown) {
      handleSession(error);
      setFeedback({ tone: "error", message: "More accounts could not be loaded. The current list remains available." });
    } finally {
      setLoadingMore(false);
    }
  }

  async function submitDecision(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (state.kind !== "ready" || !selected || saving) return;
    setSaving(true);
    setFeedback(null);
    try {
      await changeAdminMemberStatus(selected, decision, reason);
      setState({ kind: "ready", page: {
        ...state.page,
        items: state.page.items.filter((member) => member.memberId !== selected.memberId),
      } });
      setFeedback({ tone: "success", message: `${selected.fullName} was ${decision === "APPROVED" ? "approved" : "rejected"}. The decision was audited.` });
      setSelected(null);
      setReason("");
    } catch (error: unknown) {
      handleSession(error);
      setFeedback({
        tone: "error",
        message: error instanceof ApiError && error.status === 409
          ? "This account changed elsewhere. Reload the queue before deciding."
          : "The decision was not applied. No account state was changed.",
      });
    } finally {
      setSaving(false);
    }
  }

  return (
    <article className="admin-shell">
      <header className="admin-heading">
        <div>
          <p className="eyebrow">Library operations</p>
          <h1>Administration</h1>
          <p>Review account access against authoritative membership records.</p>
        </div>
        <aside aria-label="Administrative safeguards"><strong>Fresh authorization</strong><span>Every read and decision is checked by Membership. Browser state never grants access.</span></aside>
      </header>

      <section className="admin-overview" aria-label="Account review overview">
        <div><span>Queue shown</span><strong>{state.page.items.length}</strong><small>{status.toLowerCase()} accounts loaded</small></div>
        <div><span>Decision safety</span><strong>Versioned</strong><small>Conflicting updates are rejected</small></div>
        <div><span>Audit trail</span><strong>Required</strong><small>Every decision needs a reason</small></div>
      </section>

      {feedback && <output className={`admin-feedback command-${feedback.tone}`}>{feedback.message}</output>}

      <section className="admin-workspace" aria-labelledby="account-review-title">
        <div className="admin-section-heading">
          <div><p className="eyebrow">Membership</p><h2 id="account-review-title">Account review</h2></div>
          <nav className="admin-filters" aria-label="Filter account status">
            {(["PENDING", "APPROVED", "REJECTED"] as const).map((value) => (
              <button type="button" key={value} aria-pressed={status === value} onClick={() => setStatus(value)}>
                {value.charAt(0) + value.slice(1).toLowerCase()}
              </button>
            ))}
          </nav>
        </div>

        {state.page.items.length === 0 ? (
          <div className="admin-empty"><h3>No {status.toLowerCase()} accounts.</h3><p>The queue is clear for this status.</p></div>
        ) : (
          <ol className="admin-member-list">
            {state.page.items.map((member) => (
              <li key={member.memberId}>
                <div className="admin-member-identity"><strong>{member.fullName}</strong><a href={`mailto:${member.email}`}>{member.email}</a></div>
                <dl>
                  <div><dt>University ID</dt><dd>{member.universityId}</dd></div>
                  <div><dt>Role</dt><dd>{formatRole(member.role)}</dd></div>
                  <div><dt>Requested</dt><dd><time dateTime={member.createdAt}>{joinedFormatter.format(new Date(member.createdAt))}</time></dd></div>
                </dl>
                {status === "PENDING" && <div className="admin-member-actions">
                  <button type="button" onClick={() => { setSelected(member); setDecision("APPROVED"); setReason(""); }}>Approve</button>
                  <button type="button" className="danger-action" onClick={() => { setSelected(member); setDecision("REJECTED"); setReason(""); }}>Reject</button>
                </div>}
              </li>
            ))}
          </ol>
        )}
        {state.page.nextCursor && <button className="secondary-action" type="button" onClick={() => void loadMore()} disabled={loadingMore}>{loadingMore ? "Loading…" : "Load more"}</button>}
      </section>

      {selected && <form className="admin-decision" onSubmit={(event) => void submitDecision(event)} aria-labelledby="decision-title">
        <div><p className="eyebrow">Confirm decision</p><h2 id="decision-title">{decision === "APPROVED" ? "Approve" : "Reject"} {selected.fullName}</h2><p>This changes access immediately and creates an immutable audit event.</p></div>
        <label>Reason<textarea value={reason} minLength={8} maxLength={500} required onChange={(event) => setReason(event.target.value)} placeholder="Record the evidence or policy basis for this decision." /></label>
        <div className="admin-decision-actions"><button type="button" onClick={() => setSelected(null)} disabled={saving}>Cancel</button><button type="submit" className={decision === "REJECTED" ? "danger-action" : undefined} disabled={saving || reason.trim().length < 8}>{saving ? "Applying…" : `Confirm ${decision === "APPROVED" ? "approval" : "rejection"}`}</button></div>
      </form>}
    </article>
  );
}

function formatRole(role: AdminMember["role"]) {
  return role === "SUPER_ADMIN" ? "Super admin" : role.charAt(0) + role.slice(1).toLowerCase();
}

function handleSession(error: unknown) {
  if (error instanceof ApiError && error.status === 401) window.location.assign("/oauth2/authorization/institutional");
}

function AdminError({ forbidden }: { forbidden: boolean }) {
  return <section className="status-page"><p className="eyebrow">Administration</p><h1>{forbidden ? "Administrative access required." : "Administration could not be loaded."}</h1><p>{forbidden ? "This area is restricted to approved library administrators." : "No account state was changed. Try again in a moment."}</p>{!forbidden && <button type="button" onClick={() => window.location.reload()}>Try again</button>}<Link to="/catalog">Return to catalog</Link></section>;
}
