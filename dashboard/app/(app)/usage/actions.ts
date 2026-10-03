"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { api, query } from "@/lib/api";
import { dollarsToMicros } from "@/lib/money";
import { ApiError } from "@/lib/request";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export async function saveBudget(installation: string, form: FormData): Promise<void> {
  if (!UUID.test(installation)) {
    redirect("/usage");
  }
  let failure: string | null = null;
  try {
    await api(`/api/v1/budgets${query({ installation })}`, {
      method: "PUT",
      body: JSON.stringify({
        monthlyMicros: dollarsToMicros(String(form.get("monthly") ?? "")),
        jobMicros: dollarsToMicros(String(form.get("job") ?? "")),
      }),
    });
  } catch (error) {
    failure = error instanceof ApiError || error instanceof Error ? error.message : "The budget was not saved";
  }
  revalidatePath("/usage");
  redirect(
    failure ? `/usage${query({ installation, error: failure })}` : `/usage${query({ installation, saved: 1 })}`,
  );
}
