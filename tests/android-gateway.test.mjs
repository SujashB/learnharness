import { test } from "node:test";
import assert from "node:assert/strict";
import https from "node:https";
import http from "node:http";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { once } from "node:events";
import { createGateway, pairingConfig, authorized, uploadFilename, MAX_UPLOAD_BYTES } from "../scripts/android-gateway.mjs";

test("pairing requires the full token; filenames cannot escape uploads", () => {
	const token = "a".repeat(64);
	assert.equal(authorized(`Bearer ${token}`, token), true);
	for (const supplied of [undefined, token, "Bearer x", `Bearer ${"b".repeat(64)}`]) assert.equal(authorized(supplied, token), false);
	assert.equal(authorized("Bearer x", "x"), false);
	assert.match(uploadFilename(encodeURIComponent("../../sample.pdf")), /^[a-f0-9-]+-sample\.pdf$/);
	assert.throws(() => uploadFilename("%broken"));
	assert.throws(() => uploadFilename(".."));
});

test("TLS gateway gates every route, preserves request bodies and streams, and stores bounded uploads", async () => {
	const directory = mkdtempSync(path.join(tmpdir(), "lh-android-test-"));
	let backend, gateway;
	try {
		const config = pairingConfig(directory, process.env.OPENSSL ?? (process.platform === "win32" ? "C:/Program Files/Git/usr/bin/openssl.exe" : "openssl"));
		backend = http.createServer(async (req, res) => {
			if (req.url === "/stream") { res.writeHead(200, { "Content-Type": "text/event-stream" }); res.write("data: first\n\n"); setTimeout(() => res.end("data: last\n\n"), 150); return; }
			const chunks = []; for await (const chunk of req) chunks.push(chunk);
			res.setHeader("Content-Type", "application/json");
			res.end(JSON.stringify({ method: req.method, headers: req.headers, body: Buffer.concat(chunks).toString() }));
		});
		backend.listen(0, "127.0.0.1"); await once(backend, "listening");
		gateway = createGateway({ ...config, backendPort: backend.address().port, uploadDir: path.join(directory, "uploads") });
		gateway.listen(0, "127.0.0.1"); await once(gateway, "listening");
		const send = (url, { method = "GET", headers = {}, body = "", auth = true, onData } = {}) => new Promise((resolve, reject) => {
			// Fixture-only TLS client: trust the generated cert but skip its localhost name mismatch.
			const req = https.request({ hostname: "127.0.0.1", port: gateway.address().port, path: url, method, ca: config.cert, checkServerIdentity: () => undefined,
				headers: { ...(auth ? { authorization: `Bearer ${config.token}` } : {}), ...headers } }, res => {
				const chunks = []; res.on("data", chunk => { chunks.push(chunk); onData?.(chunk.toString()); });
				res.on("end", () => resolve({ status: res.statusCode, body: Buffer.concat(chunks).toString() })); res.on("error", reject);
			}); req.on("error", reject); req.end(body);
		});
		for (const route of ["/", "/api/keys", "/_next/static/a.js", "/__android/health", "/api/plugins/x/documents/markdown"]) assert.equal((await send(route, { auth: false })).status, 401);
		assert.equal((await send("/__android/health")).status, 200);
		const response = await send("/api/chat", { method: "POST", headers: { origin: "http://127.0.0.1:12345", "content-type": "application/json", "x-forwarded-host": "evil.example", cookie: "secret=x" }, body: '{"message":"hello"}' });
		assert.equal(response.status, 200);
		const echoed = JSON.parse(response.body);
		assert.equal(echoed.body, '{"message":"hello"}');
		assert.equal(echoed.headers.origin, `http://127.0.0.1:${backend.address().port}`);
		assert.equal(echoed.headers.authorization, undefined);
		assert.equal(echoed.headers.cookie, undefined);
		assert.equal(echoed.headers["x-forwarded-host"], undefined);
		const events = []; await send("/stream", { onData: chunk => events.push(chunk) });
		assert.ok(events.length >= 2, "SSE must arrive before the upstream closes");
		const upload = await send("/__android/upload", { method: "POST", headers: { "x-filename": "sample.txt", "content-type": "application/octet-stream" }, body: "tablet attachment" });
		assert.equal(upload.status, 201);
		assert.equal(readFileSync(JSON.parse(upload.body).path, "utf8"), "tablet attachment");
		assert.equal((await send("/__android/upload", { method: "POST", headers: { "x-filename": "large.bin", "content-length": String(MAX_UPLOAD_BYTES + 1) } })).status, 413);
	} finally {
		gateway?.closeAllConnections(); backend?.closeAllConnections();
		if (gateway) await new Promise(resolve => gateway.close(resolve));
		if (backend) await new Promise(resolve => backend.close(resolve));
		rmSync(directory, { recursive: true, force: true });
	}
});
