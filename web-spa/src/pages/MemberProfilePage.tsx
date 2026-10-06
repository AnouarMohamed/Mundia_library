import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { ApiError, getMemberProfile, type MemberProfile } from "../api/client";

type LoadState =
  | { kind: "loading" }
  | { kind: "ready"; profile: MemberProfile }
  | { kind: "missing" | "forbidden" | "failed" };

const dateFormatter = new Intl.DateTimeFormat(undefined, {
  year: "numeric",
  month: "long",
  day: "numeric",
});

const statusCopy: Record<MemberProfile["status"], { label: string; description: string }> = {
  APPROVED: { label: "Active", description: "Your library membership is active." },
  PENDING: { label: "Pending review", description: "Library staff are reviewing your membership." },
  REJECTED: {
    label: "Access not approved",
    description: "Contact library staff if you believe this is incorrect.",
  },
};

const roleLabels: Record<MemberProfile["role"], string> = {
  USER: "Student",
  ADMIN: "Library administrator",
  SUPER_ADMIN: "Super administrator",
};

export function MemberProfilePage() {
  const [state, setState] = useState<LoadState>({ kind: "loading" });

  useEffect(() => {
    const controller = new AbortController();
    void getMemberProfile(controller.signal)
      .then((profile) => setState({ kind: "ready", profile }))
      .catch((error: unknown) => {
        if (controller.signal.aborted) return;
        if (error instanceof ApiError && error.status === 401) {
          window.location.assign("/oauth2/authorization/institutional");
          return;
        }
        if (error instanceof ApiError && error.status === 404) {
          setState({ kind: "missing" });
          return;
        }
        setState({ kind: error instanceof ApiError && error.status === 403 ? "forbidden" : "failed" });
      });
    return () => controller.abort();
  }, []);

  if (state.kind === "loading") {
    return <div className="profile-skeleton" aria-label="Loading account"><span /><span /><span /><span /></div>;
  }
  if (state.kind !== "ready") return <ProfileError kind={state.kind} />;

  const { profile } = state;
  const membership = statusCopy[profile.status];
  return (
    <article className="profile-shell">
      <header className="profile-heading">
        <p className="eyebrow">My account</p>
        <h1>{profile.fullName}</h1>
        <div className="membership-summary">
          <span className={`membership-status membership-status-${profile.status.toLowerCase()}`}>
            {membership.label}
          </span>
          <p>{membership.description}</p>
        </div>
      </header>

      <div className="profile-layout">
        <section aria-labelledby="account-details-title">
          <h2 id="account-details-title">Account details</h2>
          <dl className="profile-ledger">
            <ProfileField label="Full name" value={profile.fullName} />
            <ProfileField label="Email" value={profile.email} />
            <ProfileField label="University ID" value={String(profile.universityId)} />
            <ProfileField label="Membership" value={membership.label} />
            <ProfileField label="Access level" value={roleLabels[profile.role]} />
            <ProfileField label="Member since" value={dateFormatter.format(new Date(profile.createdAt))} dateTime={profile.createdAt} />
            <ProfileField label="Last updated" value={dateFormatter.format(new Date(profile.updatedAt))} dateTime={profile.updatedAt} />
          </dl>
        </section>
        <aside className="profile-note" aria-labelledby="account-help-title">
          <p className="eyebrow">Membership record</p>
          <h2 id="account-help-title">Need to correct something?</h2>
          <p>These details come from the university membership service. Contact library staff to request a correction.</p>
        </aside>
      </div>
    </article>
  );
}

function ProfileField({ label, value, dateTime }: { label: string; value: string; dateTime?: string }) {
  return (
    <div>
      <dt>{label}</dt>
      <dd>{dateTime ? <time dateTime={dateTime}>{value}</time> : value}</dd>
    </div>
  );
}

function ProfileError({ kind }: { kind: "missing" | "forbidden" | "failed" }) {
  const copy = kind === "missing"
    ? { title: "No membership record was found.", body: "Contact library staff to connect your institutional account." }
    : kind === "forbidden"
      ? { title: "This account cannot view a profile.", body: "Your session is valid, but profile access is not enabled for it." }
      : { title: "We could not load your account.", body: "Your account was not changed. Try again in a moment." };
  return (
    <section className="status-page">
      <p className="eyebrow">My account</p>
      <h1>{copy.title}</h1>
      <p>{copy.body}</p>
      {kind === "failed" ? (
        <button type="button" onClick={() => window.location.reload()}>Try again</button>
      ) : (
        <Link to="/resources">Return to open learning</Link>
      )}
    </section>
  );
}
