#!/bin/sh
envsubst '${PORT} ${API_URL}' < /tmp/default.conf.template > /etc/nginx/conf.d/default.conf
exec nginx -g 'daemon off;'
