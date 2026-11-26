import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { runInNewContext } from "node:vm";

const page = readFileSync(new URL("../../main/resources/static/admin-payment-monitor.html", import.meta.url), "utf8");
const script = page.match(/<script>([\s\S]+?)<\/script>/)[1];
const settle = () => new Promise((resolve) => setImmediate(resolve));

function element() {
  return {
    value: "", children: [], textContent: "",
    addEventListener() {},
    appendChild(node) { this.children.push(node); },
    prepend(node) { this.children.unshift(node); },
    replaceChildren() { this.children = []; }
  };
}

function openMonitor() {
  const elements = new Map();
  const sockets = [];
  const timers = new Set();
  const locks = [];
  const server = { owner: "session-A", refreshedOwner: "session-A", refreshes: 0 };
  const window = {
    location: { origin: "https://monitor.example" },
    setInterval: (callback) => { timers.add(callback); return callback; }
  };
  const document = {
    getElementById(id) {
      if (!elements.has(id)) elements.set(id, element());
      return elements.get(id);
    },
    createElement: element
  };

  class Socket {
    constructor() { sockets.push(this); }
    addEventListener(type, callback) { if (type === "close") this.onClose = callback; }
    close(code = 1000) { this.onClose({ code }); }
    connect(headers, connected) { this.expected = headers["X-Expected-Session"]; connected(); }
    subscribe(topic) { this.subscribed = topic; }
    heartbeat = {};
  }
  async function request(url, options) {
    assert.equal(options.credentials, "include");
    const path = new URL(url).pathname;
    if (path === "/api/auth/session") {
      assert.equal(options.cache, "no-store");
      return new Response(server.owner, { status: server.owner ? 200 : 401 });
    }
    if (path === "/api/auth/reissue") {
      assert.equal(options.method, "POST");
      server.refreshes++;
      server.owner = server.refreshedOwner;
      return new Response(null, { status: server.owner ? 200 : 401 });
    }
    assert.equal(path, "/api/v1/admin/payment/requests");
    return Response.json({ data: [{ requestId: 1 }] });
  }
  runInNewContext(script, {
    window, document, fetch: request, SockJS: Socket, Stomp: { over: (socket) => socket },
    navigator: { locks: { request: (name, callback) => { locks.push(name); return callback(); } } },
    setTimeout: (callback) => { timers.add(callback); return callback; },
    clearTimeout: (callback) => timers.delete(callback),
    clearInterval: (callback) => timers.delete(callback)
  });
  return { sockets, server, timers, locks, elements };
}

test("policy close clears orders and does not subscribe again after account switch", async () => {
  for (const code of [4403, 1008]) {
    const { sockets, server, timers, elements } = openMonitor();
    await settle();
    assert.equal(sockets[0].expected, "session-A");
    assert.equal(sockets[0].subscribed, "/topic/admin/orders");
    assert.equal(elements.get("summary").textContent, "대기 요청 1건");
    server.owner = "session-B";
    sockets[0].close(code);
    await settle();
    assert.equal(sockets.length, 1);
    assert.equal(timers.size, 0);
    assert.equal(server.refreshes, 0);
    assert.equal(elements.get("summary").textContent, "대기 요청 0건");
  }
});

test("access expiry rotates once and reconnects with the original session pinned", async () => {
  const { sockets, server, locks } = openMonitor();
  await settle();
  server.owner = null;
  sockets[0].close(4401);
  await settle();
  assert.equal(server.refreshes, 1);
  assert.deepEqual(locks, ["comatching-auth-refresh"]);
  assert.deepEqual(sockets.map((socket) => socket.expected), ["session-A", "session-A"]);
  assert.equal(sockets[1].subscribed, "/topic/admin/orders");
});

test("expiry cannot reconnect with a replaced or revoked login", async () => {
  for (const [owner, refreshedOwner] of [["session-B", "session-B"], [null, "session-B"], [null, null]]) {
    const { sockets, server, timers, elements } = openMonitor();
    await settle();
    Object.assign(server, { owner, refreshedOwner });
    sockets[0].close(4401);
    await settle();
    assert.equal(sockets.length, 1);
    assert.equal(timers.size, 0);
    assert.equal(elements.get("connState").textContent, "DISCONNECTED");
  }
});

test("a transport retry checks the session again before opening another socket", async () => {
  const { sockets, server, timers } = openMonitor();
  await settle();
  sockets[0].close(1006);
  assert.equal(timers.size, 1);
  server.owner = "session-B";
  const [retry] = timers;
  timers.delete(retry);
  retry();
  await settle();
  assert.equal(sockets.length, 1);
  assert.equal(timers.size, 0);
});
