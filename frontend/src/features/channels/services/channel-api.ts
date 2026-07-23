import { apiFetch } from "@/lib/api";
import type {
  ChannelAccount,
  ChannelMessageDetail,
  ChannelMessagePage,
  ChannelRuntime,
  ChannelSummary,
} from "../types";

interface ApiResult<T> {
  code: string;
  message?: string;
  data?: T;
}

async function request<T>(url: string): Promise<T> {
  const response = await apiFetch(url);
  const result = (await response.json()) as ApiResult<T>;
  if (!response.ok || result.code !== "0" || result.data === undefined) {
    throw new Error(result.message || `Channel API failed (${response.status})`);
  }
  return result.data;
}

export function getChannelSummary() {
  return request<ChannelSummary>("/api/channels/summary");
}

export function getChannelAccounts() {
  return request<ChannelAccount[]>("/api/channels/accounts");
}

export function getChannelMessages(keyword = "") {
  const query = new URLSearchParams({ page: "1", pageSize: "50" });
  if (keyword.trim()) query.set("keyword", keyword.trim());
  return request<ChannelMessagePage>(`/api/channels/messages?${query}`);
}

export function getChannelFailures() {
  return request<ChannelMessagePage>("/api/channels/failures?page=1&pageSize=50");
}

export function getChannelMessage(messageId: string) {
  return request<ChannelMessageDetail>(`/api/channels/messages/${encodeURIComponent(messageId)}`);
}

export function getChannelRuntime() {
  return request<ChannelRuntime>("/api/channels/runtime");
}
