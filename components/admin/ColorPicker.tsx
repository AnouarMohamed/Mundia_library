/**
 * ColorPicker Component
 * 
 * Provides an interactive color selection tool for book cover customization.
 * Combines a hex-code text input with a visual color gradient picker.
 * 
 * @author Mundia Library Team
 * @version 1.0.0
 */

"use client";

import { HexColorInput, HexColorPicker } from "react-colorful";

/**
 * Props for ColorPicker
 */
interface Props {
  /**
   * The current hex color value (e.g., "#FFFFFF")
   */
  value?: string;
  /**
   * Callback function triggered when the color selection changes
   */
  onPickerChange: (color: string) => void;
}

/**
 * ColorPicker
 * 
 * A specialized admin component for selecting theme colors.
 * Uses the 'react-colorful' library for a lightweight and accessible experience.
 * 
 * @param {Props} props - Component properties
 * @returns {JSX.Element} The rendered color picker component
 */
const ColorPicker = ({ value, onPickerChange }: Props) => {
  return (
    <div className="relative">
      {/* Hex-code Text Input Area */}
      <div className="flex flex-row items-center gap-1 sm:gap-2">
        <p className="text-base font-semibold text-dark-400">#</p>
        <HexColorInput
          color={value}
          onChange={onPickerChange}
          className="hex-input"
        />
      </div>
      
      {/* Visual Gradient Picker Area */}
      <div className="mt-3 w-full max-w-[180px] sm:max-w-[200px]">
        <HexColorPicker
          color={value}
          onChange={onPickerChange}
          style={{
            width: "100%",
          }}
          className="h-[140px] sm:h-[150px]"
        />
      </div>
    </div>
  );
};

export default ColorPicker;
