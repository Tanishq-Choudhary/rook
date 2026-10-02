import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

const login = vi.fn();

vi.mock("@/app/login/actions", () => ({
  login: (...args: unknown[]) => login(...args),
}));

import { LoginForm } from "./LoginForm";

describe("LoginForm", () => {
  it("asks for a key and hides what is typed", () => {
    render(<LoginForm />);
    const input = screen.getByLabelText("API key");
    expect(input).toHaveAttribute("type", "password");
    expect(input).toBeRequired();
  });

  it("shows the reason when the key is refused", async () => {
    login.mockResolvedValueOnce({ error: "That key was not accepted" });
    render(<LoginForm />);
    await userEvent.type(screen.getByLabelText("API key"), "wrong");
    await userEvent.click(screen.getByRole("button", { name: "Sign in" }));
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("That key was not accepted"));
    expect(screen.getByLabelText("API key")).toHaveAttribute("aria-describedby", "login-error");
  });
});
