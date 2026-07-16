// /agents/<aid>/project/<pid> — fresh new chat in a project. UI is
// rendered by the parent layout's <ChatScreen/>; this page only exists
// so Next has a dynamic route to match. ChatScreen reads the project
// id from `usePathname()` and treats it as the lazy-create marker —
// the session row is minted on the first user message and the URL
// upgrades to /chat/<sid>/ via history.replaceState (no remount).
//
// generateStaticParams: under output:'export' Next emits one .html per
// param tuple. We ship a single placeholder ("_") and rely on the Go
// SPA fallback to serve it for any concrete pid at runtime.
export async function generateStaticParams() {
  return [{ pid: "_" }];
}

export default function ProjectPage() {
  return null;
}
