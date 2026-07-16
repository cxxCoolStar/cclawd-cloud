"use client";

import type { AnchorHTMLAttributes } from "react";

/**
 * ExternalAnchor renders <a> for ReactMarkdown chat content with one tweak:
 * links pointing at a different origin open in a new tab. Same-origin / relative
 * / mailto / # links keep default behaviour.
 *
 * Why: chat replies routinely include outbound URLs (Namecheap, GitHub, docs).
 * Opening those in the current tab navigates away from the chat session and
 * loses the agent's context. Forcing target="_blank" only on cross-origin
 * URLs avoids breaking in-app navigation (we still rely on the Next.js router
 * for /agents/<id>/... links the agent might emit).
 *
 * Pair with rel="noopener noreferrer" so the popup can't reach back to
 * window.opener — standard hardening for any anchor that opens a new tab.
 */
export function ExternalAnchor(props: AnchorHTMLAttributes<HTMLAnchorElement>) {
  const { href, children, ...rest } = props;
  const external = isExternalHref(href);
  if (external) {
    return (
      <a href={href} target="_blank" rel="noopener noreferrer" {...rest}>
        {children}
      </a>
    );
  }
  return (
    <a href={href} {...rest}>
      {children}
    </a>
  );
}

function isExternalHref(href: string | undefined): boolean {
  if (!href) return false;
  // Bail on mailto:, tel:, # anchors, and protocol-less / relative paths —
  // those should keep their default in-place behaviour.
  if (!/^https?:\/\//i.test(href)) return false;
  if (typeof window === "undefined") {
    // Server render: assume any absolute http(s) link could be external.
    // Hydration on the client will re-evaluate against the real origin.
    return true;
  }
  try {
    const u = new URL(href);
    return u.host !== window.location.host;
  } catch {
    return false;
  }
}
