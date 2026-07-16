import AgentLayoutClient from "./layout-client";

export function generateStaticParams() {
  return [{ id: "default" }];
}

// Server params here resolve at BUILD time (output: 'export' bakes
// generateStaticParams' "default" into the bundle), so we can't pass
// the agent id down — the client wrapper reads it from the URL via
// usePathname() instead.
export default function AgentLayout({ children }: { children: React.ReactNode }) {
  return <AgentLayoutClient>{children}</AgentLayoutClient>;
}
