"use client";

import * as React from "react";
import { usePathname } from "next/navigation";
import { SidebarLayout } from "@/components/sidebar";

// Paths that render on their own (no sidebar chrome). /signup is in
// here because hitting it directly while signed out (e.g. from an admin
// invite link) was leaking the authenticated app chrome — Overview /
// Agents / Models in the sidebar — to a not-yet-registered visitor.
const BARE_PATHS = ["/", "/onboard", "/signup"];

function wantsSidebar(pathname: string) {
  if (BARE_PATHS.includes(pathname)) return false;
  if (pathname.startsWith("/onboard/")) return false;
  if (pathname.startsWith("/signup/")) return false;
  return true;
}

// AppShell mounts SidebarLayout once for every authenticated page and keeps
// that instance alive across client-side navigations. Previously each route
// segment had its own layout.tsx that re-wrapped SidebarLayout, so Next
// unmounted and remounted the sidebar on every top-level nav — triggering a
// fresh status / agents / sessions fetch and a visible flash. One shell at
// the root means the sidebar (and its effects) persists across navigations.
export function AppShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  if (!wantsSidebar(pathname)) {
    return <>{children}</>;
  }
  return <SidebarLayout>{children}</SidebarLayout>;
}
