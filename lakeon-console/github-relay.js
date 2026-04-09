// Lightweight GitHub OAuth relay server
// Proxies requests from backend (behind GFW) to GitHub API
const http = require('http');
const https = require('https');
const { URL } = require('url');

const PORT = 3001;

function proxyToGithub(targetUrl, req, res) {
  const url = new URL(targetUrl);

  let body = '';
  req.on('data', chunk => { body += chunk; });
  req.on('end', () => {
    const options = {
      hostname: url.hostname,
      path: url.pathname + url.search,
      method: req.method,
      headers: {
        'Content-Type': req.headers['content-type'] || 'application/x-www-form-urlencoded',
        'Accept': 'application/json',
        'User-Agent': 'DBay',
      },
    };
    // Forward Authorization header if present
    if (req.headers['authorization']) {
      options.headers['Authorization'] = req.headers['authorization'];
    }
    if (body) {
      options.headers['Content-Length'] = Buffer.byteLength(body);
    }

    const upstream = https.request(options, (upRes) => {
      res.writeHead(upRes.statusCode, { 'Content-Type': 'application/json' });
      upRes.pipe(res);
    });
    upstream.on('error', (err) => {
      console.error('Upstream error:', err.message);
      res.writeHead(502, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: 'relay_error', message: err.message }));
    });
    if (body) upstream.write(body);
    upstream.end();
  });
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);

  if (url.pathname === '/oauth-relay/github/token') {
    // Forward query params as POST body to GitHub
    const params = url.searchParams.toString();
    const target = `https://github.com/login/oauth/access_token?${params}`;
    proxyToGithub(target, req, res);
  } else if (url.pathname.startsWith('/oauth-relay/github/api/')) {
    const ghPath = url.pathname.replace('/oauth-relay/github/api', '');
    const target = `https://api.github.com${ghPath}${url.search}`;
    proxyToGithub(target, req, res);
  } else {
    res.writeHead(404);
    res.end('Not found');
  }
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`GitHub relay listening on 127.0.0.1:${PORT}`);
});
