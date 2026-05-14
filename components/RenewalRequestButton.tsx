/**
 * RenewalRequestButton Component
 *
 * A client component that allows students to request a renewal for a borrowed book.
 * It includes a dialog to provide an optional reason for the renewal.
 *
 * Features:
 * - Checks eligibility before allowing the request.
 * - Displays a loading state during the submission.
 * - Shows success/error toasts upon completion.
 */

"use client";

import React, { useState, useEffect } from "react";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
  DialogFooter,
  DialogDescription,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { RotateCcw, Loader2 } from "lucide-react";
import { requestRenewal, canRequestRenewal } from "@/lib/actions/renewal";
import { toast } from "@/hooks/use-toast";

interface RenewalRequestButtonProps {
  borrowRecordId: string;
  bookTitle: string;
}

const RenewalRequestButton: React.FC<RenewalRequestButtonProps> = ({
  borrowRecordId,
  bookTitle,
}) => {
  const [isOpen, setIsOpen] = useState(false);
  const [reason, setReason] = useState("");
  const [isLoading, setIsLoading] = useState(false);
  const [isEligible, setIsEligible] = useState(false);
  const [isChecking, setIsChecking] = useState(true);

  // Check eligibility on mount
  useEffect(() => {
    const checkEligibility = async () => {
      const eligible = await canRequestRenewal(borrowRecordId);
      setIsEligible(eligible);
      setIsChecking(false);
    };
    checkEligibility();
  }, [borrowRecordId]);

  const handleSubmit = async () => {
    setIsLoading(true);
    try {
      const result = await requestRenewal({
        borrowRecordId,
        reason: reason.trim() || undefined,
      });

      if (result.success) {
        toast({
          title: "Request submitted",
          description: result.message,
          variant: "default",
        });
        setIsOpen(false);
        setIsEligible(false); // Disable button after successful request
      } else {
        toast({
          title: "Request failed",
          description: result.error,
          variant: "destructive",
        });
      }
    } catch {
      toast({
        title: "Error",
        description: "An unexpected error occurred.",
        variant: "destructive",
      });
    } finally {
      setIsLoading(false);
    }
  };

  if (isChecking) return null;
  if (!isEligible) return null;

  return (
    <Dialog open={isOpen} onOpenChange={setIsOpen}>
      <DialogTrigger asChild>
        <Button
          variant="outline"
          size="sm"
          className="flex min-h-11 items-center gap-1 border-[var(--mundia-line)] bg-[var(--mundia-paper)] text-[var(--mundia-ink)] hover:border-[var(--mundia-navy)] hover:bg-[var(--mundia-panel)]"
        >
          <RotateCcw className="size-3 sm:size-4" />
          <span>Request renewal</span>
        </Button>
      </DialogTrigger>
      <DialogContent className="surface-panel sm:max-w-[425px]">
        <DialogHeader>
          <DialogTitle className="text-[var(--mundia-ink)]">
            Request renewal
          </DialogTitle>
          <DialogDescription>
            Request to extend the due date for <strong>{bookTitle}</strong> by 7
            days.
          </DialogDescription>
        </DialogHeader>
        <div className="grid gap-4 py-4">
          <div className="flex flex-col gap-2">
            <label htmlFor="reason" className="text-sm font-medium">
              Reason (optional)
            </label>
            <Textarea
              id="reason"
              placeholder="Why do you need more time?"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              className="app-control min-h-24 resize-none py-3"
              rows={3}
            />
          </div>
        </div>
        <DialogFooter>
          <Button
            variant="ghost"
            onClick={() => setIsOpen(false)}
            disabled={isLoading}
            className="text-[var(--mundia-ink)] hover:bg-[var(--mundia-panel)]"
          >
            Cancel
          </Button>
          <Button
            onClick={handleSubmit}
            disabled={isLoading}
            className="bg-[var(--mundia-navy)] text-white hover:bg-[var(--mundia-navy-strong)]"
          >
            {isLoading ? (
              <>
                <Loader2 className="mr-2 size-4 animate-spin" />
                Submitting...
              </>
            ) : (
              "Submit request"
            )}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default RenewalRequestButton;
