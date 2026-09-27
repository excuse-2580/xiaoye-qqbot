#!/usr/bin/env bash
#
# 拉取 llama.cpp 源码到 third_party/llama.cpp
# CMake 会把它一起编进 libggml-jni.so
#
# 用法：
#   ./scripts/fetch-llama-cpp.sh                 # 拉 master
#   LLAMA_CPP_REF=b6xxx ./scripts/fetch-llama-cpp.sh   # 指定 tag / 分支 / commit
#
set -euo pipefail

REF="${LLAMA_CPP_REF:-master}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIR="$ROOT/third_party/llama.cpp"
REPO="https://github.com/ggml-org/llama.cpp"

echo "llama.cpp 版本：$REF"
echo "目标目录：$DIR"

if [ -d "$DIR/.git" ]; then
    echo "已存在，增量更新…"
    git -C "$DIR" fetch --depth 1 origin "$REF"
    git -C "$DIR" checkout FETCH_HEAD
else
    mkdir -p "$(dirname "$DIR")"
    git init "$DIR"
    git -C "$DIR" remote add origin "$REPO"
    git -C "$DIR" fetch --depth 1 origin "$REF"
    git -C "$DIR" checkout FETCH_HEAD
fi

echo
echo "完成：$DIR"
echo "当前版本：$(git -C "$DIR" rev-parse --short HEAD)"
echo
echo "接下来：用 Android Studio 打开这个目录，直接 Run 或 Build APK 即可。"
echo "想固定版本避免上游 API 变动，重跑一次并指定 LLAMA_CPP_REF，"
echo "然后把 third_party/llama.cpp 加为 git submodule 提交进去。"
