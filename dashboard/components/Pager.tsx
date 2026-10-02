import Link from "next/link";
import type { Page } from "@/lib/types";

export function pageHref(path: string, params: Record<string, string | undefined>, page: number): string {
  const search = new URLSearchParams();
  for (const [name, value] of Object.entries(params)) {
    if (value) {
      search.set(name, value);
    }
  }
  if (page > 0) {
    search.set("page", String(page));
  }
  const text = search.toString();
  return text ? `${path}?${text}` : path;
}

export function Pager<T>({
  page,
  path,
  params,
}: {
  page: Page<T>;
  path: string;
  params: Record<string, string | undefined>;
}) {
  const pages = Math.max(1, Math.ceil(page.total / page.size));
  return (
    <nav aria-label="Pages" className="flex items-center justify-between text-sm">
      <span className="text-muted">
        {page.total} in total, page {page.page + 1} of {pages}
      </span>
      <span className="flex gap-3">
        {page.page > 0 ? <Link href={pageHref(path, params, page.page - 1)}>Previous</Link> : null}
        {page.page + 1 < pages ? <Link href={pageHref(path, params, page.page + 1)}>Next</Link> : null}
      </span>
    </nav>
  );
}
