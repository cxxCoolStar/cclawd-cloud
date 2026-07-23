"use client";

import { useCallback, useEffect, useState } from "react";
import {
  getChannelAccounts,
  getChannelFailures,
  getChannelMessages,
  getChannelRuntime,
  getChannelSummary,
} from "../services/channel-api";
import type { ChannelAccount, ChannelMessagePage, ChannelRuntime, ChannelSummary } from "../types";

const emptyPage: ChannelMessagePage = { items: [], total: 0, page: 1, pageSize: 50, hasNext: false };

export function useChannelConsole(keyword: string) {
  const [summary, setSummary] = useState<ChannelSummary | null>(null);
  const [accounts, setAccounts] = useState<ChannelAccount[]>([]);
  const [messages, setMessages] = useState<ChannelMessagePage>(emptyPage);
  const [failures, setFailures] = useState<ChannelMessagePage>(emptyPage);
  const [runtime, setRuntime] = useState<ChannelRuntime | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const load = useCallback(async (showLoading = true) => {
    if (showLoading) setLoading(true);
    try {
      const [nextSummary, nextAccounts, nextMessages, nextFailures, nextRuntime] = await Promise.all([
        getChannelSummary(),
        getChannelAccounts(),
        getChannelMessages(keyword),
        getChannelFailures(),
        getChannelRuntime(),
      ]);
      setSummary(nextSummary);
      setAccounts(nextAccounts);
      setMessages(nextMessages);
      setFailures(nextFailures);
      setRuntime(nextRuntime);
      setError("");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not load Channel operations data");
    } finally {
      setLoading(false);
    }
  }, [keyword]);

  useEffect(() => {
    const timer = window.setTimeout(() => void load(false), keyword ? 250 : 0);
    return () => window.clearTimeout(timer);
  }, [keyword, load]);

  return { summary, accounts, messages, failures, runtime, loading, error, reload: () => load(true) };
}
