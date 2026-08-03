import { ArrowRight, Building2 } from "lucide-react";
import Link from "next/link";
import ClearLogoutFlag from "@/components/ClearLogoutFlag";
import LocalCredentialSignIn from "@/components/LocalCredentialSignIn";
import { Button } from "@/components/ui/button";
import { signInWithInstitutionalOidc } from "@/lib/actions/auth";
import config from "@/lib/config";

/**
 * Institutional OIDC is the primary product sign-in. The password form is
 * rendered only when the server has admitted the local compatibility mode.
 */
const Page = async ({
  searchParams,
}: {
  searchParams: Promise<{ error?: string }>;
}) => {
  const { error } = await searchParams;
  const oidcEnabled = config.env.oidc.enabled;
  const localCredentialsEnabled = config.env.localCredentialsEnabled;

  return (
    <div className="space-y-6">
      <ClearLogoutFlag />

      <div>
        <h1 className="text-2xl font-semibold text-[var(--mundia-ink)]">
          Sign in
        </h1>
        <p className="mt-1 text-sm text-[var(--mundia-muted)]">
          Access your library account
        </p>
      </div>

      {error && (
        <p
          className="rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-950"
          role="alert"
        >
          Sign-in was not accepted. Confirm that your institutional account is
          active, then contact the library if the problem continues.
        </p>
      )}

      {oidcEnabled && (
        <form action={signInWithInstitutionalOidc}>
          <Button
            type="submit"
            className="flex w-full items-center justify-center gap-2 rounded-lg bg-[var(--mundia-navy)] px-6 py-6 text-base font-semibold text-white transition-colors hover:bg-[var(--mundia-navy-strong)]"
          >
            <Building2 aria-hidden="true" />
            Continue with your institutional account
            <ArrowRight aria-hidden="true" />
          </Button>
        </form>
      )}

      {oidcEnabled && localCredentialsEnabled && (
        <div className="flex items-center gap-3" aria-hidden="true">
          <div className="h-px flex-1 bg-slate-200" />
          <span className="text-xs font-medium uppercase tracking-wide text-[var(--mundia-muted)]">
            Local testing only
          </span>
          <div className="h-px flex-1 bg-slate-200" />
        </div>
      )}

      {localCredentialsEnabled && (
        <div className="space-y-3">
          {!oidcEnabled && (
            <output className="rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-950">
              Use the credentials issued by your library administrator.
            </output>
          )}
          <LocalCredentialSignIn
            institutionalOidcEnabled={oidcEnabled}
          />
        </div>
      )}

      {!oidcEnabled && !localCredentialsEnabled && (
        <p
          className="rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-950"
          role="alert"
        >
          Sign-in is unavailable because no approved identity provider is
          configured. Contact the library administrator.
        </p>
      )}

      {config.env.allowPublicSignup && (
        <p className="text-center text-sm text-[var(--mundia-muted)]">
          New student?{" "}
          <Link
            href="/sign-up"
            className="font-semibold text-[var(--mundia-navy)] hover:underline"
          >
            Create a local account
          </Link>
        </p>
      )}
    </div>
  );
};

export default Page;
