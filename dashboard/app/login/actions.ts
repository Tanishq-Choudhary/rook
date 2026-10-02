"use server";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { apiBase, COOKIE } from "@/lib/config";
import { ApiError, request } from "@/lib/request";

export interface LoginState {
  error: string;
}

const EIGHT_HOURS = 8 * 60 * 60;

export async function login(_previous: LoginState, form: FormData): Promise<LoginState> {
  const key = String(form.get("key") ?? "").trim();
  if (key.length === 0) {
    return { error: "Enter an API key" };
  }
  try {
    await request({ base: apiBase(), key }, "/api/v1/installations");
  } catch (error) {
    if (error instanceof ApiError && (error.status === 401 || error.status === 403)) {
      return { error: "That key was not accepted" };
    }
    if (error instanceof ApiError && error.status === 429) {
      return { error: "Too many attempts, wait a moment and try again" };
    }
    return { error: "The Rook API could not be reached" };
  }
  (await cookies()).set({
    name: COOKIE,
    value: key,
    httpOnly: true,
    sameSite: "lax",
    secure: process.env.NODE_ENV === "production",
    path: "/",
    maxAge: EIGHT_HOURS,
  });
  redirect("/");
}

export async function logout(): Promise<void> {
  (await cookies()).delete(COOKIE);
  redirect("/login");
}
