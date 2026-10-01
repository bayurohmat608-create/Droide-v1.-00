const MAX_FORM_BYTES = 8 * 1024;
const MAX_UPSTREAM_BYTES = 64 * 1024;
const DEFAULT_CALLBACK_URI = "com.baystudio.droide.oauth://github/callback";
const GITHUB_TOKEN_URL = "https://github.com/login/oauth/access_token";
const GITHUB_API_VERSION = "2026-03-10";
const EXCHANGE_PATH = "/oauth/github/exchange";
const REFRESH_PATH = "/oauth/github/refresh";
const REVOKE_PATH = "/oauth/github/revoke";

function configuredCallbackUri(env) {
  const raw = typeof env.GITHUB_REDIRECT_URI === "string" && env.GITHUB_REDIRECT_URI.trim()
    ? env.GITHUB_REDIRECT_URI.trim()
    : DEFAULT_CALLBACK_URI;
  if (raw === DEFAULT_CALLBACK_URI) return raw;
  try {
    const uri = new URL(raw);
    if (uri.protocol !== "https:" || !uri.hostname || (uri.port && uri.port !== "443")) return null;
    if (!uri.pathname || !uri.pathname.startsWith("/") || uri.username || uri.password || uri.search || uri.hash) return null;
    return raw;
  } catch {
    return null;
  }
}

function responseJson(status, body) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store, max-age=0",
      pragma: "no-cache",
      "x-content-type-options": "nosniff",
    },
  });
}

function validVerifier(value) {
  return typeof value === "string" && /^[A-Za-z0-9._~-]{43,128}$/.test(value);
}

function validCode(value) {
  return typeof value === "string" && value.length >= 1 && value.length <= 1024 && !/[\r\n\0]/.test(value);
}

function validToken(value) {
  return typeof value === "string" && value.length >= 20 && value.length <= 4096 && !/[\s\0]/.test(value);
}

function basicAuth(clientId, clientSecret) {
  return `Basic ${btoa(`${clientId}:${clientSecret}`)}`;
}

async function readBoundedUtf8(message, limit) {
  if (!message.body) return "";
  const reader = message.body.getReader();
  const decoder = new TextDecoder("utf-8", { fatal: true });
  const chunks = [];
  let bytes = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      bytes += value.byteLength;
      if (bytes > limit) {
        const error = new Error("body exceeds limit");
        error.code = "BODY_TOO_LARGE";
        throw error;
      }
      chunks.push(decoder.decode(value, { stream: true }));
    }
    chunks.push(decoder.decode());
    return chunks.join("");
  } catch (error) {
    try { await reader.cancel(); } catch {}
    throw error;
  } finally {
    reader.releaseLock();
  }
}

async function readForm(request) {
  const type = request.headers.get("content-type") || "";
  if (type.split(";", 1)[0].trim().toLowerCase() !== "application/x-www-form-urlencoded") {
    return { error: responseJson(415, { error: "unsupported_media_type" }) };
  }
  const declaredLength = Number(request.headers.get("content-length") || "0");
  if (Number.isFinite(declaredLength) && declaredLength > MAX_FORM_BYTES) {
    return { error: responseJson(413, { error: "request_too_large" }) };
  }
  try {
    const text = await readBoundedUtf8(request, MAX_FORM_BYTES);
    if (new TextEncoder().encode(text).byteLength > MAX_FORM_BYTES) {
      return { error: responseJson(413, { error: "request_too_large" }) };
    }
    return { form: new URLSearchParams(text) };
  } catch (error) {
    return { error: responseJson(error?.code === "BODY_TOO_LARGE" ? 413 : 400, {
      error: error?.code === "BODY_TOO_LARGE" ? "request_too_large" : "invalid_request",
    }) };
  }
}

function normalizedTokenResponse(payload) {
  if (!payload || typeof payload.access_token !== "string") return null;
  const out = {
    access_token: payload.access_token,
    token_type: typeof payload.token_type === "string" ? payload.token_type : "bearer",
  };
  if (typeof payload.scope === "string") out.scope = payload.scope;
  if (Number.isSafeInteger(payload.expires_in) && payload.expires_in > 0) out.expires_in = payload.expires_in;
  if (typeof payload.refresh_token === "string") out.refresh_token = payload.refresh_token;
  if (Number.isSafeInteger(payload.refresh_token_expires_in) && payload.refresh_token_expires_in > 0) {
    out.refresh_token_expires_in = payload.refresh_token_expires_in;
  }
  return out;
}

async function tokenRequest(upstreamBody) {
  let upstream;
  try {
    upstream = await fetch(GITHUB_TOKEN_URL, {
      method: "POST",
      redirect: "manual",
      headers: {
        accept: "application/json",
        "content-type": "application/x-www-form-urlencoded",
        "user-agent": "Droide-GitHub-Auth-Relay",
      },
      body: upstreamBody.toString(),
    });
  } catch {
    return responseJson(502, { error: "github_unreachable" });
  }

  let payload;
  try {
    payload = JSON.parse(await readBoundedUtf8(upstream, MAX_UPSTREAM_BYTES));
  } catch {
    return responseJson(502, { error: "invalid_github_response" });
  }
  const normalized = normalizedTokenResponse(payload);
  if (!upstream.ok || !normalized) {
    const safeError = typeof payload?.error === "string" ? payload.error.slice(0, 80) : "token_exchange_failed";
    return responseJson(upstream.status >= 400 && upstream.status < 500 ? 400 : 502, { error: safeError });
  }
  return responseJson(200, normalized);
}

async function exchange(form, env) {
  const clientId = form.get("client_id");
  const code = form.get("code");
  const verifier = form.get("code_verifier");
  const redirectUri = form.get("redirect_uri");
  const callbackUri = configuredCallbackUri(env);
  if (!callbackUri || clientId !== env.GITHUB_CLIENT_ID || !validCode(code) || !validVerifier(verifier) || redirectUri !== callbackUri) {
    return responseJson(400, { error: "invalid_request" });
  }
  return tokenRequest(new URLSearchParams({
    client_id: env.GITHUB_CLIENT_ID,
    client_secret: env.GITHUB_CLIENT_SECRET,
    code,
    redirect_uri: callbackUri,
    code_verifier: verifier,
  }));
}

async function refresh(form, env) {
  const clientId = form.get("client_id");
  const grantType = form.get("grant_type");
  const refreshToken = form.get("refresh_token");
  if (clientId !== env.GITHUB_CLIENT_ID || grantType !== "refresh_token" || !validToken(refreshToken)) {
    return responseJson(400, { error: "invalid_request" });
  }
  return tokenRequest(new URLSearchParams({
    client_id: env.GITHUB_CLIENT_ID,
    client_secret: env.GITHUB_CLIENT_SECRET,
    grant_type: "refresh_token",
    refresh_token: refreshToken,
  }));
}

async function revoke(form, env) {
  const clientId = form.get("client_id");
  const accessToken = form.get("access_token");
  if (clientId !== env.GITHUB_CLIENT_ID || !validToken(accessToken)) {
    return responseJson(400, { error: "invalid_request" });
  }
  let upstream;
  try {
    upstream = await fetch(`https://api.github.com/applications/${encodeURIComponent(env.GITHUB_CLIENT_ID)}/grant`, {
      method: "DELETE",
      redirect: "manual",
      headers: {
        accept: "application/vnd.github+json",
        authorization: basicAuth(env.GITHUB_CLIENT_ID, env.GITHUB_CLIENT_SECRET),
        "content-type": "application/json",
        "user-agent": "Droide-GitHub-Auth-Relay",
        "x-github-api-version": GITHUB_API_VERSION,
      },
      body: JSON.stringify({ access_token: accessToken }),
    });
  } catch {
    return responseJson(502, { error: "github_unreachable" });
  }
  if (upstream.status === 204) {
    return new Response(null, { status: 204, headers: { "cache-control": "no-store, max-age=0" } });
  }
  

  if (upstream.status === 404) {
    return new Response(null, { status: 204, headers: { "cache-control": "no-store, max-age=0" } });
  }
  return responseJson(upstream.status >= 400 && upstream.status < 500 ? 400 : 502, { error: "github_revoke_failed" });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (![EXCHANGE_PATH, REFRESH_PATH, REVOKE_PATH].includes(url.pathname)) {
      return responseJson(404, { error: "not_found" });
    }
    if (request.method !== "POST") {
      return new Response(null, { status: 405, headers: { allow: "POST", "cache-control": "no-store" } });
    }
    if (!env.GITHUB_CLIENT_ID || !env.GITHUB_CLIENT_SECRET || !configuredCallbackUri(env)) {
      return responseJson(503, { error: "relay_not_configured" });
    }
    const parsed = await readForm(request);
    if (parsed.error) return parsed.error;
    if (url.pathname === EXCHANGE_PATH) return exchange(parsed.form, env);
    if (url.pathname === REFRESH_PATH) return refresh(parsed.form, env);
    return revoke(parsed.form, env);
  },
};
