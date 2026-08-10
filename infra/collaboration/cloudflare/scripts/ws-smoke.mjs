import WebSocket from "ws";

const url = process.env.NORFOLD_COLLAB_WS;
const token = process.env.NORFOLD_COLLAB_TOKEN;
if (!url || !token) {
  console.error("Set NORFOLD_COLLAB_WS and NORFOLD_COLLAB_TOKEN.");
  process.exit(2);
}

function connect(name) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url, { headers: { Authorization: `Bearer ${token}` } });
    const timer = setTimeout(() => {
      try { ws.terminate(); } catch {}
      reject(new Error(`${name}: timeout`));
    }, 10_000);
    ws.once("open", () => { clearTimeout(timer); resolve(ws); });
    ws.once("error", error => { clearTimeout(timer); reject(new Error(`${name}: ${error.message}`)); });
  });
}

function receive(ws, expectedType, name) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`${name} did not receive ${expectedType}`)), 10_000);
    ws.on("message", raw => {
      const data = JSON.parse(raw.toString());
      if (data.type === expectedType) { clearTimeout(timer); resolve(data); }
    });
  });
}

const [a, b, c] = await Promise.all([connect("A"), connect("B"), connect("C")]);
const receiveB = receive(b, "smoke.preview", "B");
const receiveC = receive(c, "smoke.preview", "C");
const message = {
  type: "smoke.preview",
  seq: 1,
  target: "object-1",
  payload: { x: 12, y: 34 },
  clientSentAt: Date.now(),
};
a.send(JSON.stringify(message));
const [onB, onC] = await Promise.all([receiveB, receiveC]);
console.log(JSON.stringify({ ok: true, clients: 3, received: [onB, onC] }, null, 2));
[a, b, c].forEach(socket => socket.close());
