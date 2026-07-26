/**
 * CountdownTimer Component
 * 
 * Calculates and displays a live countdown or overdue status badge
 * for book return deadlines.
 * 
 * @author Mundia Library Team
 * @version 1.1.0
 */

"use client";

import React, { useState, useEffect } from "react";
import { Badge } from "@/components/ui/badge";

/**
 * Props for CountdownTimer
 */
interface CountdownTimerProps {
  /**
   * The deadline date for returning the book
   */
  dueDate: Date;
  /**
   * The original date the book was borrowed
   */
  borrowDate: Date;
}

/**
 * CountdownTimer
 * 
 * Displays remaining time until due date.
 * 
 * CRITICAL PERFORMANCE: 
 * Memoized to prevent unnecessary re-renders when parent component updates.
 * Only recalculates when dueDate timestamp actually changes.
 */
const CountdownTimer: React.FC<CountdownTimerProps> = React.memo(({
  dueDate,
  borrowDate: _borrowDate,
}) => {
  // Local state for the granular time components
  const [timeLeft, setTimeLeft] = useState<{
    days: number;
    hours: number;
    minutes: number;
    seconds: number;
    isOverdue: boolean;
  }>({
    days: 0,
    hours: 0,
    minutes: 0,
    seconds: 0,
    isOverdue: false,
  });

  /**
   * CRITICAL: Use timestamp instead of Date object in dependency arrays.
   * This ensures the effect only runs when the actual value changes,
   * not when a new Date instance with the same value is passed.
   */
  const dueDateTimestamp = dueDate.getTime();

  useEffect(() => {
    let timer: NodeJS.Timeout | null = null;

    /**
     * Core calculation logic for the remaining or overdue time.
     */
    const calculateTimeLeft = () => {
      try {
        const now = new Date().getTime();
        const due = dueDateTimestamp;
        const difference = due - now;

        if (difference > 0) {
          // Future deadline: Calculate remaining components
          const days = Math.floor(difference / (1000 * 60 * 60 * 24));
          const hours = Math.floor(
            (difference % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60)
          );
          const minutes = Math.floor(
            (difference % (1000 * 60 * 60)) / (1000 * 60)
          );
          const seconds = Math.floor((difference % (1000 * 60)) / 1000);

          setTimeLeft({ days, hours, minutes, seconds, isOverdue: false });
        } else {
          // Passed deadline: Calculate overdue components
          const overdueDiff = Math.abs(difference);
          const overdueDays = Math.floor(overdueDiff / (1000 * 60 * 60 * 24));
          const overdueHours = Math.floor(
            (overdueDiff % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60)
          );
          const overdueMinutes = Math.floor(
            (overdueDiff % (1000 * 60 * 60)) / (1000 * 60)
          );
          const overdueSeconds = Math.floor((overdueDiff % (1000 * 60)) / 1000);

          setTimeLeft({
            days: overdueDays,
            hours: overdueHours,
            minutes: overdueMinutes,
            seconds: overdueSeconds,
            isOverdue: true,
          });
        }
      } catch (error) {
        console.warn("Error calculating time left:", error);
      }
    };

    // Initial calculation and 1-second interval setup
    calculateTimeLeft();
    timer = setInterval(calculateTimeLeft, 1000);

    // Cleanup interval on unmount
    return () => {
      if (timer) {
        clearInterval(timer);
        timer = null;
      }
    };
  }, [dueDateTimestamp]);

  /**
   * Returns the appropriate badge variant based on urgency or overdue status.
   */
  const getBadgeVariant = () => {
    if (timeLeft.isOverdue) return "destructive";
    if (timeLeft.days <= 1) return "destructive";
    if (timeLeft.days <= 3) return "secondary";
    return "default";
  };

  /**
   * Formats the text label for the countdown badge.
   */
  const getBadgeText = () => {
    if (timeLeft.isOverdue) {
      return `Overdue: ${timeLeft.days}d ${timeLeft.hours}h ${timeLeft.minutes}m`;
    }
    return `Remaining: ${timeLeft.days}d ${timeLeft.hours}h ${timeLeft.minutes}m`;
  };

  return (
    <Badge variant={getBadgeVariant()} className="font-mono text-[10px] sm:text-xs">
      {getBadgeText()}
    </Badge>
  );
}, (prevProps, nextProps) => {
  // CRITICAL PERFORMANCE: Only re-render if the core timestamp changes
  return prevProps.dueDate.getTime() === nextProps.dueDate.getTime();
});

// Set display name for React DevTools debugging
CountdownTimer.displayName = "CountdownTimer";

export default CountdownTimer;
