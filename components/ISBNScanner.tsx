/**
 * ISBNScanner Component
 * 
 * A specialized utility for scanning physical book barcodes using the device's camera.
 * Optimized for ISBN-10 and ISBN-13 formats using the 'html5-qrcode' library.
 * 
 * @author Mundia Library Team
 * @version 1.0.0
 */

"use client";

import React, { useEffect, useRef } from "react";
import { Html5QrcodeScanner, Html5QrcodeSupportedFormats } from "html5-qrcode";
import { Button } from "@/components/ui/button";
import { X } from "lucide-react";

/**
 * Props for ISBNScanner
 */
interface ISBNScannerProps {
  /**
   * Callback function triggered when a barcode is successfully decoded
   */
  onScanSuccess: (decodedText: string) => void;
  /**
   * Callback function to handle the closing of the scanner interface
   */
  onClose: () => void;
}

/**
 * ISBNScanner
 * 
 * Camera-based ISBN barcode scanner.
 * Features:
 * - Real-time barcode detection.
 * - Auto-stops camera upon successful scan.
 * - Provides feedback via success/error callbacks.
 */
const ISBNScanner: React.FC<ISBNScannerProps> = ({
  onScanSuccess,
  onClose,
}) => {
  // Reference to the scanner instance for cleanup
  const scannerRef = useRef<Html5QrcodeScanner | null>(null);

  useEffect(() => {
    /**
     * Initialization:
     * We focus on standard retail/book formats (EAN-13 for ISBN-13, UPC for ISBN-10).
     */
    const formats = [
      Html5QrcodeSupportedFormats.EAN_13,
      Html5QrcodeSupportedFormats.EAN_8,
      Html5QrcodeSupportedFormats.CODE_128,
      Html5QrcodeSupportedFormats.UPC_A,
      Html5QrcodeSupportedFormats.UPC_E,
    ];

    const scanner = new Html5QrcodeScanner(
      "reader",
      {
        fps: 10, // Frames per second for scanning
        qrbox: { width: 250, height: 150 }, // UI focus area
        aspectRatio: 1.777778, // 16:9 standard video aspect ratio
        formatsToSupport: formats,
      },
      /* verbose= */ false,
    );

    // Start rendering the scanner UI and video stream
    scanner.render(
      (decodedText) => {
        /**
         * SUCCESS HANDLER:
         * Clean the result (remove dashes and spaces common in ISBN prints).
         */
        const cleanedIsbn = decodedText.replace(/[-\s]/g, "");
        onScanSuccess(cleanedIsbn);
        
        // Stop the camera and clear the UI after a successful read to save resources
        scanner.clear(); 
      },
      (_errorMessage) => {
        // NOTE: This callback fires frequently when no barcode is in view.
        // Usually ignored to prevent logging spam.
      },
    );

    scannerRef.current = scanner;

    // Cleanup: Ensure the camera is released when the component is unmounted
    return () => {
      if (scannerRef.current) {
        scannerRef.current
          .clear()
          .catch((err) => console.error("Failed to clear scanner during unmount:", err));
      }
    };
  }, [onScanSuccess]);

  return (
    <div className="relative flex flex-col items-center gap-4 rounded-lg border border-[var(--mundia-line)] bg-[var(--surface-card-strong)] p-6">
      {/* Header with Title and Close Button */}
      <div className="flex w-full items-center justify-between mb-2">
        <h3 className="text-lg font-semibold tracking-tight text-[var(--mundia-ink)]">
          Scan Book ISBN
        </h3>
        <Button
          variant="ghost"
          size="icon"
          onClick={onClose}
          className="text-slate-600 hover:text-[var(--mundia-ink)]"
        >
          <X className="size-5" />
        </Button>
      </div>

      {/* Target element where html5-qrcode will render the camera stream */}
      <div
        id="reader"
        className="w-full overflow-hidden rounded-lg bg-[var(--mundia-ink-strong)]"
      ></div>

      <p className="text-center text-xs text-slate-500">
        Position the barcode within the frame to scan.
      </p>
    </div>
  );
};

export default ISBNScanner;
