/**
 * AuthForm Component
 * 
 * A generic, highly reusable authentication form component built with React Hook Form and Zod.
 * It handles both Sign In and Sign Up flows by dynamically rendering fields based on the 
 * provided Zod schema and default values.
 * 
 * Features:
 * - Type-safe form handling using Generics.
 * - Integrated validation via @hookform/resolvers/zod.
 * - Dynamic field rendering based on schema keys.
 * - Specialized handling for file uploads (university card).
 * - Automatic toast notifications for success/error states.
 * - Tailwind-based styling for consistent UI.
 * 
 * @template T - The shape of the form data, extending FieldValues.
 */

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
import { ArrowRight } from "lucide-react";

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
import Link from "next/link";
import { FIELD_NAMES, FIELD_TYPES, FIELD_PLACEHOLDERS } from "@/constants";
import FileUpload from "@/components/FileUpload";
import { showToast } from "@/lib/toast";
import { useRouter } from "next/navigation";

/**
 * Props for the AuthForm component.
 */
interface Props<T extends FieldValues> {
  /** The Zod validation schema for the form. */
  schema: ZodType<T>;
  /** Initial values for the form fields. */
  defaultValues: T;
  /** 
   * Async submission handler.
   * @param data - The validated form data.
   * @returns A promise resolving to a success flag and optional error messages.
   */
  onSubmit: (
    data: T,
  ) => Promise<
    { success: true } | { success: false; error?: string; fieldError?: string }
  >;
  /** Determines the UI text and redirect logic (Sign In vs Sign Up). */
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

  /**
   * Initialize the form with Zod resolver.
   */
  const form: UseFormReturn<T> = useForm({
    resolver: zodResolver(schema),
    defaultValues: defaultValues as DefaultValues<T>,
  });

  /**
   * Centralized submit handler.
   * Handles server response, error mapping to form fields, and navigation.
   */
  const handleSubmit: SubmitHandler<T> = async (data) => {
    const result = await onSubmit(data);
    
    if (result.success) {
      if (isSignIn) showToast.auth.signInSuccess();
      else showToast.auth.signUpSuccess();
      router.push(isSignIn ? "/" : "/sign-in");
    } else {
      // Map server-side validation errors back to the form if applicable
      if (result.error && result.fieldError) {
        form.setError(result.error as Path<T>, {
          type: "server",
          message: result.fieldError,
        });
        showToast.error("Validation Error", result.fieldError);
      } else {
        showToast.error(
          "Authentication Error",
          result.error ?? "An unexpected error occurred.",
        );
      }
    }
  };

  const isSubmitting = form.formState.isSubmitting;

  return (
    <div className="space-y-6">
      {/* Header Section */}
      <div>
        <h1 className="text-2xl font-semibold text-[var(--mundia-ink)]">
          {isSignIn ? "Sign in" : "Create account"}
        </h1>
        <p className="mt-1 text-sm text-[var(--mundia-muted)]">
          {isSignIn
            ? "Access your library account"
            : "Register for a library account"}
        </p>
      </div>

      {/* Main Form Section */}
      <Form {...form}>
        <form onSubmit={form.handleSubmit(handleSubmit)} className="space-y-4">
          {/* Dynamic Field Mapping */}
          {Object.keys(defaultValues).map((field) => (
            <FormField
              key={field}
              control={form.control}
              name={field as Path<T>}
              render={({ field }) => (
                <FormItem>
                  <FormLabel className="text-sm font-medium text-[var(--mundia-ink)]">
                    {FIELD_NAMES[field.name as keyof typeof FIELD_NAMES]}
                  </FormLabel>
                  <FormControl>
                    {/* Specialized handler for ID card upload */}
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
                          // Coerce University ID to number as expected by schema
                          if (field.name === "universityId") {
                            field.onChange(
                              value === "" ? undefined : Number(value),
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

          {/* Submit Button */}
          <Button
            type="submit"
            className="!mt-6 flex w-full items-center justify-center gap-2 rounded-lg bg-[var(--mundia-navy)] px-6 py-3 text-base font-semibold text-white transition-colors hover:bg-[var(--mundia-navy-strong)]"
            disabled={isSubmitting}
          >
            {isSubmitting
              ? isSignIn
                ? "Signing in..."
                : "Signing up..."
              : isSignIn
                ? "Sign In"
                : "Create Account"}
            {!isSubmitting && <ArrowRight className="size-4" />}
          </Button>
        </form>
      </Form>

      {/* Footer / Redirection Section */}
      <p className="text-center text-sm text-[var(--mundia-muted)]">
        {isSignIn ? "New student? " : "Already have an account? "}
        <Link
          href={isSignIn ? "/sign-up" : "/sign-in"}
          className="font-semibold text-[var(--mundia-navy)] hover:underline"
        >
          {isSignIn ? "Create an account" : "Sign in"}
        </Link>
      </p>
    </div>
  );
};
export default AuthForm;
