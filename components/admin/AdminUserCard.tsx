import type { Session } from "next-auth";

import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { cn, getInitials } from "@/lib/utils";

interface AdminUserCardProps {
  session: Session;
  variant: "sidebar" | "mobile";
}

const AdminUserCard = ({ session, variant }: AdminUserCardProps) => {
  const isMobile = variant === "mobile";

  return (
    <div
      className={
        isMobile
          ? "flex min-w-0 items-center gap-3 rounded-lg bg-[var(--mundia-paper)] p-3"
          : "user"
      }
    >
      <Avatar className={isMobile ? "size-11" : "size-10"}>
        <AvatarFallback className="bg-amber-100 text-sm text-[var(--mundia-ink)]">
          {getInitials(session.user?.name || "Administrator")}
        </AvatarFallback>
      </Avatar>
      <div className={cn("min-w-0", !isMobile && "flex-1")}>
        <p
          className={cn(
            "truncate font-semibold text-[var(--mundia-ink)]",
            isMobile && "text-sm",
          )}
        >
          {session.user?.name || "Administrator"}
        </p>
        <p
          className={cn(
            "truncate text-xs text-[var(--mundia-muted)]",
            isMobile && "mt-0.5",
          )}
        >
          {session.user?.email}
        </p>
      </div>
    </div>
  );
};

export default AdminUserCard;
