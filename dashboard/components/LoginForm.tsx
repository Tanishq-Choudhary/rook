"use client";

import { useActionState } from "react";
import { login, type LoginState } from "@/app/login/actions";

const INITIAL: LoginState = { error: "" };

export function LoginForm() {
  const [state, action, pending] = useActionState(login, INITIAL);
  return (
    <form action={action} className="space-y-4">
      <div className="space-y-1">
        <label htmlFor="key" className="text-sm font-medium">
          API key
        </label>
        <input
          id="key"
          name="key"
          type="password"
          autoComplete="off"
          required
          aria-describedby={state.error ? "login-error" : undefined}
          className="w-full rounded-md border border-line bg-surface px-3 py-2 text-sm"
        />
      </div>
      {state.error ? (
        <p id="login-error" role="alert" className="text-sm text-bad">
          {state.error}
        </p>
      ) : null}
      <button
        type="submit"
        disabled={pending}
        className="w-full rounded-md bg-accent px-3 py-2 text-sm font-medium text-surface disabled:opacity-60"
      >
        {pending ? "Checking" : "Sign in"}
      </button>
    </form>
  );
}
