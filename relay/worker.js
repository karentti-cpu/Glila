// Glila's relay: fetches the pages that the website can't read by itself
// (YouTube results, web search, Google News, podcast feeds) and hands them back.
// Runs on Cloudflare Workers' free plan. Only the Glila website may use it.

const ALLOWED_ORIGINS = ['https://karentti-cpu.github.io'];
const MAX_BYTES = 3 * 1024 * 1024;
const USER_AGENT =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36';

export default {
  async fetch(request, env, ctx) {
    const origin = request.headers.get('Origin') || '';
    const allowed = ALLOWED_ORIGINS.includes(origin);
    const cors = { 'Access-Control-Allow-Origin': allowed ? origin : ALLOWED_ORIGINS[0], Vary: 'Origin' };
    const fail = (status, text) => new Response(text, { status, headers: cors });

    if (request.method === 'OPTIONS') return new Response(null, { headers: { ...cors, 'Access-Control-Allow-Methods': 'GET' } });
    if (request.method !== 'GET' || !allowed) return fail(403, 'forbidden');

    let target;
    try {
      target = new URL(new URL(request.url).searchParams.get('url'));
    } catch (e) {
      return fail(400, 'bad url');
    }
    if (target.protocol !== 'https:') return fail(400, 'https only');

    // the same page asked again within 10 minutes comes from Cloudflare's cache
    const key = new Request(target.toString());
    let res = await caches.default.match(key);
    if (!res) {
      const headers = { 'User-Agent': USER_AGENT, 'Accept-Language': 'he,fr;q=0.9,en;q=0.8' };
      // YouTube asks about cookies first in some countries; this answers "reject all"
      if (target.hostname.endsWith('youtube.com')) headers.Cookie = 'SOCS=CAI';
      let up;
      try {
        up = await fetch(target.toString(), { headers, redirect: 'follow' });
      } catch (e) {
        return fail(502, 'unreachable');
      }
      if (!up.ok) return fail(502, 'upstream ' + up.status);
      const body = await up.arrayBuffer();
      if (body.byteLength > MAX_BYTES) return fail(413, 'too big');
      res = new Response(body, {
        headers: { 'Content-Type': up.headers.get('Content-Type') || 'text/plain', 'Cache-Control': 'max-age=600' },
      });
      ctx.waitUntil(caches.default.put(key, res.clone()));
    }
    const out = new Response(res.body, res);
    Object.entries(cors).forEach(([k, v]) => out.headers.set(k, v));
    return out;
  },
};
