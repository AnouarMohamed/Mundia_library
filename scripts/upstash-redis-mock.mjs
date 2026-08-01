import http from "node:http";
import process from "node:process";

const HOST = "127.0.0.1";
const PORT = Number(process.env.UPSTASH_MOCK_PORT || 8079);
const TOKEN = process.env.UPSTASH_REDIS_TOKEN;

if (!TOKEN) {
  throw new Error("UPSTASH_REDIS_TOKEN is required by the local Redis test double");
}

const values = new Map();

function readEntry(key) {
  const entry = values.get(key);
  if (!entry) return null;
  if (entry.expiresAt !== null && entry.expiresAt <= Date.now()) {
    values.delete(key);
    return null;
  }
  return entry;
}

function writeEntry(key, value, ttlMs = null) {
  values.set(key, {
    value,
    expiresAt: ttlMs === null ? null : Date.now() + ttlMs,
  });
}

function execute(command) {
  if (!Array.isArray(command) || command.length === 0) {
    throw new Error("command must be a non-empty array");
  }

  const operation = String(command[0]).toLowerCase();
  if (operation === "get") {
    return readEntry(String(command[1]))?.value ?? null;
  }

  if (operation === "set") {
    const key = String(command[1]);
    const value = command[2];
    let ttlMs = null;
    for (let index = 3; index < command.length - 1; index += 1) {
      const option = String(command[index]).toLowerCase();
      if (option === "ex") ttlMs = Number(command[index + 1]) * 1000;
      if (option === "px") ttlMs = Number(command[index + 1]);
    }
    writeEntry(key, value, ttlMs);
    return "OK";
  }

  if (operation === "del") {
    let removed = 0;
    for (const key of command.slice(1)) {
      if (values.delete(String(key))) removed += 1;
    }
    return removed;
  }

  if (operation === "scan") {
    return ["0", []];
  }

  if (operation === "evalsha" || operation === "eval") {
    const keyCountIndex = 2;
    const keyCount = Number(command[keyCountIndex]);
    const firstKeyIndex = keyCountIndex + 1;
    const args = command.slice(firstKeyIndex + keyCount);
    const limit = Number(args[0]);

    if (!Number.isFinite(limit)) {
      throw new Error("unsupported rate-limit script invocation");
    }

    if (args.length >= 3) {
      // Performance jobs measure application capacity, not the public abuse
      // threshold. Return a valid fixed-window response without letting the
      // generator throttle itself after 200 requests from one loopback IP.
      return [1, limit];
    }

    return [Math.max(0, limit - 1), limit];
  }

  throw new Error(`unsupported command: ${operation}`);
}

async function readJson(request) {
  const chunks = [];
  for await (const chunk of request) chunks.push(chunk);
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

const server = http.createServer(async (request, response) => {
  if (request.method === "GET" && request.url === "/health") {
    response.writeHead(200, { "content-type": "application/json" });
    response.end('{"ok":true}');
    return;
  }

  if (
    request.method !== "POST" ||
    request.headers.authorization !== `Bearer ${TOKEN}`
  ) {
    response.writeHead(401, { "content-type": "application/json" });
    response.end('{"error":"unauthorized"}');
    return;
  }

  try {
    const body = await readJson(request);
    const result = request.url === "/pipeline"
      ? body.map((command) => ({ result: execute(command) }))
      : { result: execute(body) };
    response.writeHead(200, { "content-type": "application/json" });
    response.end(JSON.stringify(result));
  } catch (error) {
    response.writeHead(200, { "content-type": "application/json" });
    response.end(JSON.stringify({
      error: error instanceof Error ? error.message : "mock command failed",
    }));
  }
});

server.listen(PORT, HOST, () => {
  console.log(`Upstash Redis test double listening on http://${HOST}:${PORT}`);
});

for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, () => server.close(() => process.exit(0)));
}
