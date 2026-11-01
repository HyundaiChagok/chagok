#!/bin/sh
# web/을 공개 저장소(RosieOh/chagok-report)에 올린다. GitHub Pages가 1~2분 안에 다시 배포한다.
# 쓰는 법: sh scripts/publish-web.sh "바꾼 내용"
set -e
cd "$(dirname "$0")/.."
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
git clone -q --depth 1 https://github.com/RosieOh/chagok-report.git "$tmp"
# README와 .nojekyll은 저장소에 있는 것을 그대로 둔다.
cp -r web/. "$tmp/"
cd "$tmp"
git add -A
if git diff --cached --quiet; then
  echo "바뀐 내용이 없습니다."
  exit 0
fi
git commit -q -m "${1:-리포트 웹 갱신}"
git push -q
echo "올렸습니다: https://rosieoh.github.io/chagok-report/"
