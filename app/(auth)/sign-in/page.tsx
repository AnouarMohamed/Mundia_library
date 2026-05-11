"use client";

import React, { useEffect } from "react";
import AuthForm from "@/components/AuthForm";
import { signInSchema } from "@/lib/validations";
import { signInWithCredentials } from "@/lib/actions/auth";

/**
 * Sign-in page using the shared AuthForm.
 */
const Page = () => {
  useEffect(() => {
    // Clear the logout flag to avoid stale state after returning.
    document.cookie = "logout-in-progress=; path=/; max-age=0; SameSite=Lax";
  }, []);

  return (
    <AuthForm
      type="SIGN_IN"
      schema={signInSchema}
      defaultValues={{
        email: "",
        password: "",
      }}
      onSubmit={signInWithCredentials}
    />
  );
};

export default Page;
