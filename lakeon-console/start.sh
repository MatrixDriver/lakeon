#!/bin/sh
envsubst '${PORT} ${API_URL}' < /tmp/default.conf.template > /etc/nginx/conf.d/default.conf

# Start GitHub OAuth relay in background
node /app/github-relay.js &

exec nginx -g 'daemon off;'
