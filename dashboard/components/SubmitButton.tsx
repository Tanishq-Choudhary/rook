"use client";

import type { ComponentProps } from "react";
import { useFormStatus } from "react-dom";

export function SubmitButton({
  children,
  tone = "neutral",
  ...rest
}: ComponentProps<"button"> & { tone?: "primary" | "danger" | "neutral" }) {
  const { pending } = useFormStatus();
  const palette = {
    primary: "bg-accent text-surface",
    danger: "border border-bad/50 text-bad",
    neutral: "border border-line",
  }[tone];
  return (
    <button
      type="submit"
      disabled={pending || rest.disabled}
      className={`rounded-md px-3 py-1.5 text-sm font-medium disabled:opacity-60 ${palette}`}
      {...rest}
    >
      {children}
    </button>
  );
}
