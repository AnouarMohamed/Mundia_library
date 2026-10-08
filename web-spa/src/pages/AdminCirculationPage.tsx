import { useEffect, useState, type ReactNode } from "react";
import { Link } from "react-router-dom";
import {
  ApiError,
  getAdminCirculationOverview,
  getAdminLoanQueue,
  getAdminReservationQueue,
  getCatalogEditions,
  mutateAdminLoan,
  mutateAdminReservation,
  type AdministrativeCirculationOverview,
  type CatalogEdition,
  type LoanHistoryItem,
  type Reservation,
} from "../api/client";
import { AdminNavigation } from "../components/AdminNavigation";

type DeskState =
  | { kind: "loading" }
  | { kind: "ready"; overview: AdministrativeCirculationOverview; loans: LoanHistoryItem[]; activeLoans: LoanHistoryItem[]; reservations: Reservation[]; editions: Map<string, CatalogEdition> }
  | { kind: "forbidden" | "failed" };
type PendingCommand =
  | { kind: "loan"; record: LoanHistoryItem; operation: "approve" | "reject" | "return"; idempotencyKey: string }
  | { kind: "reservation"; record: Reservation; operation: "fulfill" | "expire"; idempotencyKey: string };

const dateTime = new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" });

export function AdminCirculationPage() {
  const [state, setState] = useState<DeskState>({ kind: "loading" });
  const [pending, setPending] = useState<PendingCommand | null>(null);
  const [saving, setSaving] = useState(false);
  const [feedback, setFeedback] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    void Promise.all([
      getAdminCirculationOverview(controller.signal),
      getAdminLoanQueue("REQUESTED", undefined, controller.signal),
      getAdminLoanQueue("ACTIVE", undefined, controller.signal),
      getAdminReservationQueue("READY", undefined, controller.signal),
    ]).then(async ([overview, loans, activeLoans, reservations]) => {
      const ids = Array.from(new Set([...loans.items, ...activeLoans.items, ...reservations.items].map((item) => item.editionId)));
      const editions = await getCatalogEditions(ids, controller.signal);
      if (!controller.signal.aborted) setState({
        kind: "ready",
        overview,
        loans: loans.items,
        activeLoans: activeLoans.items,
        reservations: reservations.items,
        editions: new Map(editions.map((edition) => [edition.editionId, edition])),
      });
    }).catch((error: unknown) => {
      if (controller.signal.aborted) return;
      if (error instanceof ApiError && error.status === 401) return window.location.assign("/oauth2/authorization/institutional");
      setState({ kind: error instanceof ApiError && error.status === 403 ? "forbidden" : "failed" });
    });
    return () => controller.abort();
  }, []);

  async function confirmCommand() {
    if (!pending || state.kind !== "ready" || saving) return;
    setSaving(true);
    setFeedback(null);
    try {
      if (pending.kind === "loan") {
        await mutateAdminLoan(pending.record.loanId, pending.operation, pending.idempotencyKey);
        setState({
          ...state,
          overview: updateOverview(state.overview, pending),
          loans: state.loans.filter((loan) => loan.loanId !== pending.record.loanId),
          activeLoans: state.activeLoans.filter((loan) => loan.loanId !== pending.record.loanId),
        });
      } else {
        await mutateAdminReservation(pending.record.reservationId, pending.operation, pending.idempotencyKey);
        setState({
          ...state,
          overview: updateOverview(state.overview, pending),
          reservations: state.reservations.filter((item) => item.reservationId !== pending.record.reservationId),
        });
      }
      setFeedback("The circulation command was applied and recorded in the audit stream.");
      setPending(null);
    } catch (error: unknown) {
      if (error instanceof ApiError && error.status === 401) window.location.assign("/oauth2/authorization/institutional");
      setFeedback(error instanceof ApiError && error.status === 409
        ? "The record changed elsewhere. Reload before applying another command."
        : "The command was not applied. No circulation state was changed.");
    } finally {
      setSaving(false);
    }
  }

  if (state.kind === "loading") return <div className="admin-skeleton" aria-label="Loading circulation administration"><span /><span /><span /></div>;
  if (state.kind !== "ready") return <section className="status-page"><p className="eyebrow">Circulation administration</p><h1>{state.kind === "forbidden" ? "Administrative access required." : "The circulation desk could not be loaded."}</h1><Link to="/catalog">Return to catalog</Link></section>;

  const title = (editionId: string) => state.editions.get(editionId)?.title ?? "Catalog record unavailable";
  return (
    <article className="admin-shell">
      <AdminNavigation />
      <header className="admin-heading">
        <div><p className="eyebrow">Library operations</p><h1>Circulation desk</h1><p>Decide pending loans, record returns, and clear ready reservation holds.</p></div>
        <aside><strong>Authoritative commands</strong><span>Every operation rechecks your current Membership role and uses a narrowly scoped Circulation token.</span></aside>
      </header>
      <section className="admin-overview admin-overview-five" aria-label="Circulation overview">
        <Metric label="Loan requests" value={state.overview.requestedLoans} />
        <Metric label="Active loans" value={state.overview.activeLoans} />
        <Metric label="Overdue" value={state.overview.overdueLoans} alert={state.overview.overdueLoans > 0} />
        <Metric label="Waiting holds" value={state.overview.waitingReservations} />
        <Metric label="Ready holds" value={state.overview.readyReservations} />
      </section>
      {feedback && <output className="admin-feedback">{feedback}</output>}
      <div className="admin-desk-grid">
        <Queue title="Pending loan requests" empty="No loan requests need review.">
          {state.loans.map((loan) => <li key={loan.loanId}>
            <div><strong>{title(loan.editionId)}</strong><span>Member {shortId(loan.memberId)} · requested <time dateTime={loan.requestedAt}>{dateTime.format(new Date(loan.requestedAt))}</time></span></div>
            <div className="admin-member-actions"><button type="button" onClick={() => setPending(adminLoanCommand(loan, "approve"))}>Approve</button><button className="danger-action" type="button" onClick={() => setPending(adminLoanCommand(loan, "reject"))}>Reject</button></div>
          </li>)}
        </Queue>
        <Queue title="Ready reservation holds" empty="No ready holds need action.">
          {state.reservations.map((reservation) => <li key={reservation.reservationId}>
            <div><strong>{title(reservation.editionId)}</strong><span>Member {shortId(reservation.memberId)}{reservation.expiresAt && <> · expires <time dateTime={reservation.expiresAt}>{dateTime.format(new Date(reservation.expiresAt))}</time></>}</span></div>
            <div className="admin-member-actions"><button type="button" onClick={() => setPending(adminReservationCommand(reservation, "fulfill"))}>Fulfil</button><button className="danger-action" type="button" onClick={() => setPending(adminReservationCommand(reservation, "expire"))}>Expire</button></div>
          </li>)}
        </Queue>
        <Queue title="Active loans" empty="No active loans are currently loaded.">
          {state.activeLoans.map((loan) => <li key={loan.loanId}>
            <div><strong>{title(loan.editionId)}</strong><span>Member {shortId(loan.memberId)}{loan.dueAt && <> · due <time dateTime={loan.dueAt}>{dateTime.format(new Date(loan.dueAt))}</time></>}</span></div>
            <div className="admin-member-actions"><button type="button" onClick={() => setPending(adminLoanCommand(loan, "return"))}>Record return</button></div>
          </li>)}
        </Queue>
      </div>
      {pending && <section className="admin-command-confirm" aria-labelledby="command-confirm-title">
        <div><p className="eyebrow">Confirm command</p><h2 id="command-confirm-title">{commandLabel(pending)}</h2><p>This operation is idempotent and recorded in the circulation audit stream.</p></div>
        <div className="admin-decision-actions"><button type="button" disabled={saving} onClick={() => setPending(null)}>Cancel</button><button type="button" disabled={saving} className={pending.operation === "reject" || pending.operation === "expire" ? "danger-action" : undefined} onClick={() => void confirmCommand()}>{saving ? "Applying…" : "Confirm"}</button></div>
      </section>}
    </article>
  );
}

function Metric({ label, value, alert = false }: { label: string; value: number; alert?: boolean }) {
  return <div><span>{label}</span><strong className={alert ? "metric-alert" : undefined}>{value}</strong></div>;
}

function Queue({ title, empty, children }: { title: string; empty: string; children: ReactNode[] }) {
  return <section className="admin-workspace"><div className="admin-section-heading"><h2>{title}</h2></div>{children.length === 0 ? <div className="admin-empty"><p>{empty}</p></div> : <ol className="admin-command-list">{children}</ol>}</section>;
}

function shortId(value: string) { return value.slice(0, 8); }
function adminLoanCommand(record: LoanHistoryItem, operation: "approve" | "reject" | "return"): PendingCommand {
  return { kind: "loan", record, operation, idempotencyKey: `spa:admin-loan-${operation}:${crypto.randomUUID()}` };
}
function adminReservationCommand(record: Reservation, operation: "fulfill" | "expire"): PendingCommand {
  return { kind: "reservation", record, operation, idempotencyKey: `spa:admin-reservation-${operation}:${crypto.randomUUID()}` };
}
function updateOverview(overview: AdministrativeCirculationOverview, command: PendingCommand): AdministrativeCirculationOverview {
  const next = { ...overview };
  if (command.kind === "reservation") {
    next.readyReservations = Math.max(0, next.readyReservations - 1);
    return next;
  }
  if (command.operation === "approve") {
    next.requestedLoans = Math.max(0, next.requestedLoans - 1);
    next.activeLoans += 1;
  } else if (command.operation === "reject") {
    next.requestedLoans = Math.max(0, next.requestedLoans - 1);
  } else {
    next.activeLoans = Math.max(0, next.activeLoans - 1);
    if (command.record.dueAt && new Date(command.record.dueAt).getTime() < Date.now()) {
      next.overdueLoans = Math.max(0, next.overdueLoans - 1);
    }
  }
  return next;
}
function commandLabel(command: PendingCommand) {
  const labels = { approve: "Approve this loan request?", reject: "Reject this loan request?", return: "Record this return?", fulfill: "Fulfil this reservation?", expire: "Expire this reservation hold?" };
  return labels[command.operation];
}
