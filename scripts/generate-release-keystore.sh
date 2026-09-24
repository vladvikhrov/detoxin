#!/usr/bin/env bash
# Генерирует локальный ключ подписи release.jks и keystore.properties (оба в .gitignore).
# Пароль создаётся случайно и хранится только в keystore.properties на этой машине.
# ВАЖНО: сделайте резервную копию обоих файлов. Без них нельзя выпустить обновление
# приложения, подписанное тем же ключом.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KEYSTORE="$ROOT/release.jks"
PROPS="$ROOT/keystore.properties"
ALIAS="detoxin_key"

if [[ -f "$KEYSTORE" ]]; then
    echo "release.jks уже существует — генерация пропущена."
    exit 0
fi

command -v keytool >/dev/null || { echo "keytool не найден: установите JDK 17+." >&2; exit 1; }

PASSWORD="$(head -c 512 /dev/urandom | LC_ALL=C tr -dc 'A-Za-z0-9' | cut -c1-32)"

keytool -genkeypair -v \
    -keystore "$KEYSTORE" \
    -storetype PKCS12 \
    -alias "$ALIAS" \
    -keyalg RSA -keysize 4096 \
    -validity 10000 \
    -storepass "$PASSWORD" -keypass "$PASSWORD" \
    -dname "CN=Detoxin, O=Detoxin, C=RU" >/dev/null

cat > "$PROPS" <<PROPS
storeFile=release.jks
storePassword=$PASSWORD
keyAlias=$ALIAS
keyPassword=$PASSWORD
PROPS
chmod 600 "$KEYSTORE" "$PROPS"

echo "Создан release.jks (alias: $ALIAS) и keystore.properties."
echo "Сделайте резервную копию обоих файлов в надёжном месте."
