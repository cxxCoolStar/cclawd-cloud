export interface ChannelSummary {
  accountCount: number;
  messagesToday: number;
  inboxBacklog: number;
  outboxBacklog: number;
  interruptedCount: number;
  deadCount: number;
}

export interface ChannelAccount {
  id: string;
  agentId: string;
  type: string;
  accountId: string;
  displayName: string;
  enabled: boolean;
  sharedIdentity: boolean;
  clusterStatus: string;
  adapterStatus: string;
  ownerId: string;
  lastHeartbeatAt?: string;
  lastMessageAt?: string;
  lastError: string;
  leaseActive: boolean;
  inboxBacklog: number;
  outboxBacklog: number;
  updatedAt: string;
}

export interface ChannelMessage {
  id: string;
  type: string;
  accountId: string;
  displayName: string;
  senderId: string;
  text: string;
  inboxStatus: string;
  outboxStatus?: string;
  runId?: string;
  attempts: number;
  lastError?: string;
  createdAt: string;
  updatedAt: string;
  sentAt?: string;
}

export interface ChannelTraceStep {
  stage: string;
  status: string;
  detail: string;
  startedAt?: string;
  completedAt?: string;
}

export interface ChannelMessageDetail {
  message: ChannelMessage;
  timeline: ChannelTraceStep[];
}

export interface ChannelMessagePage {
  items: ChannelMessage[];
  total: number;
  page: number;
  pageSize: number;
  hasNext: boolean;
}

export interface ChannelRuntime {
  bus: string;
  roles: string[];
  configuredAccounts: number;
  activeAccounts: number;
  inboxBacklog: number;
  outboxBacklog: number;
  status: string;
}
