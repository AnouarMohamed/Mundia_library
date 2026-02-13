import { auth } from "@/auth";
import { cache } from "react";

/**
 * Deduplicate session reads across nested Server Components in a single request.
 */
export const getSession = cache(() => auth());
