import type { LearningResource } from "../api/client";

export function ResourceCover({ resource, detail = false }: { resource: LearningResource; detail?: boolean }) {
  if (resource.coverUrl) {
    return (
      <div className={`resource-cover ${detail ? "resource-cover-detail" : ""}`}>
        <img src={resource.coverUrl} alt={resource.coverAlt ?? `Cover of ${resource.title}`} loading={detail ? "eager" : "lazy"} />
      </div>
    );
  }
  return (
    <div className={`resource-cover resource-bookplate ${detail ? "resource-cover-detail" : ""}`} aria-hidden="true">
      <span>{resource.sourceName}</span>
      <strong>{resource.title.slice(0, 80)}</strong>
    </div>
  );
}
