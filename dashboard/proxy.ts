import { NextResponse, type NextRequest } from "next/server";
import { COOKIE } from "@/lib/config";

export function proxy(request: NextRequest) {
  const signedIn = Boolean(request.cookies.get(COOKIE)?.value);
  const onLogin = request.nextUrl.pathname.startsWith("/login");
  if (!signedIn && !onLogin) {
    return NextResponse.redirect(new URL("/login", request.url));
  }
  if (signedIn && onLogin) {
    return NextResponse.redirect(new URL("/", request.url));
  }
  return NextResponse.next();
}

export const config = {
  matcher: ["/((?!_next/static|_next/image|favicon.ico).*)"],
};
