// /agents/<aid>/chat/<session> — open existing chat by id. UI is
// rendered by the parent layout's <ChatScreen/>; this page only exists
// so Next has a dynamic route to match. ChatScreen reads the session id
// from `usePathname()`.
//
// generateStaticParams: under output:'export' Next emits one .html per
// param tuple. We ship a single placeholder ("_") and rely on the Go
// SPA fallback to serve it for any concrete session id at runtime.
export async function generateStaticParams() {
  return [{ session: "_" }];
}

export default function ChatSessionPage() {
  return null;
}
