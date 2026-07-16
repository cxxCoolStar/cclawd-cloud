// /agents/<aid>/ — bare agent URL. UI is rendered by the parent
// layout's <ChatScreen/> (which treats this as the same fresh-chat
// state as /agents/<aid>/chat/). This page only exists so Next has a
// route to match — without it the bare URL 404s.
export default function AgentRootPage() {
  return null;
}
