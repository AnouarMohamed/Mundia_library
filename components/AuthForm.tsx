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

interface Props<T extends FieldValues> {
  schema: ZodType<T>;
  defaultValues: T;
  onSubmit: (
    data: T,
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

  const form: UseFormReturn<T> = useForm({
    resolver: zodResolver(schema),
    defaultValues: defaultValues as DefaultValues<T>,
  });

  const handleSubmit: SubmitHandler<T> = async (data) => {
    const result = await onSubmit(data);
    if (result.success) {
      if (isSignIn) showToast.auth.signInSuccess();
      else showToast.auth.signUpSuccess();
      router.push("/");
    } else {
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

      <Form {...form}>
        <form onSubmit={form.handleSubmit(handleSubmit)} className="space-y-4">
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
