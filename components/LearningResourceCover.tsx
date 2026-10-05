import Image from "next/image";
import { normalizeOfficialLearningResourceCover } from "@/lib/learning-resources/cover-policy";
import { decodeDisplayText } from "@/lib/learning-resources/display-text";

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
  const displayTitle = decodeDisplayText(title);
  const displaySource = decodeDisplayText(sourceName);
  const dimensions =
    size === "detail"
      ? "aspect-[3/4] w-full max-w-56"
      : "h-28 w-20 sm:h-40 sm:w-28";

  return (
    <div
      className={`${dimensions} shrink-0 overflow-hidden border border-[var(--mundia-line)] bg-[var(--mundia-panel)]`}
    >
      {officialCover ? (
        // The browser loads this verified official-source asset directly. Mundia
        // does not copy it into application storage or the Next.js image proxy.
        <Image
          src={officialCover}
          alt={coverAlt ? decodeDisplayText(coverAlt) : `Official cover of ${displayTitle}`}
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
          className="flex h-full flex-col bg-[var(--mundia-navy-strong)] p-2.5 text-[var(--mundia-surface)] sm:p-3.5"
        >
          <span className="line-clamp-2 text-[8px] font-semibold leading-tight tracking-[0.14em] text-[var(--mundia-gold)] uppercase sm:text-[9px]">
            {displaySource}
          </span>
          <span className="mt-3 font-serif text-2xl leading-none sm:text-3xl">
            {initials(category)}
          </span>
          <span className="mt-auto line-clamp-3 border-t border-[var(--mundia-surface)]/20 pt-2 text-[8px] font-medium leading-[1.25] text-[var(--mundia-surface)]/85 sm:text-[10px]">
            {displayTitle}
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
