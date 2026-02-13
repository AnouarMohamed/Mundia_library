import { NextRequest, NextResponse } from "next/server";
import {
  exportAnalytics,
  exportBooks,
  exportBorrows,
  exportUsers,
  type ExportFormat,
} from "@/lib/admin/actions/data-export";
import { requireAdminRouteAccess } from "@/lib/admin/route-guard";

export const runtime = "nodejs";

type ExportType = "books" | "users" | "borrows" | "analytics" | "borrows-range";

const validExportTypes: ExportType[] = [
  "books",
  "users",
  "borrows",
  "analytics",
  "borrows-range",
];

const parseExportFormat = (value: FormDataEntryValue | null): ExportFormat => {
  if (value === "json") return "json";
  return "csv";
};

export async function POST(
  request: NextRequest,
  context: { params: Promise<{ type: string }> }
) {
  try {
    const guard = await requireAdminRouteAccess();
    if (!guard.ok) {
      return guard.response;
    }

    const { type } = await context.params;
    if (!validExportTypes.includes(type as ExportType)) {
      return NextResponse.json(
        {
          success: false,
          error: "Invalid export type",
          message: `Unsupported export type: ${type}.`,
        },
        { status: 400 }
      );
    }

    const formData = await request.formData();
    const format = parseExportFormat(formData.get("format"));
    let exportResult:
      | Awaited<ReturnType<typeof exportBooks>>
      | Awaited<ReturnType<typeof exportUsers>>
      | Awaited<ReturnType<typeof exportBorrows>>
      | Awaited<ReturnType<typeof exportAnalytics>>;

    if (type === "books") {
      exportResult = await exportBooks(format);
    } else if (type === "users") {
      exportResult = await exportUsers(format);
    } else if (type === "borrows") {
      exportResult = await exportBorrows(format);
    } else if (type === "analytics") {
      exportResult = await exportAnalytics(format);
    } else {
      const dateFrom = formData.get("dateFrom");
      const dateTo = formData.get("dateTo");

      if (typeof dateFrom !== "string" || typeof dateTo !== "string") {
        return NextResponse.json(
          {
            success: false,
            error: "Missing date range",
            message:
              "dateFrom and dateTo are required for borrows-range export.",
          },
          { status: 400 }
        );
      }

      const start = new Date(dateFrom);
      const end = new Date(dateTo);

      if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime())) {
        return NextResponse.json(
          {
            success: false,
            error: "Invalid date range",
            message: "dateFrom/dateTo must be valid date values.",
          },
          { status: 400 }
        );
      }

      // exportBorrows uses < end boundary. Add one day to make dateTo inclusive.
      const endExclusive = new Date(end);
      endExclusive.setDate(endExclusive.getDate() + 1);

      exportResult = await exportBorrows(format, {
        start,
        end: endExclusive,
      });
    }

    return new NextResponse(exportResult.data, {
      status: 200,
      headers: {
        "Content-Type": exportResult.contentType,
        "Content-Disposition": `attachment; filename="${exportResult.filename}"`,
        "Cache-Control": "no-store",
      },
    });
  } catch (error) {
    console.error("Error exporting admin data:", error);
    return NextResponse.json(
      {
        success: false,
        error: "Failed to export data",
        message:
          error instanceof Error ? error.message : "Unknown error occurred",
      },
      { status: 500 }
    );
  }
}
