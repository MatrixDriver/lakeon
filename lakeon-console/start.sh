#!/bin/sh
echo "=== DEBUG: API_URL=${API_URL} ==="
echo "=== DEBUG: PORT=${PORT} ==="
envsubst '${PORT} ${API_URL}' < /tmp/default.conf.template > /etc/nginx/conf.d/default.conf
echo "=== Generated nginx config ==="
cat /etc/nginx/conf.d/default.conf
echo "=== End config ==="
exec nginx -g 'daemon off;'
