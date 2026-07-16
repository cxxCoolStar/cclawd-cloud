"use client";

import { useEffect, useState } from "react";
import { getAgents } from "@/lib/api";

// useAgentName resolves an agent id to its display name. While the agent
// list is loading, or if the id isn't in the list, it returns the id so
// page chrome doesn't flicker between empty and resolved states. Pass an
// empty string to skip the fetch entirely.
export function useAgentName(agentId: string): string {
  const [name, setName] = useState<string>(agentId);
  useEffect(() => {
    if (!agentId) {
      setName("");
      return;
    }
    let aborted = false;
    setName(agentId);
    getAgents()
      .then((list) => {
        if (aborted) return;
        const me = list.find((a) => a.id === agentId);
        if (me?.name) setName(me.name);
      })
      .catch(() => {
        // leave name as the id fallback
      });
    return () => {
      aborted = true;
    };
  }, [agentId]);
  return name;
}
