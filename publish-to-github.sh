#!/usr/bin/env bash
# publish-to-github.sh — публикация проекта «Диктофон для совещаний» на GitHub
#
# Подготовка (один раз):
#   1) Создайте ПУСТОЙ репозиторий: https://github.com/new (без README и .gitignore)
#   2) Получите Personal Access Token:
#      https://github.com/settings/tokens -> Generate new token (classic), scope: repo
#
# Запуск:
#   GITHUB_TOKEN=<токен> REPO=<логин/имя-репо> ./publish-to-github.sh
#   Пример: GITHUB_TOKEN=ghp_xxx REPO=ivan/meeting-recorder ./publish-to-github.sh
set -euo pipefail

: "${GITHUB_TOKEN:?Не задан GITHUB_TOKEN (Personal Access Token со scope repo)}"
: "${REPO:?Не задан REPO в формате login/repo-name}"

BRANCH="main"
cd "$(dirname "$0")"

echo "==> 1. Проверяем, что ветка ${BRANCH} содержит весь код..."
CUR=$(git rev-parse --abbrev-ref HEAD)
if [ "$CUR" != "$BRANCH" ]; then git checkout "$BRANCH"; fi
BEHIND=$(git rev-list --count "$BRANCH"..qwen-code-* 2>/dev/null || echo 0)
[ "$BEHIND" != "0" ] && git merge --ff-only "$(git rev-parse qwen-code-*)" || true

echo "==> 2. Добавляем удалённый репозиторий origin..."
git remote remove origin 2>/dev/null || true
git remote add origin "https://x-access-token:${GITHUB_TOKEN}@github.com/${REPO}.git"

echo "==> 3. Отправляем ветку ${BRANCH} на GitHub..."
git push -u origin "$BRANCH"

echo "==> Готово! Проект опубликован: https://github.com/${REPO}"

# Удаляем токен из конфига, чтобы он не остался в .git/config
git remote set-url origin "https://github.com/${REPO}.git"
echo "==> Токен удалён из remote URL."
