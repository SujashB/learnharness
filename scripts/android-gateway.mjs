import https from "node:https";
import http from "node:http";
import { randomBytes, randomUUID, timingSafeEqual, X509Certificate } from "node:crypto";
import { createWriteStream, existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { unlink } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";
import { Transform } from "node:stream";
import { pipeline } from "node:stream/promises";

export const MAX_UPLOAD_BYTES = 25 * 1024 * 1024;
const HOP = new Set(["connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade"]);

export function authorized(value, token) {
	if (typeof value !== "string" || typeof token !== "string" || token.length < 64) return false;
	const a = Buffer.from(value), b = Buffer.from(`Bearer ${token}`);
	return a.length === b.length && timingSafeEqual(a, b);
}

export function uploadFilename(value) {
	let name;
	try { name = decodeURIComponent(value ?? ""); } catch { throw new Error("Invalid filename"); }
	// Retain the extension so existing agent tools can identify the uploaded file.
	name = name.split(/[\\/]/).at(-1).replace(/[<>:"|?*\x00-\x1f\x7f]/g, "_").replace(/[. ]+$/, "").slice(-160);
	if (!name || name === "." || name === "..") throw new Error("Invalid filename");
	return `${randomUUID()}-${name}`;
}

function json(response, status, body) {
	response.writeHead(status, { "Content-Type": "application/json", "Cache-Control": "no-store" });
	response.end(JSON.stringify(body));
}

/** Only this authenticated TLS listener is exposed to the LAN. Next stays on loopback. */
export function createGateway({ key, cert, token, backendPort = 3000, uploadDir }) {
	if (!/^[a-f0-9]{64}$/.test(token)) throw new Error("A 256-bit access token is required");
	return https.createServer({ key, cert, minVersion: "TLSv1.2", maxHeaderSize: 32 * 1024 }, async (req, res) => {
		if (!authorized(req.headers.authorization, token)) {
			json(res, 401, { error: "Pair this device with the PC first." });
			req.resume();
			return;
		}
		if (!req.url?.startsWith("/") || req.url.startsWith("//")) return json(res, 400, { error: "Invalid path" });
		if (req.url === "/__android/health" && req.method === "GET") return json(res, 200, { app: "learnharness", protocol: 1 });
		if (req.url === "/__android/upload" && req.method === "POST") {
			let destination;
			try {
				if (Number(req.headers["content-length"]) > MAX_UPLOAD_BYTES) { req.resume(); return json(res, 413, { error: "Files must be 25 MiB or smaller." }); }
				destination = path.join(uploadDir, uploadFilename(req.headers["x-filename"]));
				mkdirSync(uploadDir, { recursive: true, mode: 0o700 });
				let received = 0;
				const limit = new Transform({ transform(chunk, _encoding, callback) {
					received += chunk.length;
					callback(received > MAX_UPLOAD_BYTES ? new Error("File exceeds 25 MiB") : null, chunk);
				} });
				await pipeline(req, limit, createWriteStream(destination, { flags: "wx", mode: 0o600 }));
				return json(res, 201, { path: destination });
			} catch {
				if (destination) await unlink(destination).catch(() => {});
				if (!res.destroyed) json(res, 400, { error: "Upload failed. Check the file size and retry." });
				return;
			}
		}
		// Rewrite the addressed origin only after authenticating the native relay.
		// Do not forward the tablet credential, forwarding headers, or hop headers to Next.
		const headers = {};
		for (const [name, value] of Object.entries(req.headers)) {
			if (!HOP.has(name) && !["authorization", "host", "origin", "referer", "cookie", "forwarded"].includes(name) && !name.startsWith("x-forwarded-") && !name.startsWith("x-learnharness-")) headers[name] = value;
		}
		headers.host = `127.0.0.1:${backendPort}`;
		if (req.headers.origin) headers.origin = `http://${headers.host}`;
		if (req.headers.referer) headers.referer = `http://${headers.host}/`;
		const upstream = http.request({ hostname: "127.0.0.1", port: backendPort, path: req.url, method: req.method, headers }, reply => {
			const outgoing = {};
			for (const [name, value] of Object.entries(reply.headers)) if (!HOP.has(name) && name !== "set-cookie") outgoing[name] = value;
			// Prevent an absolute backend redirect from escaping the authenticated relay.
			if (typeof outgoing.location === "string" && outgoing.location.startsWith(`http://127.0.0.1:${backendPort}/`)) outgoing.location = outgoing.location.slice(`http://127.0.0.1:${backendPort}`.length);
			res.writeHead(reply.statusCode ?? 502, outgoing);
			reply.pipe(res); // SSE and streamed responses must never be buffered.
			reply.on("error", () => res.destroy());
		});
		upstream.on("error", () => { if (!res.headersSent) json(res, 502, { error: "The PC backend is not running." }); else res.destroy(); });
		req.on("aborted", () => upstream.destroy());
		res.on("close", () => upstream.destroy());
		req.pipe(upstream);
	});
}

export function pairingConfig(directory, openssl) {
	mkdirSync(directory, { recursive: true, mode: 0o700 });
	const keyPath = path.join(directory, "server.key"), certPath = path.join(directory, "server.crt"), tokenPath = path.join(directory, "device-token");
	if (!existsSync(keyPath) || !existsSync(certPath)) {
		const result = spawnSync(openssl, ["req", "-x509", "-newkey", "rsa:3072", "-sha256", "-nodes", "-keyout", keyPath, "-out", certPath, "-days", "365", "-subj", "/CN=LearnHarness PC"], { stdio: "pipe" });
		if (result.error || result.status !== 0) throw new Error(`Certificate generation failed: ${result.error?.message ?? result.stderr}`);
	}
	if (!existsSync(tokenPath)) writeFileSync(tokenPath, randomBytes(32).toString("hex"), { mode: 0o600, flag: "wx" });
	const key = readFileSync(keyPath), cert = readFileSync(certPath), token = readFileSync(tokenPath, "utf8").trim();
	const fingerprint = new X509Certificate(cert).fingerprint256.replaceAll(":", "").toLowerCase();
	return { key, cert, token, fingerprint };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
	const root = path.resolve(import.meta.dirname, "..");
	const directory = path.join(root, ".android-pairing");
	const openssl = process.env.OPENSSL ?? (process.platform === "win32" ? "C:/Program Files/Git/usr/bin/openssl.exe" : "openssl");
	const config = pairingConfig(directory, openssl);
	const host = process.env.LH_ANDROID_HOST;
	if (!host) throw new Error("Set LH_ANDROID_HOST to your PC's Wi-Fi IPv4 address (or 127.0.0.1 for USB).");
	const port = Number(process.env.LH_ANDROID_PORT ?? 3443);
	const pairing = { version: 1, url: `https://${host}:${port}`, token: config.token, fingerprint: config.fingerprint };
	// Pairing is sensitive. Save to an ignored file rather than logging credentials.
	writeFileSync(path.join(directory, "pairing.txt"), JSON.stringify(pairing), { mode: 0o600 });
	const gateway = createGateway({ ...config, backendPort: Number(process.env.LH_ANDROID_BACKEND_PORT ?? 3000), uploadDir: path.resolve(process.env.LH_DATA_DIR ?? path.join(root, "data"), "android-uploads") });
	gateway.listen(port, process.env.LH_ANDROID_BIND ?? host, () => console.log(`Android gateway: https://${host}:${port}\nPairing string: ${path.join(directory, "pairing.txt")}\nKeep the PC backend running on loopback. Treat the pairing string as a password.`));
}
