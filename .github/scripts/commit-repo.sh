#!/bin/bash
set -e

rsync -a --delete --exclude .git --exclude .gitignore ../master/repo/ .
git config --global user.email "action@github.com"
git config --global user.name "GitHub Action"
git status
if [ -n "$(git status --porcelain)" ]; then
    git add .
    git commit -m "Update extensions repo"
    git push

    # Purge cached index on jsDelivr
    curl https://purge.jsdelivr.net/gh/aniyomiorg/aniyomi-extensions@repo/index.min.json
else
    echo "No changes to commit"
fi
