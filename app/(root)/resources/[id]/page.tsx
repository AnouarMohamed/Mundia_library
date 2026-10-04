import Link from "next/link";
import { notFound } from "next/navigation";
import { z } from "zod";
import { getVerifiedLearningResource } from "@/lib/learning-resources/queries";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const Page = async ({ params }: { params: Promise<{ id: string }> }) => {
  const parsed = z.string().uuid().safeParse((await params).id);
  if (!parsed.success) notFound();
  const resource = await getVerifiedLearningResource(parsed.data);
  if (!resource) notFound();

  return (
    <article className="mx-auto w-full max-w-4xl py-2 sm:py-5">
      <Link
        href="/resources"
        className="inline-flex min-h-11 items-center text-sm font-semibold text-[var(--mundia-navy)] hover:underline"
      >
        Back to open learning
      </Link>
      <header className="mt-4 border-b border-[var(--mundia-line)] pb-7">
        <p className="text-sm font-medium text-[var(--mundia-gold-strong)]">
          {resource.category}
        </p>
        <h1 className="mt-2 max-w-3xl font-serif text-3xl font-normal leading-tight text-[var(--mundia-ink)] sm:text-4xl">
          {resource.title}
        </h1>
        <p className="mt-3 text-base text-[var(--mundia-muted)]">
          {resource.author || "Author not listed"}
        </p>
      </header>

      <div className="grid gap-8 py-7 md:grid-cols-[minmax(0,1fr)_17rem]">
        <section>
          <h2 className="font-serif text-2xl text-[var(--mundia-ink)]">
            Access
          </h2>
          <p className="mt-2 max-w-2xl text-sm leading-6 text-[var(--mundia-muted)]">
            Mundia verified the licence evidence but does not host this file.
            The action below resolves to the authoritative source.
          </p>
          <div className="mt-5 flex flex-wrap gap-3">
            {resource.downloadUrl && (
              <a
                href={`/api/learning-resources/${resource.id}/download`}
                className="inline-flex min-h-12 items-center justify-center bg-[var(--mundia-navy)] px-6 text-sm font-semibold text-[var(--mundia-surface)] transition-colors hover:bg-[var(--mundia-navy-strong)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--mundia-navy)] focus-visible:ring-offset-2"
              >
                Download from source
              </a>
            )}
            {resource.readUrl && (
              <a
                href={resource.readUrl}
                target="_blank"
                rel="noopener noreferrer"
                className="inline-flex min-h-12 items-center justify-center border border-[var(--mundia-navy)] px-6 text-sm font-semibold text-[var(--mundia-navy)] transition-colors hover:bg-[var(--mundia-panel)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--mundia-navy)] focus-visible:ring-offset-2"
              >
                Read at source
              </a>
            )}
          </div>
        </section>

        <dl className="divide-y divide-[var(--mundia-line)] border-y border-[var(--mundia-line)] text-sm">
          <div className="py-3">
            <dt className="text-[var(--mundia-muted)]">Licence</dt>
            <dd className="mt-1 font-semibold text-[var(--mundia-ink)]">
              <a
                href={resource.licenseUrl || resource.verificationEvidenceUrl || "#"}
                target="_blank"
                rel="noopener noreferrer"
                className="hover:underline"
              >
                {resource.licenseExpression}
              </a>
            </dd>
          </div>
          <div className="py-3">
            <dt className="text-[var(--mundia-muted)]">Source</dt>
            <dd className="mt-1 break-words font-semibold text-[var(--mundia-ink)]">
              <a
                href={resource.sourceUrl}
                target="_blank"
                rel="noopener noreferrer"
                className="hover:underline"
              >
                {resource.sourceName}
              </a>
            </dd>
          </div>
          <div className="py-3">
            <dt className="text-[var(--mundia-muted)]">Language</dt>
            <dd className="mt-1 font-semibold text-[var(--mundia-ink)]">
              {resource.language.toUpperCase()}
            </dd>
          </div>
          <div className="py-3">
            <dt className="text-[var(--mundia-muted)]">Rights evidence</dt>
            <dd className="mt-1">
              <a
                href={resource.verificationEvidenceUrl || resource.sourceUrl}
                target="_blank"
                rel="noopener noreferrer"
                className="font-semibold text-[var(--mundia-navy)] hover:underline"
              >
                View verification source
              </a>
            </dd>
          </div>
        </dl>
      </div>
    </article>
  );
};

export default Page;
