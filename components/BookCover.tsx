import Image from "next/image";
import BookCoverSvg from "@/components/BookCoverSvg";
import { cn } from "@/lib/utils";

type BookCoverVariant = "extraSmall" | "small" | "medium" | "regular" | "wide";

const variantStyles: Record<BookCoverVariant, string> = {
  extraSmall: "book-cover_extra_small",
  small: "book-cover_small",
  medium: "book-cover_medium",
  regular: "book-cover_regular",
  wide: "book-cover_wide",
};

const variantSizes: Record<BookCoverVariant, string> = {
  extraSmall: "29px",
  small: "55px",
  medium: "144px",
  regular: "(min-width: 480px) 174px, 114px",
  wide: "(min-width: 480px) 296px, 256px",
};

interface Props {
  className?: string;
  variant?: BookCoverVariant;
  coverColor: string;
  coverImage: string;
  title?: string;
  priority?: boolean;
  decorative?: boolean;
}

const normalizeCoverSource = (coverImage: string) => {
  if (
    /^(https?:|data:|blob:)/i.test(coverImage) ||
    coverImage.startsWith("/")
  ) {
    return coverImage;
  }

  return `/${coverImage}`;
};

const canUseNextImage = (source: string) => {
  if (source.startsWith("/")) return true;

  try {
    const url = new URL(source);
    return (
      url.protocol === "https:" &&
      ["placehold.co", "m.media-amazon.com", "ik.imagekit.io"].includes(
        url.hostname,
      )
    );
  } catch {
    return false;
  }
};

const BookCover = ({
  className,
  variant = "regular",
  coverColor,
  coverImage,
  title,
  priority = false,
  decorative = false,
}: Props) => {
  const source = normalizeCoverSource(coverImage);
  const alt = decorative ? "" : title ? `Cover of ${title}` : "Book cover";

  return (
    <div
      className={cn("relative", variantStyles[variant], className)}
      data-book-cover
    >
      <BookCoverSvg coverColor={coverColor} />
      <div
        className="absolute z-10"
        style={{ left: "12%", width: "87.5%", height: "88%" }}
      >
        {coverImage ? (
          canUseNextImage(source) ? (
            <Image
              src={source}
              alt={alt}
              fill
              sizes={variantSizes[variant]}
              className="rounded-sm object-fill"
              priority={priority}
              loading={priority ? undefined : "lazy"}
              referrerPolicy="no-referrer"
            />
          ) : (
            <img
              src={source}
              alt={alt}
              width={360}
              height={500}
              className="size-full rounded-sm object-fill"
              loading={priority ? "eager" : "lazy"}
              decoding="async"
              fetchPriority={priority ? "high" : "auto"}
              referrerPolicy="no-referrer"
            />
          )
        ) : (
          <div className="flex size-full items-center justify-center rounded-sm bg-slate-100">
            <span className="px-2 text-center text-xs text-[var(--mundia-muted)]">
              Cover unavailable
            </span>
          </div>
        )}
      </div>
    </div>
  );
};

export default BookCover;
