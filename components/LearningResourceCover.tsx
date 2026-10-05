import Image from "next/image";
import { normalizeOfficialLearningResourceCover } from "@/lib/learning-resources/cover-policy";

interface LearningResourceCoverProps {
  title: string;
  category: string;
  sourceName: string;
  coverUrl: string | null;
  coverAlt: string | null;
  size?: "list" | "detail";
}

export function LearningResourceCover({
  title,
  category,
  sourceName,
  coverUrl,
  coverAlt,
  size = "list",
}: LearningResourceCoverProps) {
  const officialCover = normalizeOfficialLearningResourceCover(coverUrl);
  const dimensions =
    size === "detail"
      ? "aspect-[3/4] w-full max-w-56"
      : "h-[7.25rem] w-[5.25rem]";

  return (
    <div
      className={`${dimensions} shrink-0 overflow-hidden border border-[var(--mundia-line)] bg-[var(--mundia-panel)]`}
    >
      {officialCover ? (
        // The browser loads this verified official-source asset directly. Mundia
        // does not copy it into application storage or the Next.js image proxy.
        <Image
          src={officialCover}
          alt={coverAlt || `Official cover of ${title}`}
          width={280}
          height={373}
          loading={size === "detail" ? "eager" : "lazy"}
          referrerPolicy="no-referrer"
          unoptimized
          className="h-full w-full object-cover"
        />
      ) : (
        <div
          aria-hidden="true"
          className="flex h-full flex-col justify-between bg-[var(--mundia-navy-strong)] p-2.5 text-[var(--mundia-surface)] sm:p-3"
        >
          <span className="line-clamp-2 text-[9px] font-semibold leading-tight tracking-[0.12em] text-[var(--mundia-gold)] uppercase">
            {sourceName}
          </span>
          <span className="font-serif text-2xl leading-none">
            {initials(category)}
          </span>
          <span className="line-clamp-3 text-[9px] leading-tight text-[var(--mundia-surface)]/80">
            {title}
          </span>
        </div>
      )}
    </div>
  );
}

function initials(value: string) {
  const result = value
    .split(/[^\p{L}\p{N}]+/u)
    .filter(Boolean)
    .slice(0, 3)
    .map((part) => part[0])
    .join("")
    .toUpperCase();
  return result || "OL";
}
