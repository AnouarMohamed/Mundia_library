/**
 * ISBNScanner Component
 * 
 * A client component that uses the device's camera to scan ISBN barcodes.
 * It uses the 'html5-qrcode' library for high-performance scanning.
 * 
 * Features:
 * - Real-time barcode detection.
 * - Auto-stops camera upon successful scan.
 * - Provides feedback via success/error callbacks.
 */

"use client";

import React, { useEffect, useRef } from "react";
import { Html5QrcodeScanner, Html5QrcodeSupportedFormats } from "html5-qrcode";
import { Button } from "@/components/ui/button";
import { X } from "lucide-react";

interface ISBNScannerProps {
  onScanSuccess: (decodedText: string) => void;
  onClose: () => void;
}

const ISBNScanner: React.FC<ISBNScannerProps> = ({ onScanSuccess, onClose }) => {
  const scannerRef = useRef<Html5QrcodeScanner | null>(null);

  useEffect(() => {
    // Initialize the scanner
    // We only want to scan EAN-13 (ISBN-13) or UPC-A/E (ISBN-10 fallback)
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
        fps: 10, 
        qrbox: { width: 250, height: 150 },
        aspectRatio: 1.777778, // 16:9
        formatsToSupport: formats,
      },
      /* verbose= */ false
    );

    scanner.render(
      (decodedText) => {
        // Success: Decoded ISBN
        // We clean the result (ISBNs might have dashes)
        const cleanedIsbn = decodedText.replace(/[-\s]/g, "");
        onScanSuccess(cleanedIsbn);
        scanner.clear(); // Stop scanning after success
      },
      (_errorMessage) => {
        // Error is called on every frame where no barcode is found
        // We usually ignore this unless it's a critical initialization error
      }
    );

    scannerRef.current = scanner;

    // Cleanup on unmount
    return () => {
      if (scannerRef.current) {
        scannerRef.current.clear().catch((err) => console.error("Failed to clear scanner:", err));
      }
    };
  }, [onScanSuccess]);

  return (
    <div className="relative flex flex-col items-center gap-4 bg-dark-400 p-6 rounded-2xl border border-white/10">
      <div className="flex w-full items-center justify-between mb-2">
        <h3 className="text-lg font-bebas-neue tracking-widest text-light-100">Scan Book ISBN</h3>
        <Button variant="ghost" size="icon" onClick={onClose} className="text-light-200 hover:text-white">
          <X className="size-5" />
        </Button>
      </div>
      
      <div id="reader" className="w-full overflow-hidden rounded-xl bg-black/40"></div>
      
      <p className="text-center text-xs text-light-200/60">
        Position the barcode within the frame to scan.
      </p>
    </div>
  );
};

export default ISBNScanner;
