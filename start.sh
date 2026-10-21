#!/bin/sh
# 本机的 NODE_OPTIONS 里有 --use-system-ca，Node 20 不接受，会直接启动失败
cd "$(dirname "$0")" || exit 1
NODE_OPTIONS=
export NODE_OPTIONS
exec node server/server.mjs "$@"
