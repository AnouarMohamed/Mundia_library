import Link from "next/link";
import { notFound } from "next/navigation";
import { z } from "zod";
import { getVerifiedLearningResource } from "@/lib/learning-resources/queries";
import { LearningResourceCover } from "@/components/LearningResourceCover";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const Page = async ({ params }: { params: Promise<{ id: string }> }) => {
  const parsed = z
    .string()
    .uuid()
    .safeParse((await params).id);
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
      <header className="mt-4 grid gap-6 border-b border-[var(--mundia-line)] pb-7 sm:grid-cols-[9rem_minmax(0,1fr)] sm:items-end">
        <LearningResourceCover
          title={resource.title}
          category={resource.category}
          sourceName={resource.sourceName}
          coverUrl={resource.coverUrl}
          coverAlt={resource.coverAlt}
          size="detail"
        />
        <div>
          <p className="text-sm font-medium text-[var(--mundia-gold-strong)]">
            {resource.category}
          </p>
          <h1 className="mt-2 max-w-3xl font-serif text-3xl font-normal leading-tight text-[var(--mundia-ink)] sm:text-4xl">
            {resource.title}
          </h1>
          <p className="mt-3 text-base text-[var(--mundia-muted)]">
            {resource.author || "Author not listed"}
          </p>
        </div>
      </header>

      <div className="grid gap-8 py-7 md:grid-cols-[minmax(0,1fr)_17rem]">
        <section>
          {resource.description && (
            <div className="mb-8">
              <h2 className="font-serif text-2xl text-[var(--mundia-ink)]">
                About this resource
              </h2>
              <p className="mt-3 max-w-[70ch] whitespace-pre-line text-sm leading-6 text-[var(--mundia-muted)]">
                {resource.description}
              </p>
            </div>
          )}
          <h2 className="font-serif text-2xl text-[var(--mundia-ink)]">
            Access
          </h2>
          <p className="mt-2 max-w-2xl text-sm leading-6 text-[var(--mundia-muted)]">
            {resource.downloadUrl
              ? "This file is downloaded from a verified third-party university or open publisher. Mundia checks the licence and official source, but does not host the file."
              : "This resource opens on the verified publisher or library page. Mundia checks the rights evidence and source, but does not host a copy."}
          </p>
          <div className="mt-5 flex flex-wrap gap-3">
            {resource.downloadUrl && (
              <a
                href={`/api/learning-resources/${resource.id}/download`}
                download
                className="inline-flex min-h-12 items-center justify-center bg-[var(--mundia-navy)] px-6 text-sm font-semibold text-[var(--mundia-surface)] transition-colors hover:bg-[var(--mundia-navy-strong)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--mundia-navy)] focus-visible:ring-offset-2"
              >
                Download from {resource.sourceName}
              </a>
            )}
            {resource.readUrl && (
              <a
                href={resource.readUrl}
                target="_blank"
                rel="noopener noreferrer"
                className="inline-flex min-h-12 items-center justify-center border border-[var(--mundia-navy)] px-6 text-sm font-semibold text-[var(--mundia-navy)] transition-colors hover:bg-[var(--mundia-panel)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--mundia-navy)] focus-visible:ring-offset-2"
              >
                Open official source
              </a>
            )}
          </div>
        </section>

        <dl className="divide-y divide-[var(--mundia-line)] border-y border-[var(--mundia-line)] text-sm">
          <div className="py-3">
            <dt className="text-[var(--mundia-muted)]">Licence</dt>
            <dd className="mt-1 font-semibold text-[var(--mundia-ink)]">
              <a
                href={
                  resource.licenseUrl || resource.verificationEvidenceUrl || "#"
                }
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
              <p className="mt-2 leading-5 text-[var(--mundia-muted)]">
                {resource.verificationReason}
              </p>
            </dd>
          </div>
        </dl>
      </div>
    </article>
  );
};

export default Page;
