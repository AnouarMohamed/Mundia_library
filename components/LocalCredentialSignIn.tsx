"use client";

import AuthForm from "@/components/AuthForm";
import { signInWithCredentials } from "@/lib/actions/auth";
import { signInSchema } from "@/lib/validations";

/**
 * Keep non-serializable Zod schema state inside the client boundary.
 */
const LocalCredentialSignIn = ({
  institutionalOidcEnabled,
}: {
  institutionalOidcEnabled: boolean;
}) => (
  <AuthForm
    type="SIGN_IN"
    schema={signInSchema}
    defaultValues={{
      email: "",
      password: "",
    }}
    onSubmit={signInWithCredentials}
    showHeader={false}
    showAlternateLink={false}
    submitLabel={institutionalOidcEnabled ? "Local sign in" : "Sign In"}
  />
);

export default LocalCredentialSignIn;
