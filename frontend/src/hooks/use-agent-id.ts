"use client";

import { useParams, usePathname } from "next/navigation";

// Static-export only generates /agents/default/..., so useParams() always
// returns "default" when an app is served for a non-default agent via the
// Go spaHandler fallback. Parse the real id from the *reactive* pathname
// instead — usePathname() updates on every client navigation, so callers
// see the new id immediately when the user switches agents (otherwise
// background fetches keep firing against the old id and the chat panel
// shows the wrong history).
export function useAgentIdFromURL(): string {
  const pathname = usePathname();
  const params = useParams<{ id: string }>();
  const m = pathname?.match(/\/agents\/([^/]+)\//);
  if (m) return m[1];
  return params?.id ?? "default";
}
