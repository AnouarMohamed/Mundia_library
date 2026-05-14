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
  Sparkles,
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
  "oklch(56% 0.09 214)",
  "oklch(63% 0.11 150)",
  "oklch(78% 0.105 88)",
];
const chartGrid = "oklch(76% 0.035 218 / 0.7)";
const chartAxis = "oklch(46% 0.035 225)";
const chartBorrow = "oklch(56% 0.09 214)";
const chartReturn = "oklch(63% 0.11 150)";
const chartGold = "oklch(78% 0.105 88)";
const chartTooltipStyle = {
  borderRadius: "0.75rem",
  border: "1px solid var(--mundia-line)",
  background: "var(--mundia-paper)",
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
    <div className="mb-4">
      <h3 className="text-lg font-semibold tracking-tight text-slate-900 sm:text-xl">
        {title}
      </h3>
      {subtitle && <p className="mt-1 text-sm text-slate-600">{subtitle}</p>}
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
    blue: { badge: "bg-blue-100 text-blue-700", ring: "ring-blue-200/70" },
    teal: { badge: "bg-teal-100 text-teal-700", ring: "ring-teal-200/70" },
    amber: { badge: "bg-amber-100 text-amber-700", ring: "ring-amber-200/70" },
    slate: { badge: "bg-slate-200 text-slate-700", ring: "ring-slate-300/70" },
  };

  const styles = toneStyles[tone];

  return (
    <article
      className={`rounded-2xl border border-slate-200/80 bg-white p-4 shadow-sm ring-1 ${styles.ring} sm:p-5`}
    >
      <div className="flex items-start justify-between gap-3">
        <div>
          <p className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-600">
            {title}
          </p>
          <p className="mt-2 text-2xl font-semibold tracking-tight text-slate-900 sm:text-3xl">
            {value}
          </p>
        </div>
        <span
          className={`inline-flex size-9 items-center justify-center rounded-xl ${styles.badge}`}
        >
          <Icon className="size-4.5" />
        </span>
      </div>
      <p className="mt-3 text-sm text-slate-600">{subtitle}</p>
    </article>
  );
};

const EmptyState = ({ label }: { label: string }) => (
  <div className="flex h-60 items-center justify-center rounded-2xl border border-dashed border-slate-300 bg-slate-50/90 px-4 text-center text-sm text-slate-600">
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

      <section
        className="relative overflow-hidden rounded-[2rem] border border-white/10 p-6 text-slate-100 sm:p-8"
        style={{ boxShadow: "0 24px 70px oklch(10% 0.02 225 / 0.5)" }}
      >
        <div
          className="absolute inset-0"
          style={{
            background:
              "linear-gradient(115deg, oklch(78% 0.105 88 / 0.10) 0 1px, transparent 1px 118px), linear-gradient(135deg, oklch(18% 0.025 225) 0%, oklch(24% 0.04 218) 48%, oklch(31% 0.055 218) 100%)",
          }}
        />
        <div className="pointer-events-none absolute inset-x-0 top-0 h-px bg-gradient-to-r from-transparent via-[var(--mundia-gold)]/70 to-transparent" />

        <div className="relative z-10 space-y-5">
          <div className="inline-flex items-center gap-2 rounded-full border border-white/10 bg-white/10 px-3 py-1 text-[11px] uppercase tracking-[0.22em] text-light-100/80">
            <Sparkles className="size-3.5" />
            Dashboard Overview
          </div>

          <div className="space-y-2">
            <h2 className="font-bebas-neue text-4xl tracking-[0.08em] text-light-100 sm:text-5xl">
              Library Intelligence Dashboard
            </h2>
            <p className="max-w-3xl text-sm text-slate-300 sm:text-base">
              Live operational insight for circulation, catalog quality, and
              user growth in one view.
            </p>
          </div>

          <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
            <div className="rounded-xl border border-white/10 bg-white/10 px-4 py-3">
              <p className="text-[11px] uppercase tracking-[0.18em] text-slate-400">
                Borrow Utilization
              </p>
              <p className="mt-1 text-2xl font-semibold text-slate-100">
                {utilizationRate}%
              </p>
            </div>
            <div className="rounded-xl border border-white/10 bg-white/10 px-4 py-3">
              <p className="text-[11px] uppercase tracking-[0.18em] text-slate-400">
                User Approval
              </p>
              <p className="mt-1 text-2xl font-semibold text-slate-100">
                {approvalRate}%
              </p>
            </div>
            <div className="rounded-xl border border-white/10 bg-white/10 px-4 py-3">
              <p className="text-[11px] uppercase tracking-[0.18em] text-slate-400">
                Admin Coverage
              </p>
              <p className="mt-1 text-2xl font-semibold text-slate-100">
                {toPercent(adminUsers, Math.max(1, totalUsers))}%
              </p>
            </div>
          </div>
        </div>
      </section>

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
                className="rounded-xl border border-slate-200 bg-slate-50 px-3 py-2"
              >
                <div className="flex items-center gap-2">
                  <span
                    className="inline-block size-2.5 rounded-full"
                    style={{ backgroundColor: item.color }}
                  />
                  <p className="text-xs uppercase tracking-[0.14em] text-slate-500">
                    {item.name}
                  </p>
                </div>
                <p className="mt-1 text-lg font-semibold text-slate-900">
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
            <div className="space-y-3">
              {booksByLanguage.map((item) => (
                <div key={item.language} className="space-y-1.5">
                  <div className="flex items-center justify-between text-sm">
                    <p className="font-medium text-slate-700">
                      {item.language}
                    </p>
                    <p className="font-semibold text-slate-900">{item.count}</p>
                  </div>
                  <div className="h-2 rounded-full bg-slate-100">
                    <div
                      className="h-2 rounded-full"
                      style={{
                        background: `linear-gradient(90deg, ${chartBorrow}, ${chartReturn})`,
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
          subtitle="Top genres by number of titles"
        >
          {categoryStats.length === 0 ? (
            <EmptyState label="No genre analytics available yet." />
          ) : (
            <div className="space-y-3">
              {categoryStats.map((item) => (
                <div
                  key={item.genre}
                  className="rounded-xl border border-slate-200 bg-slate-50/80 p-3"
                >
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <p className="text-sm font-semibold text-slate-800">
                      {item.genre}
                    </p>
                    <p className="text-sm font-medium text-slate-600">
                      {item.count} titles
                    </p>
                  </div>
                  <div className="mt-2 h-2 rounded-full bg-slate-200">
                    <div
                      className="h-2 rounded-full"
                      style={{
                        background: `linear-gradient(90deg, ${chartBorrow}, ${chartGold})`,
                        width: `${Math.max(
                          8,
                          Math.round((item.count / maxGenreCount) * 100),
                        )}%`,
                      }}
                    />
                  </div>
                  <div className="mt-2 flex items-center justify-between text-xs text-slate-500">
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
                  className="flex items-center justify-between rounded-xl border border-slate-200 bg-white px-3 py-3"
                >
                  <div className="min-w-0 pr-3">
                    <p className="truncate text-sm font-semibold text-slate-800">
                      {book.title}
                    </p>
                    <p className="truncate text-xs text-slate-500">
                      {book.author}
                    </p>
                  </div>
                  <div className="inline-flex items-center gap-1 rounded-full bg-amber-100 px-2.5 py-1 text-xs font-semibold text-amber-700">
                    <Sparkles className="size-3.5" />
                    {book.rating.toFixed(1)}
                  </div>
                </div>
              ))}
              <div className="rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 text-xs text-slate-500">
                Avg pages per book:{" "}
                <span className="font-semibold text-slate-700">
                  {Math.round(averagePageCount)}
                </span>
              </div>
            </div>
          )}
        </Panel>
      </section>

      <section className="grid grid-cols-1 gap-5 xl:grid-cols-2">
        <Panel
          title="Recent Borrow Activity"
          subtitle="Latest circulation updates"
        >
          {recentBorrows.length === 0 ? (
            <EmptyState label="No borrow records found yet." />
          ) : (
            <div className="space-y-2.5">
              {recentBorrows.map((item) => (
                <div
                  key={item.id}
                  className="flex flex-col gap-2 rounded-xl border border-slate-200 bg-white px-3 py-3 sm:flex-row sm:items-center sm:justify-between"
                >
                  <div className="min-w-0">
                    <p className="truncate text-sm font-semibold text-slate-800">
                      {item.bookTitle}
                    </p>
                    <p className="truncate text-xs text-slate-500">
                      {item.userName}
                    </p>
                  </div>
                  <div className="flex items-center gap-2">
                    <span
                      className={`inline-flex w-fit items-center rounded-full border px-2.5 py-1 text-xs font-semibold ${getStatusClasses(item.status)}`}
                    >
                      {item.status}
                    </span>
                    {item.status === "PENDING" && (
                      <>
                        <button
                          type="button"
                          className="inline-flex min-h-9 items-center rounded-full bg-[var(--mundia-success)] px-3 py-1 text-xs font-semibold text-white transition hover:bg-[var(--mundia-success-strong)] disabled:cursor-not-allowed disabled:opacity-60"
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
                          className="text-xs font-semibold text-slate-500 underline underline-offset-2 hover:text-slate-700"
                        >
                          Manage
                        </Link>
                      </>
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
                  className="flex flex-col gap-2 rounded-xl border border-slate-200 bg-white px-3 py-3 sm:flex-row sm:items-center sm:justify-between"
                >
                  <div className="min-w-0">
                    <p className="truncate text-sm font-semibold text-slate-800">
                      {item.fullName}
                    </p>
                    <p className="truncate text-xs text-slate-500">
                      {item.email}
                    </p>
                  </div>
                  <span
                    className={`inline-flex w-fit items-center rounded-full border px-2.5 py-1 text-xs font-semibold ${getStatusClasses(item.status)}`}
                  >
                    {item.status}
                  </span>
                </div>
              ))}
            </div>
          )}
        </Panel>
      </section>

      <section className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <div className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm">
          <div className="flex items-center gap-2 text-slate-600">
            <ArrowDownToLine className="size-4" />
            <p className="text-xs uppercase tracking-[0.14em]">
              Borrow Pressure
            </p>
          </div>
          <p className="mt-2 text-2xl font-semibold text-slate-900">
            {activeBorrows + pendingBorrows}
          </p>
          <p className="mt-1 text-sm text-slate-500">open borrow flows</p>
        </div>
        <div className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm">
          <div className="flex items-center gap-2 text-slate-600">
            <ArrowUpFromLine className="size-4" />
            <p className="text-xs uppercase tracking-[0.14em]">
              Return Velocity
            </p>
          </div>
          <p className="mt-2 text-2xl font-semibold text-slate-900">
            {compact(returnedBooks)}
          </p>
          <p className="mt-1 text-sm text-slate-500">returned records</p>
        </div>
        <div className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm">
          <div className="flex items-center gap-2 text-slate-600">
            <ChartColumnIncreasing className="size-4" />
            <p className="text-xs uppercase tracking-[0.14em]">
              Catalog Utilization
            </p>
          </div>
          <p className="mt-2 text-2xl font-semibold text-slate-900">
            {utilizationRate}%
          </p>
          <p className="mt-1 text-sm text-slate-500">
            of copies currently in circulation
          </p>
        </div>
      </section>
    </div>
  );
};

export default AdminDashboardContent;
