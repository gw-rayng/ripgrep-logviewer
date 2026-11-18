#!/usr/bin/env sh
# 用法：./start.sh                  搜尋專案內的 logs 資料夾
#       ./start.sh /path/to/logs …  搜尋指定的資料夾（可多個）
cd "$(dirname "$0")"
ARGS=""
for d in "$@"; do ARGS="$ARGS --root=$d"; done
exec java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 src/LogViewer.java $ARGS
