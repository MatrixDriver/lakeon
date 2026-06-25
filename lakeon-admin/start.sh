#!/bin/sh
: "${PORT:=8080}"
: "${API_URL:=https://api.dbay.cloud:8443}"
envsubst '${PORT} ${API_URL}' < /tmp/default.conf.template > /etc/nginx/conf.d/default.conf
exec nginx -g 'daemon off;'
