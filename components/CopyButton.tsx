/**
 * CopyButton Component
 * 
 * A utility component that provides a one-click way to copy text to the system clipboard.
 * Features a visual feedback state (success icon) after a successful copy.
 * 
 * @author Mundia Library Team
 * @version 1.0.0
 */

"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Copy, Check } from "lucide-react";

/**
 * Props for CopyButton
 */
interface CopyButtonProps {
  /**
   * The literal text string to be copied to the clipboard
   */
  text: string;
  /**
   * Optional CSS classes for styling the button
   */
  className?: string;
}

/**
 * CopyButton
 * 
 * An icon-based button that interacts with the navigator.clipboard API.
 * Provides immediate visual feedback to the user.
 * 
 * @param {CopyButtonProps} props - Component properties
 * @returns {JSX.Element} The rendered copy button
 */
export const CopyButton = ({ text, className }: CopyButtonProps) => {
  // State to track if the copy operation was recently successful
  const [copied, setCopied] = useState(false);

  /**
   * Asynchronously copies the provided text to the clipboard.
   * Updates state to show feedback and resets it after a delay.
   */
  const copyToClipboard = async () => {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      
      // Reset the "copied" state after 2 seconds to restore the original icon
      setTimeout(() => setCopied(false), 2000); 
    } catch (err) {
      console.error("Failed to copy text: ", err);
    }
  };

  return (
    <Button
      size="sm"
      variant="outline"
      onClick={copyToClipboard}
      className={className}
    >
      {/* Show Check icon for feedback, otherwise show Copy icon */}
      {copied ? (
        <Check className="size-3 text-green-500 sm:size-4" />
      ) : (
        <Copy className="size-3 sm:size-4" />
      )}
    </Button>
  );
};
