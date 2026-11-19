#!/usr/bin/env bash
set -euo pipefail
git init
git add .
git commit -m "Initial Cinema TV Android app"
git branch -M main
git remote add origin https://github.com/adamhull27041994-del/Android-TV.git
git push -u origin main
