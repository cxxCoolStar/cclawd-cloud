// MCP Streamable HTTP 测试服务器（最小实现：POST JSON-RPC → SSE 响应）
const http = require("http");

const TOOLS = [
  {
    name: "upper",
    description: "Uppercase the input text",
    inputSchema: {
      type: "object",
      properties: { text: { type: "string" } },
      required: ["text"],
    },
  },
];

function handle(msg) {
  if (msg.method === "initialize") {
    return {
      protocolVersion: msg.params?.protocolVersion || "2024-11-05",
      capabilities: { tools: {} },
      serverInfo: { name: "http-test-server", version: "0.0.1" },
    };
  }
  if (msg.method === "tools/list") {
    return { tools: TOOLS };
  }
  if (msg.method === "tools/call") {
    const text = msg.params?.arguments?.text ?? "";
    return { content: [{ type: "text", text: text.toUpperCase() }], isError: false };
  }
  return null;
}

const server = http.createServer((req, res) => {
  if (req.method !== "POST") {
    res.writeHead(405).end();
    return;
  }
  let body = "";
  req.on("data", (chunk) => (body += chunk));
  req.on("end", () => {
    let msg;
    try {
      msg = JSON.parse(body);
    } catch {
      res.writeHead(400).end("bad json");
      return;
    }
    // 通知无 id：202 Accepted 无响应体
    if (msg.id === undefined || msg.id === null) {
      res.writeHead(202).end();
      return;
    }
    const result = handle(msg);
    const payload = JSON.stringify({ jsonrpc: "2.0", id: msg.id, result });
    res.writeHead(200, { "Content-Type": "text/event-stream" });
    res.end(`event: message\ndata: ${payload}\n\n`);
  });
});

server.listen(Number(process.env.MCP_PORT || 0), "127.0.0.1", () => {
  console.log("MCP_PORT=" + server.address().port);
});
