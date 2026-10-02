"use server";

import { revalidatePath } from "next/cache";
import { notFound, redirect } from "next/navigation";
import { api } from "@/lib/api";
import { ApiError } from "@/lib/request";

type Action = "approve" | "reject" | "cancel" | "retry";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

async function run(id: string, action: Action, note?: string): Promise<never> {
  if (!UUID.test(id)) {
    notFound();
  }
  let failure: string | null = null;
  try {
    await api(`/api/v1/jobs/${id}/${action}`, {
      method: "POST",
      body: note === undefined ? undefined : JSON.stringify({ note }),
    });
  } catch (error) {
    failure = error instanceof ApiError ? error.message : "The action failed";
  }
  revalidatePath(`/jobs/${id}`);
  redirect(failure ? `/jobs/${id}?error=${encodeURIComponent(failure)}` : `/jobs/${id}`);
}

function note(form: FormData): string {
  return String(form.get("note") ?? "").slice(0, 500);
}

export async function approve(id: string, form: FormData): Promise<void> {
  await run(id, "approve", note(form));
}

export async function reject(id: string, form: FormData): Promise<void> {
  await run(id, "reject", note(form));
}

export async function cancel(id: string): Promise<void> {
  await run(id, "cancel");
}

export async function retry(id: string): Promise<void> {
  await run(id, "retry");
}
