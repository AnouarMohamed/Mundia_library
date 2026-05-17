/**
 * Toast Notification Utility
 * 
 * Provides a centralized, type-safe API for displaying toast notifications throughout the application.
 * It wraps the custom 'use-toast' hook to provide consistent styling and messaging for common
 * actions like authentication, book management, and file uploads.
 * 
 * The utility is organized into semantic namespaces (auth, book, file) to improve discoverability
 * and maintainability of notification logic.
 */

import { toast } from "@/hooks/use-toast";

/**
 * Global toast dispatcher.
 */
export const showToast = {
  /**
   * Displays a generic success toast.
   * @param title - The headline for the toast.
   * @param description - The supporting message.
   */
  success: (title: string, description: string) => {
    toast({
      title: ` ${title}`,
      description,
    });
  },

  /**
   * Displays a generic error toast with destructive styling.
   * @param title - The error headline.
   * @param description - The error details.
   */
  error: (title: string, description: string) => {
    toast({
      title: ` ${title}`,
      description,
      variant: "destructive",
    });
  },

  /**
   * Displays a warning toast (currently uses destructive styling).
   * @param title - The warning headline.
   * @param description - The warning details.
   */
  warning: (title: string, description: string) => {
    toast({
      title: ` ${title}`,
      description,
      variant: "destructive",
    });
  },

  /**
   * Displays a generic informational toast.
   * @param title - The info headline.
   * @param description - The info details.
   */
  info: (title: string, description: string) => {
    toast({
      title: ` ${title}`,
      description,
    });
  },

  /** Authentication-related notifications. */
  auth: {
    /** Shown after successful sign in. */
    signInSuccess: () => {
      toast({
        title: " Welcome Back!",
        description: "You have successfully signed in to Mundiapolis Library.",
      });
    },
    /** Shown after successful account creation. */
    signUpSuccess: () => {
      toast({
        title: " Account Created!",
        description:
          "Welcome to Mundiapolis Library! Your account has been created successfully.",
      });
    },
    /** Shown after successful logout. */
    logoutSuccess: () => {
      toast({
        title: " Logged Out",
        description:
          "You have been logged out successfully. Thank you for using Mundiapolis Library!",
      });
    },
  },

  /** Book management notifications. */
  book: {
    /** Shown when a book is successfully borrowed. */
    borrowSuccess: (bookTitle: string) => {
      toast({
        title: " Book Borrowed!",
        description: `"${bookTitle}" has been added to your borrowed collection. Enjoy reading!`,
      });
    },
    /** Shown when a new book is successfully added to the system. */
    createSuccess: (bookTitle: string) => {
      toast({
        title: " Book Created!",
        description: `"${bookTitle}" has been added to the library collection.`,
      });
    },
    /** Shown when a borrow attempt fails. */
    borrowError: (message: string) => {
      toast({
        title: " Cannot Borrow Book",
        description: message,
        variant: "destructive",
      });
    },
    /** Shown when a book is successfully returned. */
    returnSuccess: (bookTitle: string) => {
      toast({
        title: " Book Returned!",
        description: `"${bookTitle}" has been successfully returned to the library. Thank you!`,
      });
    },
    /** Shown when a return attempt fails. */
    returnError: (message: string) => {
      toast({
        title: " Cannot Return Book",
        description: message,
        variant: "destructive",
      });
    },
  },

  /** File upload notifications. */
  file: {
    /** Shown after a successful file upload to ImageKit. */
    uploadSuccess: (type: "image" | "video", fileName: string) => {
      toast({
        title: ` ${type === "image" ? "Image" : "Video"} Uploaded!`,
        description: `${fileName} has been uploaded successfully and is ready to use.`,
      });
    },
    /** Shown when a file upload fails. */
    uploadError: (message: string) => {
      toast({
        title: " Upload Failed",
        description: message,
        variant: "destructive",
      });
    },
    /** Shown when a file exceeds size limits. */
    fileTooLarge: (type: "image" | "video", maxSize: string) => {
      toast({
        title: " File Too Large",
        description: `${type === "image" ? "Image" : "Video"} files must be smaller than ${maxSize}. Please compress your file and try again.`,
        variant: "destructive",
      });
    },
  },
};
