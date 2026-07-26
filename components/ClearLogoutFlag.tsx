"use client";

import { useEffect } from "react";

/**
 * Remove the short-lived client logout marker after returning to sign-in.
 */
const ClearLogoutFlag = () => {
  useEffect(() => {
    document.cookie = "logout-in-progress=; path=/; max-age=0; SameSite=Lax";
  }, []);

  return null;
};

export default ClearLogoutFlag;
