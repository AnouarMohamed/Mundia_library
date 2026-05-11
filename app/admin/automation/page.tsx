import AdminAutomationClient from "@/components/AdminAutomationClient";
import { getDailyFineAmount } from "@/lib/admin/actions/config";
import { getExportStats } from "@/lib/admin/actions/data-export";
import { getReminderStats } from "@/lib/admin/actions/reminders";
import type {
  ExportStats,
  FineConfig,
  ReminderStats,
} from "@/lib/services/admin";

/**
 * Use Node.js runtime for server-side data fetching.
 */
export const runtime = "nodejs";

/**
 * Admin automation dashboard page with initial metrics.
 */
const AutomationPage = async () => {
  const [fineResult, reminderResult, exportResult] = await Promise.allSettled([
    getDailyFineAmount(),
    getReminderStats(),
    getExportStats(),
  ]);

  const initialFineConfig: FineConfig | undefined =
    fineResult.status === "fulfilled"
      ? { success: true, fineAmount: fineResult.value }
      : undefined;

  const initialReminderStats: ReminderStats | undefined =
    reminderResult.status === "fulfilled" ? reminderResult.value : undefined;

  const initialExportStats: ExportStats | undefined =
    exportResult.status === "fulfilled" ? exportResult.value : undefined;

  return (
    <AdminAutomationClient
      initialFineConfig={initialFineConfig}
      initialReminderStats={initialReminderStats}
      initialExportStats={initialExportStats}
    />
  );
};

export default AutomationPage;
