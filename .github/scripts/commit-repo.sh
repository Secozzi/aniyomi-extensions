#!/bin/bash
set -e

git config --global user.email "action@github.com"
git config --global user.name "GitHub Action"
git status
if [ -n "$(git status --porcelain)" ]; then
    git add .
    git commit -m "Update extensions repo"
    git push

    curl https://purge.jsdelivr.net/gh/Secozzi/aniyomi-extensions@repo/index.min.json
else
    echo "No changes to commit"
fi
