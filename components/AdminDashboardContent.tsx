"use client";

import React from "react";
import Link from "next/link";
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import {
  ArrowDownToLine,
  ArrowUpFromLine,
  BookOpen,
  ChartColumnIncreasing,
  LibraryBig,
  ShieldCheck,
  Users,
} from "lucide-react";
import AdminStatsSkeleton from "@/components/skeletons/AdminStatsSkeleton";
import { useAdminStats } from "@/hooks/useQueries";
import { useApproveBorrow } from "@/hooks/useMutations";
import type { AdminStats } from "@/lib/services/admin";

type RecentBorrow = {
  id: string;
  bookTitle: string;
  userName: string;
  status: string;
};

type RecentUser = {
  id: string;
  fullName: string;
  email: string;
  status: string;
};

type CategoryStat = {
  genre: string;
  count: number;
  totalCopies: number;
  availableCopies: number;
  avgRating: number;
};

type TopRatedBook = {
  id: string;
  title: string;
  author: string;
  rating: number;
};

type BorrowTrend = {
  date: string;
  borrows: number;
  returns: number;
};

type DashboardStats = AdminStats & {
  recentBorrows?: RecentBorrow[];
  recentUsers?: RecentUser[];
  categoryStats?: CategoryStat[];
  booksByYear?: Array<[string, number]>;
  booksByLanguage?: Array<[string, number]>;
  topRatedBooks?: TopRatedBook[];
  activeBooks?: number;
  inactiveBooks?: number;
  booksWithISBN?: number;
  booksWithPublisher?: number;
  averagePageCount?: number;
  borrowTrends?: BorrowTrend[];
};

interface AdminDashboardContentProps {
  initialStats?: DashboardStats;
  successMessage?: string;
}

const chartColors = [
  "var(--mundia-teal-strong)",
  "var(--mundia-success)",
  "var(--mundia-gold)",
];
const chartGrid = "var(--mundia-line)";
const chartAxis = "var(--mundia-ink)";
const chartBorrow = "var(--mundia-teal-strong)";
const chartReturn = "var(--mundia-success)";
const chartGold = "var(--mundia-gold)";
const chartTooltipStyle = {
  borderRadius: "0.75rem",
  border: "1px solid var(--mundia-line)",
  background: "var(--mundia-paper)",
  color: "var(--mundia-ink)",
};

const toNumber = (value: unknown): number => {
  const parsed = Number(value ?? 0);
  return Number.isFinite(parsed) ? parsed : 0;
};

const toPercent = (value: number, total: number): number => {
  if (total <= 0) return 0;
  return Math.round((value / total) * 100);
};

const compact = (value: number): string =>
  new Intl.NumberFormat("en-US", {
    notation: "compact",
    maximumFractionDigits: 1,
  }).format(value);

const formatDateLabel = (value: string): string => {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleDateString("en-US", { month: "short", day: "numeric" });
};

const getStatusClasses = (status: string): string => {
  switch (status) {
    case "BORROWED":
      return "status-info";
    case "RETURNED":
      return "status-success";
    case "PENDING":
    default:
      return "status-warning";
  }
};

const Panel = ({
  title,
  subtitle,
  children,
}: {
  title: string;
  subtitle?: string;
  children: React.ReactNode;
}) => (
  <article className="surface-panel-light min-w-0 p-5 sm:p-6">
    <div className="mb-5">
      <h3 className="text-lg font-bold tracking-tight text-[var(--mundia-ink)] sm:text-xl">
        {title}
      </h3>
      {subtitle && (
        <p className="mt-1 text-sm text-[var(--mundia-ink)]/70">{subtitle}</p>
      )}
    </div>
    {children}
  </article>
);

const MetricCard = ({
  title,
  value,
  subtitle,
  icon: Icon,
  tone,
}: {
  title: string;
  value: string;
  subtitle: string;
  icon: React.ComponentType<{ className?: string }>;
  tone: "blue" | "teal" | "amber" | "slate";
}) => {
  const toneStyles: Record<
    "blue" | "teal" | "amber" | "slate",
    { badge: string; ring: string }
  > = {
    blue: {
      badge: "bg-[var(--mundia-ink)]/10 text-[var(--mundia-ink)]",
      ring: "ring-[var(--mundia-line)]/50",
    },
    teal: {
      badge: "bg-[var(--mundia-teal)]/20 text-[var(--mundia-teal-strong)]",
      ring: "ring-[var(--mundia-teal)]/30",
    },
    amber: {
      badge: "bg-[var(--mundia-gold)]/20 text-[var(--mundia-gold-strong)]",
      ring: "ring-[var(--mundia-gold)]/30",
    },
    slate: {
      badge: "bg-[var(--mundia-muted)]/20 text-[var(--mundia-ink)]",
      ring: "ring-[var(--mundia-line)]/50",
    },
  };

  const styles = toneStyles[tone];

  return (
    <article
      className={`rounded-2xl border border-[var(--mundia-line)] bg-white p-4 shadow-sm ring-1 ${styles.ring} sm:p-5`}
    >
      <div className="flex items-start justify-between gap-3">
        <div>
          <p className="text-[10px] font-bold uppercase tracking-[0.08em] text-[var(--mundia-ink)]/60">
            {title}
          </p>
          <p className="mt-2 text-2xl font-bold tracking-tight text-[var(--mundia-ink)] sm:text-3xl">
            {value}
          </p>
        </div>
        <span
          className={`inline-flex size-10 items-center justify-center rounded-xl ${styles.badge}`}
        >
          <Icon className="size-5" />
        </span>
      </div>
      <p className="mt-4 text-xs font-medium text-[var(--mundia-ink)]/65">
        {subtitle}
      </p>
    </article>
  );
};

const EmptyState = ({ label }: { label: string }) => (
  <div className="flex h-60 items-center justify-center rounded-2xl border border-dashed border-[var(--mundia-line)] bg-[var(--mundia-paper)] px-4 text-center text-sm font-medium text-[var(--mundia-ink)]/50">
    {label}
  </div>
);

/**
 * Admin dashboard client component with charts and KPIs.
 */
const AdminDashboardContent: React.FC<AdminDashboardContentProps> = ({
  initialStats,
  successMessage,
}) => {
  const approveBorrowMutation = useApproveBorrow();
  const {
    data: stats,
    isLoading,
    isError,
    error,
  } = useAdminStats(initialStats);

  if (isLoading && !initialStats) {
    return (
      <div className="space-y-6 sm:space-y-8">
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
          {[...Array(4)].map((_, idx) => (
            <AdminStatsSkeleton key={`metric-${idx}`} />
          ))}
        </div>
        <div className="grid grid-cols-1 gap-5 xl:grid-cols-2">
          {[...Array(2)].map((_, idx) => (
            <AdminStatsSkeleton key={`chart-${idx}`} />
          ))}
        </div>
      </div>
    );
  }

  if (isError && !initialStats) {
    return (
      <div className="rounded-2xl border border-red-200 bg-red-50 p-6 text-red-700">
        <p className="text-base font-semibold">Failed to load dashboard data</p>
        <p className="mt-1 text-sm">
          {error instanceof Error ? error.message : "Unknown error"}
        </p>
      </div>
    );
  }

  const statsData = (stats ?? initialStats) as DashboardStats | undefined;
  if (!statsData) return null;

  const totalUsers = toNumber(statsData.totalUsers);
  const approvedUsers = toNumber(statsData.approvedUsers);
  const pendingUsers = toNumber(statsData.pendingUsers);
  const adminUsers = toNumber(statsData.adminUsers);
  const totalBooks = toNumber(statsData.totalBooks);
  const totalCopies = toNumber(statsData.totalCopies);
  const availableCopies = toNumber(statsData.availableCopies);
  const borrowedCopies = Math.max(
    toNumber(statsData.borrowedCopies),
    totalCopies - availableCopies,
  );
  const activeBorrows = toNumber(statsData.activeBorrows);
  const pendingBorrows = toNumber(statsData.pendingBorrows);
  const returnedBooks = toNumber(statsData.returnedBooks);
  const activeBooks = toNumber(statsData.activeBooks);
  const inactiveBooks = toNumber(statsData.inactiveBooks);
  const booksWithISBN = toNumber(statsData.booksWithISBN);
  const averagePageCount = toNumber(statsData.averagePageCount);

  const recentBorrows = (statsData.recentBorrows ?? []) as RecentBorrow[];
  const recentUsers = (statsData.recentUsers ?? []) as RecentUser[];
  const categoryStats = (statsData.categoryStats ?? []) as CategoryStat[];
  const topRatedBooks = (statsData.topRatedBooks ?? []) as TopRatedBook[];
  const borrowTrends = (statsData.borrowTrends ?? []) as BorrowTrend[];

  const booksByYear = ((statsData.booksByYear ?? []) as Array<[string, number]>)
    .map(([year, count]) => ({ year, count: toNumber(count) }))
    .filter((item) => item.year !== "null");

  const booksByLanguage = (
    (statsData.booksByLanguage ?? []) as Array<[string, number]>
  )
    .map(([language, count]) => ({ language, count: toNumber(count) }))
    .slice(0, 6);

  const borrowTrendData = borrowTrends.map((item) => ({
    label: formatDateLabel(item.date),
    borrows: toNumber(item.borrows),
    returns: toNumber(item.returns),
  }));

  const borrowMixData = [
    { name: "Borrowed", value: activeBorrows, color: chartColors[0] },
    { name: "Pending", value: pendingBorrows, color: chartColors[2] },
    { name: "Returned", value: returnedBooks, color: chartColors[1] },
  ].filter((item) => item.value > 0);

  const maxLanguageCount = Math.max(
    1,
    ...booksByLanguage.map((item) => item.count),
  );
  const maxGenreCount = Math.max(1, ...categoryStats.map((item) => item.count));

  const utilizationRate = toPercent(borrowedCopies, totalCopies);
  const approvalRate = toPercent(approvedUsers, totalUsers);

  return (
    <div className="mx-auto w-full max-w-[1500px] space-y-6 pr-1 sm:space-y-8 sm:pr-2 lg:pr-4">
      {successMessage === "admin-granted" && (
        <div className="status-success rounded-2xl border px-4 py-3">
          <p className="text-sm font-semibold">Admin access granted</p>
          <p className="text-sm">
            You now have access to dashboard analytics and management actions.
          </p>
        </div>
      )}

      <header className="mb-8 space-y-5">
        <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
          <div className="space-y-1">
            <div className="flex items-center gap-2 text-[var(--mundia-teal-strong)]">
              <div className="h-4 w-1 rounded-full bg-current" />
              <p className="text-[10px] font-bold uppercase tracking-[0.12em]">
                System Administration
              </p>
            </div>
            <h2 className="text-3xl font-bold tracking-tight text-[var(--mundia-ink)] sm:text-4xl">
              Dashboard Overview
            </h2>
            <p className="max-w-2xl text-sm text-[var(--mundia-ink)]/70 sm:text-base">
              Operational overview of library circulation, catalog metadata, and
              user activity.
            </p>
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <div className="rounded-xl border border-[var(--mundia-line)] bg-white px-4 py-2 shadow-sm">
              <p className="text-[10px] font-bold uppercase tracking-[0.08em] text-[var(--mundia-ink)]/50">
                Borrow Utilization
              </p>
              <p className="text-lg font-bold text-[var(--mundia-teal-strong)]">
                {utilizationRate}%
              </p>
            </div>
            <div className="rounded-xl border border-[var(--mundia-line)] bg-white px-4 py-2 shadow-sm">
              <p className="text-[10px] font-bold uppercase tracking-[0.08em] text-[var(--mundia-ink)]/50">
                User Approval
              </p>
              <p className="text-lg font-bold text-[var(--mundia-gold-strong)]">
                {approvalRate}%
              </p>
            </div>
          </div>
        </div>
      </header>

      <section className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <MetricCard
          title="Users"
          value={compact(totalUsers)}
          subtitle={`${approvedUsers} approved, ${pendingUsers} pending`}
          icon={Users}
          tone="blue"
        />
        <MetricCard
          title="Books"
          value={compact(totalBooks)}
          subtitle={`${activeBooks} active, ${inactiveBooks} inactive`}
          icon={BookOpen}
          tone="teal"
        />
        <MetricCard
          title="Copies"
          value={compact(totalCopies)}
          subtitle={`${availableCopies} available, ${borrowedCopies} borrowed`}
          icon={LibraryBig}
          tone="amber"
        />
        <MetricCard
          title="Admins"
          value={compact(adminUsers)}
          subtitle={`${booksWithISBN} books with ISBN metadata`}
          icon={ShieldCheck}
          tone="slate"
        />
      </section>

      <section className="grid grid-cols-1 gap-5 xl:grid-cols-2">
        <Panel
          title="Recent Borrow Activity"
          subtitle="Latest circulation updates and pending approvals"
        >
          {recentBorrows.length === 0 ? (
            <EmptyState label="No borrow records found yet." />
          ) : (
            <div className="space-y-2.5">
              {recentBorrows.map((item) => (
                <div
                  key={item.id}
                  className="flex flex-col gap-2 rounded-xl border border-[var(--mundia-line)] bg-white px-3 py-3 sm:flex-row sm:items-center sm:justify-between"
                >
                  <div className="min-w-0">
                    <p className="truncate text-sm font-bold text-[var(--mundia-ink)]">
                      {item.bookTitle}
                    </p>
                    <p className="truncate text-xs text-[var(--mundia-ink)]/60">
                      {item.userName}
                    </p>
                  </div>
                  <div className="flex items-center gap-2">
                    <span
                      className={`inline-flex w-fit items-center rounded-full border px-2.5 py-1 text-[10px] font-bold uppercase tracking-wider ${getStatusClasses(item.status)}`}
                    >
                      {item.status}
                    </span>
                    {item.status === "PENDING" && (
                      <div className="flex items-center gap-2">
                        <button
                          type="button"
                          className="inline-flex min-h-8 items-center rounded-lg bg-[var(--mundia-success)] px-3 py-1 text-[11px] font-bold text-white transition hover:bg-[var(--mundia-success-strong)] disabled:cursor-not-allowed disabled:opacity-60"
                          disabled={approveBorrowMutation.isPending}
                          onClick={() =>
                            approveBorrowMutation.mutate({
                              recordId: item.id,
                              bookTitle: item.bookTitle,
                              userName: item.userName,
                            })
                          }
                        >
                          Approve
                        </button>
                        <Link
                          href="/admin/book-requests?status=PENDING"
                          className="text-[11px] font-bold text-[var(--mundia-ink)]/50 underline underline-offset-2 hover:text-[var(--mundia-ink)]"
                        >
                          Manage
                        </Link>
                      </div>
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}
        </Panel>

        <Panel title="Recent Users" subtitle="Newest registrations">
          {recentUsers.length === 0 ? (
            <EmptyState label="No recent user signups found yet." />
          ) : (
            <div className="space-y-2.5">
              {recentUsers.map((item) => (
                <div
                  key={item.id}
                  className="flex flex-col gap-2 rounded-xl border border-[var(--mundia-line)] bg-white px-3 py-3 sm:flex-row sm:items-center sm:justify-between"
                >
                  <div className="min-w-0">
                    <p className="truncate text-sm font-bold text-[var(--mundia-ink)]">
                      {item.fullName}
                    </p>
                    <p className="truncate text-xs text-[var(--mundia-ink)]/60">
                      {item.email}
                    </p>
                  </div>
                  <span
                    className={`inline-flex w-fit items-center rounded-full border px-2.5 py-1 text-[10px] font-bold uppercase tracking-wider ${getStatusClasses(item.status)}`}
                  >
                    {item.status}
                  </span>
                </div>
              ))}
            </div>
          )}
        </Panel>
      </section>

      <section className="grid grid-cols-1 gap-5 xl:grid-cols-[1.5fr_1fr]">
        <Panel
          title="Borrowing Trend"
          subtitle="Daily borrow and return volume over the last 14 days"
        >
          {borrowTrendData.length === 0 ? (
            <EmptyState label="No recent borrow trend data yet." />
          ) : (
            <div className="h-72 min-w-0 w-full">
              <ResponsiveContainer width="100%" height="100%">
                <AreaChart data={borrowTrendData}>
                  <defs>
                    <linearGradient
                      id="borrowGradient"
                      x1="0"
                      x2="0"
                      y1="0"
                      y2="1"
                    >
                      <stop
                        offset="5%"
                        stopColor={chartBorrow}
                        stopOpacity={0.45}
                      />
                      <stop
                        offset="95%"
                        stopColor={chartBorrow}
                        stopOpacity={0.05}
                      />
                    </linearGradient>
                    <linearGradient
                      id="returnGradient"
                      x1="0"
                      x2="0"
                      y1="0"
                      y2="1"
                    >
                      <stop
                        offset="5%"
                        stopColor={chartReturn}
                        stopOpacity={0.35}
                      />
                      <stop
                        offset="95%"
                        stopColor={chartReturn}
                        stopOpacity={0.05}
                      />
                    </linearGradient>
                  </defs>
                  <CartesianGrid strokeDasharray="3 3" stroke={chartGrid} />
                  <XAxis dataKey="label" stroke={chartAxis} fontSize={12} />
                  <YAxis stroke={chartAxis} fontSize={12} />
                  <Tooltip contentStyle={chartTooltipStyle} />
                  <Area
                    type="monotone"
                    dataKey="borrows"
                    stroke={chartBorrow}
                    strokeWidth={2.2}
                    fill="url(#borrowGradient)"
                  />
                  <Area
                    type="monotone"
                    dataKey="returns"
                    stroke={chartReturn}
                    strokeWidth={2}
                    fill="url(#returnGradient)"
                  />
                </AreaChart>
              </ResponsiveContainer>
            </div>
          )}
        </Panel>

        <Panel title="Borrow Mix" subtitle="Current loan status distribution">
          {borrowMixData.length === 0 ? (
            <EmptyState label="No borrow activity recorded yet." />
          ) : (
            <div className="h-72 min-w-0 w-full">
              <ResponsiveContainer width="100%" height="100%">
                <PieChart>
                  <Pie
                    data={borrowMixData}
                    dataKey="value"
                    nameKey="name"
                    innerRadius={66}
                    outerRadius={100}
                    paddingAngle={3}
                  >
                    {borrowMixData.map((entry) => (
                      <Cell key={entry.name} fill={entry.color} />
                    ))}
                  </Pie>
                  <Tooltip contentStyle={chartTooltipStyle} />
                </PieChart>
              </ResponsiveContainer>
            </div>
          )}
          <div className="mt-4 grid grid-cols-1 gap-2 sm:grid-cols-3">
            {borrowMixData.map((item) => (
              <div
                key={item.name}
                className="rounded-xl border border-[var(--mundia-line)] bg-[var(--mundia-paper)] px-3 py-2 shadow-sm"
              >
                <div className="flex items-center gap-2">
                  <span
                    className="inline-block size-2 rounded-full"
                    style={{ backgroundColor: item.color }}
                  />
                  <p className="text-[10px] font-bold uppercase tracking-wider text-[var(--mundia-ink)]/50">
                    {item.name}
                  </p>
                </div>
                <p className="mt-1 text-lg font-bold text-[var(--mundia-ink)]">
                  {item.value}
                </p>
              </div>
            ))}
          </div>
        </Panel>
      </section>

      <section className="grid grid-cols-1 gap-5 xl:grid-cols-2">
        <Panel
          title="Publication Timeline"
          subtitle="Books grouped by publication year"
        >
          {booksByYear.length === 0 ? (
            <EmptyState label="No publication-year metadata available yet." />
          ) : (
            <div className="h-72 min-w-0 w-full">
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={booksByYear}>
                  <CartesianGrid strokeDasharray="3 3" stroke={chartGrid} />
                  <XAxis dataKey="year" stroke={chartAxis} fontSize={12} />
                  <YAxis stroke={chartAxis} fontSize={12} />
                  <Tooltip contentStyle={chartTooltipStyle} />
                  <Bar
                    dataKey="count"
                    radius={[8, 8, 0, 0]}
                    fill={chartBorrow}
                  />
                </BarChart>
              </ResponsiveContainer>
            </div>
          )}
        </Panel>

        <Panel
          title="Language Coverage"
          subtitle="Distribution of catalog language metadata"
        >
          {booksByLanguage.length === 0 ? (
            <EmptyState label="No language metadata available yet." />
          ) : (
            <div className="space-y-4">
              {booksByLanguage.map((item) => (
                <div key={item.language} className="space-y-1.5">
                  <div className="flex items-center justify-between text-xs">
                    <p className="font-bold text-[var(--mundia-ink)]">
                      {item.language}
                    </p>
                    <p className="font-bold text-[var(--mundia-ink)]">
                      {item.count}
                    </p>
                  </div>
                  <div className="h-2 rounded-full bg-[var(--mundia-line)]/20">
                    <div
                      className="h-2 rounded-full"
                      style={{
                        background: `linear-gradient(90deg, var(--mundia-teal-strong), var(--mundia-success))`,
                        width: `${Math.max(
                          8,
                          Math.round((item.count / maxLanguageCount) * 100),
                        )}%`,
                      }}
                    />
                  </div>
                </div>
              ))}
            </div>
          )}
        </Panel>
      </section>

      <section className="grid grid-cols-1 gap-5 xl:grid-cols-2">
        <Panel
          title="Genre Performance"
          subtitle="Top genres by number of titles and availability"
        >
          {categoryStats.length === 0 ? (
            <EmptyState label="No genre analytics available yet." />
          ) : (
            <div className="space-y-3">
              {categoryStats.map((item) => (
                <div
                  key={item.genre}
                  className="rounded-xl border border-[var(--mundia-line)] bg-white p-3 shadow-sm"
                >
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <p className="text-sm font-bold text-[var(--mundia-ink)]">
                      {item.genre}
                    </p>
                    <p className="text-xs font-bold text-[var(--mundia-teal-strong)]">
                      {item.count} titles
                    </p>
                  </div>
                  <div className="mt-2 h-1.5 rounded-full bg-[var(--mundia-line)]/20">
                    <div
                      className="h-1.5 rounded-full"
                      style={{
                        background: `linear-gradient(90deg, var(--mundia-teal-strong), var(--mundia-gold))`,
                        width: `${Math.max(
                          8,
                          Math.round((item.count / maxGenreCount) * 100),
                        )}%`,
                      }}
                    />
                  </div>
                  <div className="mt-2 flex items-center justify-between text-[10px] font-bold uppercase tracking-wider text-[var(--mundia-ink)]/50">
                    <span>
                      {item.availableCopies}/{item.totalCopies} available
                    </span>
                    <span>{item.avgRating.toFixed(1)} avg rating</span>
                  </div>
                </div>
              ))}
            </div>
          )}
        </Panel>

        <Panel title="Top Rated Books" subtitle="Highest-rated active books">
          {topRatedBooks.length === 0 ? (
            <EmptyState label="No top-rated books available yet." />
          ) : (
            <div className="space-y-3">
              {topRatedBooks.map((book) => (
                <div
                  key={book.id}
                  className="flex items-center justify-between rounded-xl border border-[var(--mundia-line)] bg-white px-3 py-3 shadow-sm"
                >
                  <div className="min-w-0 pr-3">
                    <p className="truncate text-sm font-bold text-[var(--mundia-ink)]">
                      {book.title}
                    </p>
                    <p className="truncate text-xs text-[var(--mundia-ink)]/60">
                      {book.author}
                    </p>
                  </div>
                  <div className="inline-flex items-center gap-1 rounded-full bg-[var(--mundia-gold)]/20 px-2.5 py-1 text-[11px] font-bold text-[var(--mundia-gold-strong)]">
                    {book.rating.toFixed(1)}
                  </div>
                </div>
              ))}
              <div className="rounded-xl border border-[var(--mundia-line)] bg-[var(--mundia-paper)] px-3 py-2 text-xs text-[var(--mundia-ink)]/60">
                Avg pages per book:{" "}
                <span className="font-bold text-[var(--mundia-ink)]">
                  {Math.round(averagePageCount)}
                </span>
              </div>
            </div>
          )}
        </Panel>
      </section>

      <section className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <div className="rounded-2xl border border-[var(--mundia-line)] bg-white p-5 shadow-sm">
          <div className="flex items-center gap-2 text-[var(--mundia-ink)]/60">
            <ArrowDownToLine className="size-4" />
            <p className="text-[10px] font-bold uppercase tracking-wider">
              Borrow Pressure
            </p>
          </div>
          <p className="mt-2 text-2xl font-bold text-[var(--mundia-ink)]">
            {activeBorrows + pendingBorrows}
          </p>
          <p className="mt-1 text-xs font-medium text-[var(--mundia-ink)]/55">
            Pending and active borrow flows requiring attention
          </p>
        </div>
        <div className="rounded-2xl border border-[var(--mundia-line)] bg-white p-5 shadow-sm">
          <div className="flex items-center gap-2 text-[var(--mundia-ink)]/60">
            <ArrowUpFromLine className="size-4" />
            <p className="text-[10px] font-bold uppercase tracking-wider">
              Return Velocity
            </p>
          </div>
          <p className="mt-2 text-2xl font-bold text-[var(--mundia-ink)]">
            {compact(returnedBooks)}
          </p>
          <p className="mt-1 text-xs font-medium text-[var(--mundia-ink)]/55">
            Total number of successfully returned books
          </p>
        </div>
        <div className="rounded-2xl border border-[var(--mundia-line)] bg-white p-5 shadow-sm">
          <div className="flex items-center gap-2 text-[var(--mundia-ink)]/60">
            <ChartColumnIncreasing className="size-4" />
            <p className="text-[10px] font-bold uppercase tracking-wider">
              Catalog Utilization
            </p>
          </div>
          <p className="mt-2 text-2xl font-bold text-[var(--mundia-ink)]">
            {utilizationRate}%
          </p>
          <p className="mt-1 text-xs font-medium text-[var(--mundia-ink)]/55">
            Percentage of total book copies currently in circulation
          </p>
        </div>
      </section>
    </div>
  );
};

export default AdminDashboardContent;
