"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import {
  DefaultValues,
  FieldValues,
  Path,
  SubmitHandler,
  useForm,
  UseFormReturn,
} from "react-hook-form";
import { ZodType } from "zod";
import { useState } from "react";
import { ArrowRight, ShieldCheck, Sparkles } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import Link from "next/link";
import { FIELD_NAMES, FIELD_TYPES, FIELD_PLACEHOLDERS } from "@/constants";
import FileUpload from "@/components/FileUpload";
import { showToast } from "@/lib/toast";
import { useRouter } from "next/navigation";

interface Props<T extends FieldValues> {
  schema: ZodType<T>;
  defaultValues: T;
  onSubmit: (
    data: T
  ) => Promise<
    { success: true } | { success: false; error?: string; fieldError?: string }
  >;
  type: "SIGN_IN" | "SIGN_UP";
}

const AuthForm = <T extends FieldValues>({
  type,
  schema,
  defaultValues,
  onSubmit,
}: Props<T>) => {
  const router = useRouter();

  const isSignIn = type === "SIGN_IN";
  const [selectedRole, setSelectedRole] = useState<string>("");

  const form: UseFormReturn<T> = useForm({
    resolver: zodResolver(schema),
    defaultValues: defaultValues as DefaultValues<T>,
  });

  // Test account credentials
  const testAccounts = {
    "guest-user": {
      email: "test@user.com",
      password: "12345678",
    },
    "guest-admin": {
      email: "test@admin.com",
      password: "12345678",
    },
  };

  const handleRoleSelect = (value: string) => {
    if (value === "clear") {
      setSelectedRole("");
      form.setValue("email" as Path<T>, "" as T[Path<T>]);
      form.setValue("password" as Path<T>, "" as T[Path<T>]);
    } else {
      setSelectedRole(value);
      const account = testAccounts[value as keyof typeof testAccounts];
      if (account) {
        form.setValue("email" as Path<T>, account.email as T[Path<T>]);
        form.setValue("password" as Path<T>, account.password as T[Path<T>]);
      }
    }
  };

  const handleSubmit: SubmitHandler<T> = async (data) => {
    const result = await onSubmit(data);

    if (result.success) {
      if (isSignIn) {
        showToast.auth.signInSuccess();
      } else {
        showToast.auth.signUpSuccess();
      }
      router.push("/");
    } else {
      // Handle field-specific errors
      if (result.error && result.fieldError) {
        const fieldName = result.error as Path<T>;
        const errorMessage = result.fieldError;

        // Set field-specific error
        form.setError(fieldName, {
          type: "server",
          message: errorMessage,
        });

        // Also show toast for visibility
        showToast.error("Validation Error", errorMessage);
      } else {
        // Generic error
        showToast.error(
          "Authentication Error",
          result.error ?? "An unexpected error occurred. Please try again."
        );
      }
    }
  };

  // Get form submission state for loading indicator
  const isSubmitting = form.formState.isSubmitting;
  const headingLabel = isSignIn ? "Member Access" : "Create Your Access";
  const headingTitle = isSignIn
    ? "Welcome back to Mundiapolis"
    : "Join the Mundiapolis library network";
  const headingSubtitle = isSignIn
    ? "Sign in to browse, request, and track your reading pipeline."
    : "Set up your student account to borrow, review, and manage books.";

  return (
    <div className="space-y-6 sm:space-y-7">
      <div className="auth-header-block">
        <p className="auth-badge">
          <Sparkles className="size-3.5" />
          {headingLabel}
        </p>
        <h1 className="auth-heading">{headingTitle}</h1>
        <p className="auth-subtitle">{headingSubtitle}</p>
      </div>

      <Form {...form}>
        <form
          onSubmit={form.handleSubmit(handleSubmit)}
          className="auth-form-stack"
        >
          {/* Role Based Test Account Selector - Only for Sign In */}
          {isSignIn && (
            <div className="auth-selector-panel space-y-1.5 sm:space-y-2">
              <FormLabel className="text-xs uppercase tracking-[0.16em] text-light-100/75 sm:text-sm">
                Select Test Account
              </FormLabel>
              <Select
                key={`select-${selectedRole || "empty"}`}
                value={selectedRole || undefined}
                onValueChange={handleRoleSelect}
              >
                <SelectTrigger className="form-input text-white">
                  <SelectValue placeholder="Select Role Based Test Account" />
                </SelectTrigger>
                <SelectContent className="rounded-2xl border border-white/15 bg-[rgba(7,14,22,0.98)] text-light-100">
                  <SelectItem
                    value="guest-user"
                    className="cursor-pointer rounded-lg text-white focus:bg-white/10 focus:text-white"
                  >
                    Guest User
                  </SelectItem>
                  <SelectItem
                    value="guest-admin"
                    className="cursor-pointer rounded-lg text-white focus:bg-white/10 focus:text-white"
                  >
                    Guest Admin
                  </SelectItem>
                  {selectedRole && (
                    <SelectItem
                      value="clear"
                      className="cursor-pointer rounded-lg text-gray-400 opacity-70 focus:bg-white/10 focus:text-gray-300"
                    >
                      Clear Selection
                    </SelectItem>
                  )}
                </SelectContent>
              </Select>
            </div>
          )}

          {Object.keys(defaultValues).map((field) => (
            <FormField
              key={field}
              control={form.control}
              name={field as Path<T>}
              render={({ field }) => (
                <FormItem className="auth-field-row">
                  <FormLabel className="text-xs capitalize uppercase tracking-[0.15em] text-light-100/75 sm:text-sm">
                    {FIELD_NAMES[field.name as keyof typeof FIELD_NAMES]}
                  </FormLabel>
                  <FormControl>
                    {field.name === "universityCard" ? (
                      <FileUpload
                        type="image"
                        accept="image/*"
                        placeholder={
                          FIELD_PLACEHOLDERS[
                            field.name as keyof typeof FIELD_PLACEHOLDERS
                          ] || "Upload your ID"
                        }
                        folder="ids"
                        variant="dark"
                        onFileChange={field.onChange}
                      />
                    ) : (
                      <Input
                        required
                        type={
                          FIELD_TYPES[field.name as keyof typeof FIELD_TYPES]
                        }
                        placeholder={
                          FIELD_PLACEHOLDERS[
                            field.name as keyof typeof FIELD_PLACEHOLDERS
                          ] || ""
                        }
                        {...field}
                        value={
                          field.value === undefined ||
                          field.value === null ||
                          field.value === 0
                            ? ""
                            : field.value
                        }
                        onChange={(e) => {
                          const value = e.target.value;
                          // For number inputs, convert empty string to undefined
                          if (field.name === "universityId") {
                            field.onChange(
                              value === "" ? undefined : Number(value)
                            );
                          } else {
                            field.onChange(value);
                          }
                        }}
                        className="form-input"
                      />
                    )}
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />
          ))}

          <Button
            type="submit"
            className="form-btn auth-submit-btn"
            disabled={isSubmitting}
          >
            <span>
              {isSubmitting
                ? isSignIn
                  ? "Signing in..."
                  : "Signing up..."
                : isSignIn
                  ? "Sign In"
                  : "Create Account"}
            </span>
            {!isSubmitting && <ArrowRight className="size-4" />}
          </Button>
        </form>
      </Form>

      <div className="auth-divider" />

      <div className="space-y-2">
        <p className="text-center text-sm font-medium sm:text-base">
          {isSignIn ? "New to Mundiapolis? " : "Already have an account? "}

          <Link
            href={isSignIn ? "/sign-up" : "/sign-in"}
            className="auth-link"
          >
            {isSignIn ? "Create an account" : "Sign in"}
          </Link>
        </p>
        <p className="auth-trust-note">
          <ShieldCheck className="size-3.5" />
          Encrypted sessions with secure identity checks.
        </p>
      </div>
    </div>
  );
};
export default AuthForm;
