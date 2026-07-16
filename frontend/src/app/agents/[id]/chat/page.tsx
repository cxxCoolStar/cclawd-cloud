// /agents/<aid>/chat — fresh loose chat. The visible UI is rendered by
// the parent layout's <ChatScreen/>; this page only exists so Next has a
// route to match. ChatScreen reads `usePathname()` and switches into
// "fresh chat" mode when no `chat/<sid>` segment is present.
export default function ChatPage() {
  return null;
}
