import Link from "next/link";
import { count, desc, eq } from "drizzle-orm";
import { db } from "@/database/drizzle";
import { learningResources } from "@/database/schema";
import { reviewLearningResource } from "@/lib/admin/actions/learning-resources";

const Page = async () => {
  const [resources, totals] = await Promise.all([
    db
      .select()
      .from(learningResources)
      .where(eq(learningResources.verificationStatus, "QUARANTINED"))
      .orderBy(desc(learningResources.updatedAt), desc(learningResources.id))
      .limit(50),
    db
      .select({ count: count() })
      .from(learningResources)
      .where(eq(learningResources.verificationStatus, "QUARANTINED")),
  ]);
  const total = Number(totals[0]?.count ?? 0);

  return (
    <section className="admin-content">
      <header className="mb-6 border-b border-[var(--mundia-line)] pb-6">
        <p className="text-sm text-[var(--mundia-muted)]">Rights review</p>
        <h1 className="mt-1 font-serif text-3xl font-normal text-[var(--mundia-ink)]">
          Learning resources
        </h1>
        <p className="mt-2 max-w-3xl text-sm leading-6 text-[var(--mundia-muted)]">
          {total} quarantined records are hidden from students. Approve only
          when the exact book has an allowlisted licence and durable evidence.
        </p>
      </header>

      {resources.length === 0 ? (
        <div className="border border-[var(--mundia-line)] bg-[var(--mundia-surface)] px-5 py-10">
          <h2 className="font-serif text-2xl text-[var(--mundia-ink)]">
            Review queue is clear
          </h2>
          <p className="mt-2 text-sm text-[var(--mundia-muted)]">
            New ambiguous records will appear after the next bounded import.
          </p>
        </div>
      ) : (
        <ul className="divide-y divide-[var(--mundia-line)] border-y border-[var(--mundia-line)]">
          {resources.map((resource) => (
            <li key={resource.id} className="py-5">
              <details>
                <summary className="flex min-h-12 cursor-pointer list-none items-start justify-between gap-4 marker:hidden">
                  <div className="min-w-0">
                    <p className="text-xs font-medium text-[var(--mundia-gold-strong)]">
                      {resource.category}
                    </p>
                    <h2 className="mt-1 text-base font-semibold text-[var(--mundia-ink)]">
                      {resource.title}
                    </h2>
                    <p className="mt-1 text-sm text-[var(--mundia-muted)]">
                      {resource.author || "Author not listed"}
                    </p>
                  </div>
                  <span className="shrink-0 border border-[var(--mundia-line)] px-2.5 py-1 text-xs text-[var(--mundia-muted)]">
                    Review
                  </span>
                </summary>

                <div className="mt-4 grid gap-5 border-t border-[var(--mundia-line)] pt-5 lg:grid-cols-2">
                  <div className="text-sm leading-6">
                    <p className="text-[var(--mundia-muted)]">
                      {resource.verificationReason}
                    </p>
                    <dl className="mt-4 space-y-2">
                      <div>
                        <dt className="text-xs text-[var(--mundia-muted)]">Source</dt>
                        <dd className="break-all">
                          <Link
                            href={resource.sourceUrl}
                            target="_blank"
                            rel="noopener noreferrer"
                            className="font-semibold text-[var(--mundia-navy)] hover:underline"
                          >
                            {resource.sourceName}
                          </Link>
                        </dd>
                      </div>
                      <div>
                        <dt className="text-xs text-[var(--mundia-muted)]">Detected licence</dt>
                        <dd>{resource.licenseExpression || "None"}</dd>
                      </div>
                    </dl>
                  </div>

                  <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-1 xl:grid-cols-2">
                    <form action={reviewLearningResource} className="space-y-3">
                      <input type="hidden" name="resourceId" value={resource.id} />
                      <input type="hidden" name="decision" value="APPROVED" />
                      <label className="block text-sm font-medium text-[var(--mundia-ink)]">
                        Licence
                        <select
                          name="licenseExpression"
                          required
                          className="catalog-field mt-1.5 min-h-11 w-full"
                        >
                          <option value="">Select verified licence</option>
                          <option value="CC-BY">CC BY</option>
                          <option value="CC-BY-SA">CC BY-SA</option>
                          <option value="CC0">CC0</option>
                          <option value="PUBLIC-DOMAIN">Public domain</option>
                        </select>
                      </label>
                      <label className="block text-sm font-medium text-[var(--mundia-ink)]">
                        Evidence URL
                        <input
                          name="evidenceUrl"
                          type="url"
                          required
                          maxLength={2048}
                          placeholder="https://publisher.example/licence"
                          className="catalog-field mt-1.5 min-h-11 w-full"
                        />
                      </label>
                      <label className="block text-sm font-medium text-[var(--mundia-ink)]">
                        Approval reason
                        <textarea
                          name="reason"
                          required
                          minLength={10}
                          maxLength={1000}
                          rows={3}
                          className="catalog-field mt-1.5 w-full resize-y"
                        />
                      </label>
                      <button
                        type="submit"
                        className="min-h-11 w-full bg-[var(--mundia-navy)] px-4 text-sm font-semibold text-[var(--mundia-surface)] hover:bg-[var(--mundia-navy-strong)]"
                      >
                        Approve resource
                      </button>
                    </form>

                    <form action={reviewLearningResource} className="space-y-3">
                      <input type="hidden" name="resourceId" value={resource.id} />
                      <input type="hidden" name="decision" value="REJECTED" />
                      <label className="block text-sm font-medium text-[var(--mundia-ink)]">
                        Rejection reason
                        <textarea
                          name="reason"
                          required
                          minLength={10}
                          maxLength={1000}
                          rows={4}
                          className="catalog-field mt-1.5 w-full resize-y"
                        />
                      </label>
                      <button
                        type="submit"
                        className="min-h-11 w-full border border-red-300 px-4 text-sm font-semibold text-red-900 hover:bg-red-50"
                      >
                        Reject resource
                      </button>
                    </form>
                  </div>
                </div>
              </details>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
};

export default Page;
