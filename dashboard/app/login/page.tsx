import { LoginForm } from "@/components/LoginForm";

export const metadata = { title: "Sign in" };

export default function LoginPage() {
  return (
    <main className="mx-auto flex min-h-screen max-w-sm flex-col justify-center px-4">
      <h1 className="mb-1 text-2xl font-semibold tracking-tight">Rook</h1>
      <p className="mb-6 text-sm text-muted">Sign in with an API key. The key stays in an HTTP only cookie.</p>
      <LoginForm />
    </main>
  );
}
