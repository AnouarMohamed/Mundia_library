"use client";

import React from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import {
  exportData,
  type ExportStats,
  type FineConfig,
  type ReminderStats,
} from "@/lib/services/admin";
import { showToast } from "@/lib/toast";
import {
  useExportStats,
  useFineConfig,
  useReminderStats,
} from "@/hooks/useQueries";
import {
  useGenerateAllUserRecommendations,
  useRefreshRecommendationCache,
  useSendDueReminders,
  useSendOverdueReminders,
  useUpdateFineConfig,
  useUpdateOverdueFines,
  useUpdateTrendingBooks,
} from "@/hooks/useMutations";
import {
  Download,
  Mail,
  RefreshCw,
  Settings,
  Sparkles,
  TrendingUp,
} from "lucide-react";

interface AdminAutomationClientProps {
  initialFineConfig?: FineConfig;
  initialReminderStats?: ReminderStats;
  initialExportStats?: ExportStats;
}

type ExportKind = "books" | "users" | "borrows" | "analytics";

const AdminAutomationClient: React.FC<AdminAutomationClientProps> = ({
  initialFineConfig,
  initialReminderStats,
  initialExportStats,
}) => {
  const fineConfigQuery = useFineConfig(initialFineConfig);
  const reminderStatsQuery = useReminderStats(initialReminderStats);
  const exportStatsQuery = useExportStats(initialExportStats);

  const updateFineConfigMutation = useUpdateFineConfig();
  const sendDueRemindersMutation = useSendDueReminders();
  const sendOverdueRemindersMutation = useSendOverdueReminders();
  const updateOverdueFinesMutation = useUpdateOverdueFines();
  const generateRecommendationsMutation = useGenerateAllUserRecommendations();
  const updateTrendingMutation = useUpdateTrendingBooks();
  const refreshCacheMutation = useRefreshRecommendationCache();

  const [fineAmountInput, setFineAmountInput] = React.useState<string>(
    (initialFineConfig?.fineAmount ?? 1).toFixed(2)
  );
  const [exportingType, setExportingType] = React.useState<ExportKind | null>(
    null
  );

  React.useEffect(() => {
    if (fineConfigQuery.data?.fineAmount !== undefined) {
      setFineAmountInput(fineConfigQuery.data.fineAmount.toFixed(2));
    }
  }, [fineConfigQuery.data?.fineAmount]);

  const reminderStats = reminderStatsQuery.data ?? {
    dueSoon: 0,
    overdue: 0,
    remindersSentToday: 0,
  };

  const exportStats = exportStatsQuery.data ?? {
    totalBooks: 0,
    totalUsers: 0,
    totalBorrows: 0,
  };

  const handleFineConfigSubmit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();

    const parsedFineAmount = Number(fineAmountInput);
    if (!Number.isFinite(parsedFineAmount) || parsedFineAmount < 0) {
      showToast.error(
        "Invalid Fine Amount",
        "Please enter a valid number greater than or equal to 0."
      );
      return;
    }

    updateFineConfigMutation.mutate({ fineAmount: parsedFineAmount });
  };

  const handleExport = async (type: ExportKind) => {
    try {
      setExportingType(type);
      const file = await exportData(type, "csv");
      const url = URL.createObjectURL(file);
      const anchor = document.createElement("a");
      const datePart = new Date().toISOString().split("T")[0];

      anchor.href = url;
      anchor.download = `${type}_export_${datePart}.csv`;
      document.body.appendChild(anchor);
      anchor.click();
      document.body.removeChild(anchor);
      URL.revokeObjectURL(url);

      showToast.success(
        "Export Completed",
        `${type.charAt(0).toUpperCase() + type.slice(1)} export downloaded.`
      );
    } catch (error) {
      const message =
        error instanceof Error ? error.message : "Failed to export data.";
      showToast.error("Export Failed", message);
    } finally {
      setExportingType(null);
    }
  };

  const remindersActionPending =
    sendDueRemindersMutation.isPending ||
    sendOverdueRemindersMutation.isPending ||
    updateOverdueFinesMutation.isPending;

  const recommendationActionPending =
    generateRecommendationsMutation.isPending ||
    updateTrendingMutation.isPending ||
    refreshCacheMutation.isPending;

  return (
    <section className="space-y-5 sm:space-y-6">
      <div className="rounded-xl border border-slate-200 bg-white p-4 sm:p-6">
        <h1 className="text-xl font-semibold text-slate-800 sm:text-2xl">
          Automation
        </h1>
        <p className="mt-1 text-sm text-slate-600">
          Manage reminders, fines, recommendations, and exports.
        </p>
      </div>

      <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
        <Card className="border-slate-200">
          <CardHeader className="pb-2">
            <CardTitle className="text-sm font-medium text-slate-600">
              Due Soon
            </CardTitle>
          </CardHeader>
          <CardContent>
            <p className="text-2xl font-semibold text-slate-800">
              {reminderStats.dueSoon}
            </p>
          </CardContent>
        </Card>

        <Card className="border-slate-200">
          <CardHeader className="pb-2">
            <CardTitle className="text-sm font-medium text-slate-600">
              Overdue
            </CardTitle>
          </CardHeader>
          <CardContent>
            <p className="text-2xl font-semibold text-slate-800">
              {reminderStats.overdue}
            </p>
          </CardContent>
        </Card>

        <Card className="border-slate-200">
          <CardHeader className="pb-2">
            <CardTitle className="text-sm font-medium text-slate-600">
              Reminders Sent Today
            </CardTitle>
          </CardHeader>
          <CardContent>
            <p className="text-2xl font-semibold text-slate-800">
              {reminderStats.remindersSentToday}
            </p>
          </CardContent>
        </Card>
      </div>

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
        <Card className="border-slate-200">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-lg text-slate-800">
              <Settings className="size-4" />
              Fine Settings
            </CardTitle>
          </CardHeader>
          <CardContent>
            <form
              className="flex flex-col gap-3 sm:flex-row sm:items-end"
              onSubmit={handleFineConfigSubmit}
            >
              <div className="w-full space-y-1">
                <p className="text-xs font-medium uppercase tracking-wide text-slate-500">
                  Daily Fine Amount (USD)
                </p>
                <Input
                  type="number"
                  min={0}
                  step="0.01"
                  value={fineAmountInput}
                  onChange={(event) => setFineAmountInput(event.target.value)}
                  className="w-full"
                />
              </div>

              <Button
                type="submit"
                disabled={updateFineConfigMutation.isPending}
                className="sm:min-w-40"
              >
                {updateFineConfigMutation.isPending ? "Saving..." : "Update"}
              </Button>
            </form>
          </CardContent>
        </Card>

        <Card className="border-slate-200">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-lg text-slate-800">
              <Mail className="size-4" />
              Reminder Actions
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-2">
            <Button
              variant="outline"
              className="w-full justify-start"
              disabled={remindersActionPending}
              onClick={() => sendDueRemindersMutation.mutate()}
            >
              <Mail className="size-4" />
              Send Due Soon Reminders
            </Button>

            <Button
              variant="outline"
              className="w-full justify-start"
              disabled={remindersActionPending}
              onClick={() => sendOverdueRemindersMutation.mutate()}
            >
              <Mail className="size-4" />
              Send Overdue Reminders
            </Button>

            <Button
              variant="outline"
              className="w-full justify-start"
              disabled={remindersActionPending}
              onClick={() => updateOverdueFinesMutation.mutate({})}
            >
              <RefreshCw className="size-4" />
              Update Overdue Fines
            </Button>
          </CardContent>
        </Card>

        <Card className="border-slate-200">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-lg text-slate-800">
              <Sparkles className="size-4" />
              Recommendation Actions
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-2">
            <Button
              variant="outline"
              className="w-full justify-start"
              disabled={recommendationActionPending}
              onClick={() => generateRecommendationsMutation.mutate()}
            >
              <Sparkles className="size-4" />
              Generate Recommendations
            </Button>

            <Button
              variant="outline"
              className="w-full justify-start"
              disabled={recommendationActionPending}
              onClick={() => updateTrendingMutation.mutate()}
            >
              <TrendingUp className="size-4" />
              Update Trending Books
            </Button>

            <Button
              variant="outline"
              className="w-full justify-start"
              disabled={recommendationActionPending}
              onClick={() => refreshCacheMutation.mutate()}
            >
              <RefreshCw className="size-4" />
              Refresh Recommendation Cache
            </Button>
          </CardContent>
        </Card>

        <Card className="border-slate-200">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-lg text-slate-800">
              <Download className="size-4" />
              Data Export
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            <div className="grid grid-cols-3 gap-2 text-center text-xs text-slate-600">
              <div className="rounded-md bg-slate-100 p-2">
                <p className="text-lg font-semibold text-slate-800">
                  {exportStats.totalBooks}
                </p>
                <p>Books</p>
              </div>
              <div className="rounded-md bg-slate-100 p-2">
                <p className="text-lg font-semibold text-slate-800">
                  {exportStats.totalUsers}
                </p>
                <p>Users</p>
              </div>
              <div className="rounded-md bg-slate-100 p-2">
                <p className="text-lg font-semibold text-slate-800">
                  {exportStats.totalBorrows}
                </p>
                <p>Borrows</p>
              </div>
            </div>

            <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
              <Button
                variant="outline"
                disabled={exportingType !== null}
                onClick={() => handleExport("books")}
              >
                {exportingType === "books" ? "Exporting..." : "Export Books"}
              </Button>
              <Button
                variant="outline"
                disabled={exportingType !== null}
                onClick={() => handleExport("users")}
              >
                {exportingType === "users" ? "Exporting..." : "Export Users"}
              </Button>
              <Button
                variant="outline"
                disabled={exportingType !== null}
                onClick={() => handleExport("borrows")}
              >
                {exportingType === "borrows"
                  ? "Exporting..."
                  : "Export Borrows"}
              </Button>
              <Button
                variant="outline"
                disabled={exportingType !== null}
                onClick={() => handleExport("analytics")}
              >
                {exportingType === "analytics"
                  ? "Exporting..."
                  : "Export Analytics"}
              </Button>
            </div>
          </CardContent>
        </Card>
      </div>
    </section>
  );
};

export default AdminAutomationClient;
