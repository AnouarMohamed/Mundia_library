import { auth } from "@/auth";
import { cache } from "react";

/**
 * Deduplicate session reads across nested Server Components.
 */
export const getSession = cache(() => auth());
