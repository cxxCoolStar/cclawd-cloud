// MCP stdio 测试服务器（最小 JSON-RPC：initialize / tools/list / tools/call）
// 每行一个 JSON 消息（MCP stdio 传输为 newline-delimited JSON）
const readline = require("readline");

const TOOLS = [
  {
    name: "echo",
    description: "Echo the input text back",
    inputSchema: {
      type: "object",
      properties: { text: { type: "string", description: "text to echo" } },
      required: ["text"],
    },
  },
];

const rl = readline.createInterface({ input: process.stdin, terminal: false });

function reply(id, result) {
  process.stdout.write(JSON.stringify({ jsonrpc: "2.0", id, result }) + "\n");
}

rl.on("line", (line) => {
  if (!line.trim()) return;
  let msg;
  try {
    msg = JSON.parse(line);
  } catch {
    return;
  }
  if (msg.method === "initialize") {
    reply(msg.id, {
      protocolVersion: msg.params?.protocolVersion || "2024-11-05",
      capabilities: { tools: {} },
      serverInfo: { name: "test-server", version: "0.0.1" },
    });
  } else if (msg.method === "tools/list") {
    reply(msg.id, { tools: TOOLS });
  } else if (msg.method === "tools/call") {
    const text = msg.params?.arguments?.text ?? "";
    reply(msg.id, {
      content: [{ type: "text", text: "echo: " + text }],
      isError: false,
    });
  }
  // notifications/initialized 等通知无需响应
});
