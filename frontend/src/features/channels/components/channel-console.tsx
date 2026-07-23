"use client";

import { useEffect, useState } from "react";
import {
  Bot,
  Check,
  ChevronRight,
  Clock3,
  Database,
  Inbox,
  MessageCircle,
  Radio,
  RefreshCw,
  Search,
  Server,
  ShieldCheck,
  Wifi,
} from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { useChannelConsole } from "../hooks/use-channel-console";
import { getChannelMessage } from "../services/channel-api";
import type { ChannelMessage, ChannelMessageDetail } from "../types";

function statusOf(message: ChannelMessage) {
  if (message.outboxStatus === "SENT") return "Delivered";
  if (["DEAD", "INTERRUPTED"].includes(message.outboxStatus || message.inboxStatus)) return "Failed";
  return "Processing";
}

function StatusBadge({ status }: { status: string }) {
  const healthy = ["Delivered", "Healthy", "HEALTHY", "connected", "active"].includes(status);
  const failed = ["Failed", "DEAD", "INTERRUPTED", "ERROR", "UNAVAILABLE", "expired", "stopped"].includes(status);
  const warning = ["DEGRADED", "degraded"].includes(status);
  const style = healthy
    ? "border-emerald-200 bg-emerald-50 text-emerald-700 dark:border-emerald-900 dark:bg-emerald-950 dark:text-emerald-300"
    : failed
      ? "border-red-200 bg-red-50 text-red-700 dark:border-red-900 dark:bg-red-950 dark:text-red-300"
      : warning
        ? "border-amber-200 bg-amber-50 text-amber-700 dark:border-amber-900 dark:bg-amber-950 dark:text-amber-300"
        : "border-sky-200 bg-sky-50 text-sky-700 dark:border-sky-900 dark:bg-sky-950 dark:text-sky-300";
  return <Badge variant="outline" className={style}><span className="mr-1.5 size-1.5 rounded-full bg-current" />{status}</Badge>;
}

function formatTimestamp(value?: string) {
  return value ? new Date(value).toLocaleString() : "Not reported";
}

function Metric({ label, value, detail, icon: Icon }: { label: string; value: string; detail: string; icon: React.ElementType }) {
  return (
    <div className="min-w-0 border-r border-border px-5 py-4 last:border-r-0">
      <div className="flex items-center justify-between gap-3"><span className="text-xs font-medium text-muted-foreground">{label}</span><Icon className="size-4 text-muted-foreground" /></div>
      <div className="mt-2 flex items-baseline gap-2"><span className="text-2xl font-semibold tabular-nums">{value}</span><span className="truncate text-xs text-muted-foreground">{detail}</span></div>
    </div>
  );
}

function EmptyState({ title, detail }: { title: string; detail: string }) {
  return <div className="flex min-h-40 flex-col items-center justify-center rounded-md border border-dashed p-8 text-center"><Radio className="mb-3 size-6 text-muted-foreground" /><p className="text-sm font-medium">{title}</p><p className="mt-1 text-xs text-muted-foreground">{detail}</p></div>;
}

function MessagesView({ messages, selected, onSelect }: { messages: ChannelMessage[]; selected: ChannelMessageDetail | null; onSelect: (message: ChannelMessage) => void }) {
  return (
    <div className="grid min-h-[460px] overflow-hidden rounded-md border bg-card lg:grid-cols-[minmax(0,1fr)_360px]">
      <div className="overflow-x-auto border-b lg:border-b-0 lg:border-r">
        <table className="w-full text-sm">
          <thead className="border-b bg-muted/40 text-left text-xs text-muted-foreground"><tr><th className="px-4 py-3 font-medium">Message</th><th className="px-4 py-3 font-medium">Status</th><th className="px-4 py-3 font-medium">Attempts</th><th className="px-4 py-3 font-medium">Received</th><th className="w-10" /></tr></thead>
          <tbody className="divide-y">
            {messages.map((message) => (
              <tr key={message.id} onClick={() => onSelect(message)} className={`cursor-pointer transition-colors hover:bg-muted/40 ${selected?.message.id === message.id ? "bg-muted/60" : ""}`}>
                <td className="max-w-[360px] px-4 py-3"><p className="truncate font-medium">{message.text}</p><p className="mt-1 truncate text-xs text-muted-foreground">{message.senderId} · {message.type} / {message.displayName || message.accountId}</p></td>
                <td className="px-4 py-3"><StatusBadge status={statusOf(message)} /></td>
                <td className="px-4 py-3 font-mono text-xs tabular-nums">{message.attempts}</td>
                <td className="px-4 py-3 text-xs text-muted-foreground">{new Date(message.createdAt).toLocaleTimeString()}</td>
                <td className="pr-3"><ChevronRight className="size-4 text-muted-foreground" /></td>
              </tr>
            ))}
          </tbody>
        </table>
        {messages.length === 0 && <div className="p-8 text-center text-sm text-muted-foreground">No messages found</div>}
      </div>
      <aside className="p-5">
        {!selected ? <div className="flex h-full items-center justify-center text-sm text-muted-foreground">Select a message to inspect its timeline.</div> : <>
          <div className="flex items-start justify-between gap-3"><div><p className="font-mono text-xs text-muted-foreground">{selected.message.id}</p><h3 className="mt-1 text-sm font-semibold">Delivery timeline</h3></div><StatusBadge status={statusOf(selected.message)} /></div>
          <div className="mt-6">
            {selected.timeline.map((step, index) => (
              <div key={step.stage} className="relative flex gap-3 pb-6 last:pb-0">
                {index < selected.timeline.length - 1 && <span className="absolute left-[11px] top-6 h-[calc(100%-8px)] w-px bg-border" />}
                <div className="relative z-10 flex size-6 shrink-0 items-center justify-center rounded-full border border-emerald-200 bg-emerald-50 text-emerald-700"><Check className="size-3.5" /></div>
                <div className="min-w-0 flex-1"><div className="flex justify-between gap-3"><p className="text-xs font-medium">{step.stage.replaceAll("_", " ")}</p>{step.startedAt && <time className="font-mono text-[10px] text-muted-foreground">{new Date(step.startedAt).toLocaleTimeString()}</time>}</div><p className="mt-1 text-xs text-muted-foreground">{step.status} · {step.detail}</p></div>
              </div>
            ))}
          </div>
          {selected.message.lastError && <div className="mt-5 rounded-md border border-red-200 bg-red-50 p-3 text-xs text-red-700 dark:border-red-900 dark:bg-red-950 dark:text-red-300">{selected.message.lastError}</div>}
        </>}
      </aside>
    </div>
  );
}

export function ChannelConsole() {
  const [query, setQuery] = useState("");
  const { summary, accounts, messages, failures, runtime, loading, error, reload } = useChannelConsole(query);
  const [selected, setSelected] = useState<ChannelMessageDetail | null>(null);

  useEffect(() => {
    if (!selected && messages.items[0]) void getChannelMessage(messages.items[0].id).then(setSelected).catch(() => {});
  }, [messages.items, selected]);

  const selectMessage = (message: ChannelMessage) => {
    getChannelMessage(message.id).then(setSelected).catch(() => setSelected(null));
  };

  return (
    <div className="mx-auto w-full max-w-[1440px] space-y-5 p-4 sm:p-6">
      <header className="flex flex-col justify-between gap-3 sm:flex-row sm:items-center">
        <div><h1 className="text-2xl font-semibold">Channels</h1><p className="mt-1 text-sm text-muted-foreground">Accounts, message delivery, failures, and runtime health</p></div>
        <div className="flex items-center gap-3"><div className="flex items-center gap-2 text-xs text-muted-foreground"><span className={`size-2 rounded-full ${error ? "bg-red-500" : "bg-emerald-500"}`} />{error ? "Unavailable" : "Live"}</div><Button variant="outline" size="sm" onClick={reload} disabled={loading}><RefreshCw className={`size-4 ${loading ? "animate-spin" : ""}`} />Refresh</Button></div>
      </header>
      {error && <div className="rounded-md border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700 dark:border-red-900 dark:bg-red-950 dark:text-red-300">{error}</div>}
      {loading && !summary ? <Skeleton className="h-24 w-full" /> : <section className="grid overflow-hidden rounded-md border bg-card sm:grid-cols-2 xl:grid-cols-4"><Metric label="Connected accounts" value={String(runtime?.activeAccounts ?? 0)} detail={`${summary?.accountCount ?? 0} configured`} icon={Wifi} /><Metric label="Messages today" value={String(summary?.messagesToday ?? 0)} detail="UTC day" icon={MessageCircle} /><Metric label="Queue backlog" value={String((summary?.inboxBacklog ?? 0) + (summary?.outboxBacklog ?? 0))} detail="inbox + outbox" icon={Inbox} /><Metric label="Failures" value={String((summary?.interruptedCount ?? 0) + (summary?.deadCount ?? 0))} detail="needs attention" icon={ShieldCheck} /></section>}

      <Tabs defaultValue="accounts" className="gap-4">
        <TabsList className="h-auto w-full justify-start rounded-none border-b bg-transparent p-0">
          {[["accounts", "Accounts"], ["messages", "Messages"], ["failures", `Failures (${failures.total})`], ["runtime", "Runtime"]].map(([value, label]) => <TabsTrigger key={value} value={value} className="rounded-none border-b-2 border-transparent px-4 py-2.5 data-[state=active]:border-foreground data-[state=active]:bg-transparent data-[state=active]:shadow-none">{label}</TabsTrigger>)}
        </TabsList>

        <TabsContent value="accounts" className="space-y-4">
          <div><h2 className="text-sm font-semibold">Channel accounts</h2><p className="text-xs text-muted-foreground">Connection, lease ownership, and queue state.</p></div>
          {accounts.length === 0 ? <EmptyState title="No channel accounts" detail="Connect a Channel from an Agent configuration page." /> : <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">{accounts.map((account) => <div key={account.id} className="flex min-h-64 flex-col rounded-md border bg-card p-4"><div className="flex items-start justify-between"><div className="flex size-10 items-center justify-center rounded-md bg-emerald-600 text-white"><MessageCircle className="size-5" /></div><StatusBadge status={account.clusterStatus} /></div><div className="mt-5"><p className="text-sm font-semibold capitalize">{account.type}</p><p className="mt-0.5 truncate text-xs text-muted-foreground">{account.displayName || account.accountId} · Agent {account.agentId}</p></div><dl className="mt-4 grid grid-cols-[92px_minmax(0,1fr)] gap-x-3 gap-y-2 border-t pt-3 text-[11px]"><dt className="text-muted-foreground">Adapter</dt><dd className="truncate font-mono">{account.adapterStatus || "Not reported"}</dd><dt className="text-muted-foreground">Owner Pod</dt><dd title={account.ownerId} className="truncate font-mono">{account.ownerId || "Not reported"}</dd><dt className="text-muted-foreground">Heartbeat</dt><dd>{formatTimestamp(account.lastHeartbeatAt)}</dd><dt className="text-muted-foreground">Last message</dt><dd>{formatTimestamp(account.lastMessageAt)}</dd></dl>{account.lastError && <p className="mt-3 truncate text-xs text-red-600" title={account.lastError}>{account.lastError}</p>}<div className="mt-auto flex flex-wrap gap-x-3 gap-y-1 pt-4 text-[11px] text-muted-foreground"><span>{account.leaseActive ? "Lease active" : "Lease inactive"}</span><span>Inbox {account.inboxBacklog}</span><span>Outbox {account.outboxBacklog}</span></div></div>)}</div>}
        </TabsContent>

        <TabsContent value="messages" className="space-y-4">
          <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between"><div><h2 className="text-sm font-semibold">Message trace</h2><p className="text-xs text-muted-foreground">Follow persisted Inbox, Agent Run, and Outbox stages.</p></div><div className="relative w-full sm:w-72"><Search className="absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" /><Input value={query} onChange={(event) => { setQuery(event.target.value); setSelected(null); }} placeholder="Search ID, run, or content" className="h-9 pl-9" /></div></div>
          <MessagesView messages={messages.items} selected={selected} onSelect={selectMessage} />
        </TabsContent>

        <TabsContent value="failures" className="space-y-4">
          <div><h2 className="text-sm font-semibold">Failure inspection</h2><p className="text-xs text-muted-foreground">Read-only in M1. Retry controls arrive after idempotency protection.</p></div>
          {failures.items.length === 0 ? <EmptyState title="No failed messages" detail="Interrupted and dead messages will appear here." /> : <div className="overflow-hidden rounded-md border bg-card">{failures.items.map((message) => <button key={message.id} type="button" onClick={() => selectMessage(message)} className="grid w-full gap-3 border-b p-4 text-left last:border-b-0 md:grid-cols-[minmax(0,1fr)_140px_100px_auto] md:items-center"><div className="min-w-0"><div className="flex items-center gap-2"><p className="truncate font-mono text-xs">{message.id}</p><StatusBadge status={message.outboxStatus || message.inboxStatus} /></div><p className="mt-1 truncate text-xs text-muted-foreground">{message.lastError || "No error detail recorded"}</p></div><p className="truncate text-xs text-muted-foreground">{message.type} / {message.displayName}</p><p className="text-xs text-muted-foreground">{message.attempts} attempts</p><ChevronRight className="size-4 text-muted-foreground" /></button>)}</div>}
        </TabsContent>

        <TabsContent value="runtime" className="space-y-4">
          <div><h2 className="text-sm font-semibold">Runtime health</h2><p className="text-xs text-muted-foreground">Current process roles, durable backlog, and active leases.</p></div>
          <div className="grid gap-4 lg:grid-cols-3">{[
            { icon: Server, title: "Channel bus", value: runtime?.bus || "unknown", detail: runtime?.status || "Unavailable" },
            { icon: Bot, title: "Process roles", value: runtime?.roles.join(", ") || "none", detail: `${runtime?.activeAccounts ?? 0} active accounts` },
            { icon: Database, title: "Durable queues", value: `${(runtime?.inboxBacklog ?? 0) + (runtime?.outboxBacklog ?? 0)} queued`, detail: `${runtime?.inboxBacklog ?? 0} inbox · ${runtime?.outboxBacklog ?? 0} outbox` },
          ].map((item) => <div key={item.title} className="rounded-md border bg-card p-4"><div className="flex items-start justify-between"><div className="flex size-9 items-center justify-center rounded-md bg-muted"><item.icon className="size-4" /></div><StatusBadge status={runtime?.status || "Unavailable"} /></div><p className="mt-5 text-sm font-semibold">{item.title}</p><p className="mt-1 break-words font-mono text-xs text-muted-foreground">{item.value}</p><p className="mt-4 border-t pt-3 text-xs text-muted-foreground">{item.detail}</p></div>)}</div>
          <div className="rounded-md border bg-card p-5"><div className="flex items-center justify-between"><div><p className="text-sm font-semibold">Active leases</p><p className="text-xs text-muted-foreground">One ingress owner per Channel binding</p></div><Clock3 className="size-4 text-muted-foreground" /></div><div className="mt-4 divide-y">{accounts.map((account) => <div key={account.id} className="flex items-center justify-between gap-4 py-3"><div className="min-w-0"><p className="truncate font-mono text-xs">{account.type}:{account.accountId}</p><p className="mt-1 truncate text-[11px] text-muted-foreground">{account.ownerId || "No runtime owner"} · {account.adapterStatus || "No heartbeat"}</p></div><StatusBadge status={account.clusterStatus} /></div>)}</div></div>
        </TabsContent>
      </Tabs>
    </div>
  );
}
