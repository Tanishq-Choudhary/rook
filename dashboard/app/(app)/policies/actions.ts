"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { api, query } from "@/lib/api";
import { policyFromForm } from "@/lib/policy";
import { ApiError } from "@/lib/request";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export async function savePolicy(installation: string, form: FormData): Promise<void> {
  if (!UUID.test(installation)) {
    redirect("/policies");
  }
  let failure: string | null = null;
  try {
    const body = policyFromForm(form);
    await api(`/api/v1/policies${query({ installation })}`, { method: "PUT", body: JSON.stringify(body) });
  } catch (error) {
    failure = error instanceof ApiError || error instanceof Error ? error.message : "The policy was not saved";
  }
  revalidatePath("/policies");
  redirect(
    failure
      ? `/policies${query({ installation, error: failure })}`
      : `/policies${query({ installation, saved: 1 })}`,
  );
}
