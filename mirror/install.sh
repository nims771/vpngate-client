#!/usr/bin/env bash
# =============================================================================
#  VPN Gate Client — установщик зеркала списка серверов
#
#  Что делает (спрашивает всё по ходу):
#    • ставит python3, nginx, certbot;
#    • кладёт скрипт зеркала в /opt/vpngate-mirror;
#    • запускает обновление списка по расписанию (systemd-таймер);
#    • настраивает nginx: HTTPS с бесплатным сертификатом Let's Encrypt
#      для вашего домена (или HTTP по IP, если домена нет);
#    • в конце показывает готовый адрес для приложения.
#
#  Запуск:     sudo bash install.sh
#  Удаление:   sudo bash install.sh uninstall
#
#  Поддерживаются Ubuntu / Debian (рекомендуется), а также CentOS / Rocky /
#  AlmaLinux / Fedora.
# =============================================================================
set -euo pipefail

INSTALL_DIR=/opt/vpngate-mirror
DATA_DIR=/var/www/vpngate
ACME_DIR=/var/www/vpngate-acme
CONF_FILE="$INSTALL_DIR/config.env"
SERVICE=vpngate-mirror

# ---------- оформление ----------
if [ -t 1 ]; then
    C_RED=$'\e[31m'; C_GRN=$'\e[32m'; C_YLW=$'\e[33m'; C_BLU=$'\e[36m'; C_BLD=$'\e[1m'; C_RST=$'\e[0m'
else
    C_RED=""; C_GRN=""; C_YLW=""; C_BLU=""; C_BLD=""; C_RST=""
fi
info() { echo "${C_BLU}➜${C_RST} $*"; }
ok()   { echo "${C_GRN}✔${C_RST} $*"; }
warn() { echo "${C_YLW}!${C_RST} $*"; }
die()  { echo "${C_RED}✘ $*${C_RST}" >&2; exit 1; }
step() { echo; echo "${C_BLD}== $* ==${C_RST}"; }

# Откуда читать ответы: терминал (работает и при «curl … | bash») или stdin
INPUT=/dev/stdin
if (: </dev/tty) 2>/dev/null; then INPUT=/dev/tty; fi
REPLY=""

# ask "Вопрос" "по умолчанию" -> ответ в $REPLY
ask() {
    local prompt="$1" def="${2:-}"
    REPLY=""
    if [ -n "$def" ]; then
        read -r -p "$prompt [$def]: " REPLY <"$INPUT" || true
        REPLY="${REPLY:-$def}"
    else
        read -r -p "$prompt: " REPLY <"$INPUT" || true
    fi
}

# ask_yn "Вопрос" y|n -> 0 если да
ask_yn() {
    local prompt="$1" def="${2:-y}" hint="Д/н"
    [ "$def" = "n" ] && hint="д/Н"
    while true; do
        REPLY=""
        read -r -p "$prompt ($hint): " REPLY <"$INPUT" || true
        REPLY="${REPLY:-$def}"
        case "${REPLY,,}" in
            y|yes|д|да|Д|ДА|Да) return 0 ;;
            n|no|н|нет|Н|НЕТ|Нет) return 1 ;;
            *) echo "Ответьте «д» или «н»." ;;
        esac
    done
}

random_hex() {
    if command -v openssl >/dev/null 2>&1; then
        openssl rand -hex 8
    else
        tr -dc 'a-f0-9' </dev/urandom | head -c 16
    fi
}

# ---------- пакеты ----------
PKG=""
detect_pkg() {
    if command -v apt-get >/dev/null 2>&1; then PKG=apt
    elif command -v dnf >/dev/null 2>&1; then PKG=dnf
    elif command -v yum >/dev/null 2>&1; then PKG=yum
    else die "Не найден apt, dnf или yum. Поддерживаются Ubuntu/Debian и CentOS/Rocky/Alma/Fedora."
    fi
}

install_packages() {
    local need_certbot="$1"
    info "Устанавливаю пакеты (это может занять пару минут)…"
    case "$PKG" in
        apt)
            export DEBIAN_FRONTEND=noninteractive
            apt-get update -qq
            apt-get install -y -qq python3 nginx curl ca-certificates openssl >/dev/null
            if [ "$need_certbot" = 1 ]; then apt-get install -y -qq certbot >/dev/null; fi
            ;;
        dnf|yum)
            if [ "$need_certbot" = 1 ] && ! rpm -q epel-release >/dev/null 2>&1; then
                $PKG install -y -q epel-release >/dev/null 2>&1 || true
            fi
            $PKG install -y -q python3 nginx curl ca-certificates openssl >/dev/null
            if [ "$need_certbot" = 1 ]; then $PKG install -y -q certbot >/dev/null; fi
            ;;
    esac
    ok "Пакеты установлены"
}

# ---------- nginx ----------
nginx_conf_path() {
    if [ -d /etc/nginx/sites-available ]; then
        echo /etc/nginx/sites-available/vpngate-mirror.conf
    else
        echo /etc/nginx/conf.d/vpngate-mirror.conf
    fi
}

nginx_enable() {
    local conf; conf="$(nginx_conf_path)"
    if [ -d /etc/nginx/sites-enabled ]; then
        ln -sf "$conf" /etc/nginx/sites-enabled/vpngate-mirror.conf
    fi
    nginx -t >/dev/null 2>&1 || { nginx -t; die "Ошибка в конфигурации nginx (см. выше)"; }
    systemctl enable nginx >/dev/null 2>&1 || true
    if systemctl is-active --quiet nginx; then systemctl reload nginx; else systemctl start nginx; fi
}

# Кто слушает порт? Пусто — свободен.
port_owner() {
    ss -ltnpH "sport = :$1" 2>/dev/null | sed -n 's/.*users:(("\([^"]*\)".*/\1/p' | head -n1
}

open_firewall() {
    local ports=("$@")
    if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q "Status: active"; then
        for p in "${ports[@]}"; do ufw allow "$p/tcp" >/dev/null; done
        ok "Открыты порты в ufw: ${ports[*]}"
    elif command -v firewall-cmd >/dev/null 2>&1 && systemctl is-active --quiet firewalld; then
        for p in "${ports[@]}"; do firewall-cmd --quiet --permanent --add-port="$p/tcp"; done
        firewall-cmd --quiet --reload
        ok "Открыты порты в firewalld: ${ports[*]}"
    fi
}

# ---------- удаление ----------
uninstall() {
    [ "$(id -u)" -eq 0 ] || die "Запустите от root: sudo bash $0 uninstall"
    step "Удаление зеркала"
    systemctl disable --now "$SERVICE.timer" >/dev/null 2>&1 || true
    rm -f "/etc/systemd/system/$SERVICE.service" "/etc/systemd/system/$SERVICE.timer"
    systemctl daemon-reload || true
    rm -f /etc/nginx/sites-enabled/vpngate-mirror.conf "$(nginx_conf_path)"
    if command -v nginx >/dev/null 2>&1 && nginx -t >/dev/null 2>&1; then systemctl reload nginx || true; fi
    rm -rf "$INSTALL_DIR" "$DATA_DIR" "$ACME_DIR"
    ok "Зеркало удалено (nginx и сертификаты оставлены)."
    exit 0
}

[ "${1:-}" = "uninstall" ] && uninstall

# =============================================================================
[ "$(id -u)" -eq 0 ] || die "Запустите от root: sudo bash $0"
command -v systemctl >/dev/null 2>&1 || die "Нужен systemd (обычный VPS с Ubuntu/Debian подходит)."
detect_pkg

cat <<'TXT'

  ╔══════════════════════════════════════════════════════════╗
  ║   Зеркало списка серверов для VPN Gate Client            ║
  ╚══════════════════════════════════════════════════════════╝

  Сервер будет сам скачивать список VPN Gate, проверять серверы
  и отдавать приложению только рабочие — по адресу на вашем домене.

  Важно: этот сервер должен находиться вне сети с блокировками
  (например, VPS за границей), иначе он не скачает список.

TXT

# ---------- вопросы ----------
step "1. Домен"
echo "Укажите домен (или поддомен), который уже указывает на IP этого сервера,"
echo "например mirror.example.com. Если домена нет — оставьте пустым (будет HTTP по IP)."
ask "Домен" ""
DOMAIN="$(echo "$REPLY" | tr -d ' ' | sed 's#^https\?://##; s#/.*$##' | tr 'A-Z' 'a-z')"

SERVER_IP="$(curl -4 -s --max-time 8 https://api.ipify.org || true)"
EMAIL=""
HTTP_PORT=8080
if [ -n "$DOMAIN" ]; then
    DOMAIN_IP="$(getent ahostsv4 "$DOMAIN" 2>/dev/null | awk '{print $1; exit}')"
    if [ -z "$DOMAIN_IP" ]; then
        warn "Домен $DOMAIN пока не находится в DNS."
        ask_yn "Продолжить всё равно?" n || die "Добавьте A-запись $DOMAIN → ${SERVER_IP:-IP сервера} и запустите снова."
    elif [ -n "$SERVER_IP" ] && [ "$DOMAIN_IP" != "$SERVER_IP" ]; then
        warn "$DOMAIN указывает на $DOMAIN_IP, а IP этого сервера — $SERVER_IP."
        warn "Сертификат не получится, если домен смотрит не сюда (или стоит прокси Cloudflare)."
        ask_yn "Продолжить всё равно?" n || die "Исправьте A-запись домена и запустите снова."
    else
        ok "Домен $DOMAIN указывает на этот сервер ($DOMAIN_IP)"
    fi
    echo
    echo "E-mail нужен Let's Encrypt для напоминаний об истечении сертификата (можно пропустить)."
    ask "E-mail" ""
    EMAIL="$REPLY"
else
    warn "Без домена список пойдёт по обычному HTTP: провайдер сможет увидеть его содержимое."
    ask "Порт для HTTP" "8080"
    HTTP_PORT="$REPLY"
    [[ "$HTTP_PORT" =~ ^[0-9]+$ ]] && [ "$HTTP_PORT" -ge 1 ] && [ "$HTTP_PORT" -le 65535 ] || die "Неверный порт"
fi

step "2. Секретный адрес"
echo "Случайная часть адреса защищает файл от сканеров. Можно оставить предложенную."
ask "Секретная часть" "$(random_hex)"
SECRET="$(echo "$REPLY" | tr -cd 'A-Za-z0-9_-')"
[ -n "$SECRET" ] || die "Секретная часть не может быть пустой"

step "3. Как часто обновлять"
ask "Интервал обновления, минут (5–120)" "15"
INTERVAL="$REPLY"
[[ "$INTERVAL" =~ ^[0-9]+$ ]] && [ "$INTERVAL" -ge 5 ] && [ "$INTERVAL" -le 120 ] || die "Нужно число от 5 до 120"

echo
echo "Проверять серверы перед публикацией (TCP + TLS, как телефон по SSTP)?"
echo "Так в списке останутся только рабочие. Без проверки — просто копия списка VPN Gate."
CHECK_ARGS=""
ask_yn "Проверять серверы?" y || CHECK_ARGS="--no-check"

if [ -n "$DOMAIN" ]; then
    URL="https://$DOMAIN/$SECRET/servers.csv"
else
    URL="http://${SERVER_IP:-IP_СЕРВЕРА}:$HTTP_PORT/$SECRET/servers.csv"
fi

step "Проверьте"
echo "  Адрес списка:   ${C_BLD}$URL${C_RST}"
echo "  Обновление:     каждые $INTERVAL мин"
echo "  Проверка:       $([ -z "$CHECK_ARGS" ] && echo да || echo нет)"
[ -n "$DOMAIN" ] && echo "  Сертификат:     Let's Encrypt для $DOMAIN"
echo
ask_yn "Устанавливать?" y || die "Отменено"

# ---------- установка ----------
step "Установка"
install_packages "$([ -n "$DOMAIN" ] && echo 1 || echo 0)"

if [ -n "$DOMAIN" ]; then
    for p in 80 443; do
        owner="$(port_owner "$p")"
        if [ -n "$owner" ] && [ "$owner" != "nginx" ]; then
            die "Порт $p занят программой «$owner». Освободите его или используйте установку без домена (другой порт)."
        fi
    done
else
    owner="$(port_owner "$HTTP_PORT")"
    if [ -n "$owner" ] && [ "$owner" != "nginx" ]; then
        die "Порт $HTTP_PORT занят программой «$owner». Запустите снова и выберите другой порт."
    fi
fi

mkdir -p "$INSTALL_DIR" "$DATA_DIR" "$ACME_DIR"

info "Записываю скрипт зеркала…"
cat > "$INSTALL_DIR/vpngate_mirror.py" <<'PYEOF'
#!/usr/bin/env python3
"""
Зеркало списка серверов VPN Gate для приложения VPN Gate Client.

Что делает:
  1. Скачивает свежий список с http://www.vpngate.net/api/iphone/
     (или с другого адреса / из файла).
  2. Проверяет каждый сервер со своего сервера: TCP-подключение к порту
     и TLS-рукопожатие (как при SSTP). Мёртвые серверы выбрасывает.
  3. Сохраняет результат в том же CSV-формате, что и VPN Gate,
     атомарно (сначала во временный файл, потом переименование) —
     веб-сервер никогда не отдаст «половину» файла.

Нужен только Python 3.8+ (без сторонних библиотек).

Пример:
  python3 vpngate_mirror.py --out /var/www/vpngate/servers.csv

Задержку (пинг) до серверов приложение всё равно меряет само, с телефона:
она зависит от вашей сети. Зеркало лишь заранее отсеивает неработающие серверы
и делает список доступным там, где vpngate.net заблокирован.
"""

import argparse
import base64
import concurrent.futures
import os
import re
import socket
import ssl
import sys
import tempfile
import time
import urllib.request

DEFAULT_SOURCE = "http://www.vpngate.net/api/iphone/"
HEADER = ("#HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,"
          "TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64")

REMOTE_RE = re.compile(r"^[ \t]*remote[ \t]+(\S+)[ \t]+(\d+)(?:[ \t]+(\S+))?", re.M)
PROTO_RE = re.compile(r"^[ \t]*proto[ \t]+(\S+)", re.M)


def log(msg: str) -> None:
    print(time.strftime("%Y-%m-%d %H:%M:%S"), msg, file=sys.stderr, flush=True)


def fetch(source: str, timeout: int) -> str:
    if source.startswith("file://") or os.path.exists(source):
        path = source[len("file://"):] if source.startswith("file://") else source
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read()
    req = urllib.request.Request(source, headers={"User-Agent": "Mozilla/5.0 (vpngate-mirror)"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read().decode("utf-8", errors="replace")


def parse_rows(text: str):
    """Строки серверов (списки полей). Служебные строки * и # пропускаются."""
    rows = []
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("*") or line.startswith("#"):
            continue
        fields = line.split(",")
        if len(fields) < 15 or not fields[-1].strip():
            continue
        rows.append(fields)
    return rows


def endpoint(fields):
    """(ip, port, proto) из OpenVPN-конфига строки; None если не разобрать."""
    try:
        cfg = base64.b64decode(fields[-1].strip()).decode("utf-8", errors="replace")
    except Exception:
        return None
    m = REMOTE_RE.search(cfg)
    if not m:
        return None
    proto = m.group(3) or (PROTO_RE.search(cfg).group(1) if PROTO_RE.search(cfg) else "udp")
    proto = "tcp" if proto.lower().startswith("tcp") else "udp"
    ip = fields[1].strip() or m.group(1)
    return ip, int(m.group(2)), proto


def make_tls_context() -> ssl.SSLContext:
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE  # проверяем лишь, что сервер отвечает по TLS
    try:
        ctx.minimum_version = ssl.TLSVersion.TLSv1_2  # как и у Android-клиента SSTP
        ctx.set_ciphers("DEFAULT:@SECLEVEL=0")  # у старых SoftEther бывают слабые ключи
    except (ValueError, ssl.SSLError, AttributeError):
        pass
    return ctx


TLS_CTX = make_tls_context()


def check(ip: str, port: int, proto: str, timeout: float, tls: bool):
    """Время ответа в мс или None. Для UDP-конфигов пробуем типичные TCP-порты SoftEther."""
    ports = [port] if proto == "tcp" else [443, 992, 1194, 5555]
    for p in ports:
        for _ in range(2):  # вторая попытка — от случайных потерь
            start = time.monotonic()
            try:
                with socket.create_connection((ip, p), timeout=timeout) as sock:
                    if tls:
                        sock.settimeout(timeout)
                        with TLS_CTX.wrap_socket(sock, server_hostname=None):
                            pass
                return int((time.monotonic() - start) * 1000) or 1
            except (OSError, ssl.SSLError):
                time.sleep(0.2)
    return None


def write_atomic(path: str, rows) -> None:
    directory = os.path.dirname(os.path.abspath(path))
    os.makedirs(directory, exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix=".servers-", dir=directory)
    with os.fdopen(fd, "w", encoding="utf-8", newline="") as f:
        f.write("*vpn_servers\r\n")
        f.write(HEADER + "\r\n")
        for fields in rows:
            f.write(",".join(fields) + "\r\n")
        f.write("*\r\n")
    os.chmod(tmp, 0o644)
    os.replace(tmp, path)


def main() -> int:
    ap = argparse.ArgumentParser(description="Зеркало списка серверов VPN Gate")
    ap.add_argument("--out", required=True, help="куда сохранить CSV (например /var/www/vpngate/servers.csv)")
    ap.add_argument("--source", action="append",
                    help=f"откуда брать список (можно несколько, по порядку). По умолчанию {DEFAULT_SOURCE}")
    ap.add_argument("--timeout", type=float, default=5.0, help="таймаут проверки одного сервера, с")
    ap.add_argument("--workers", type=int, default=64, help="сколько серверов проверять одновременно")
    ap.add_argument("--no-tls", action="store_true", help="проверять только TCP, без TLS-рукопожатия")
    ap.add_argument("--no-check", action="store_true", help="не проверять серверы, просто зеркалировать")
    ap.add_argument("--min-alive", type=int, default=10,
                    help="если живых меньше — сохранить полный список (защита от сбоя сети на сервере)")
    args = ap.parse_args()

    text = None
    for src in args.source or [DEFAULT_SOURCE]:
        try:
            text = fetch(src, timeout=60)
            rows = parse_rows(text)
            if rows:
                log(f"загружено {len(rows)} серверов из {src}")
                break
            log(f"{src}: список пуст или формат не распознан")
        except Exception as e:  # noqa: BLE001
            log(f"{src}: ошибка загрузки: {e}")
        text = None
    if text is None:
        log("не удалось получить список ни из одного источника — старый файл не трогаем")
        return 1

    rows = parse_rows(text)
    if args.no_check:
        write_atomic(args.out, rows)
        log(f"сохранено {len(rows)} серверов без проверки → {args.out}")
        return 0

    started = time.monotonic()
    alive = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = {}
        for fields in rows:
            ep = endpoint(fields)
            if ep is None:
                continue
            futures[pool.submit(check, *ep, args.timeout, not args.no_tls)] = fields
        for fut in concurrent.futures.as_completed(futures):
            ms = fut.result()
            if ms is not None:
                alive.append((futures[fut], ms))

    log(f"работают {len(alive)} из {len(rows)} (проверка заняла {time.monotonic() - started:.0f} с)")

    if len(alive) < args.min_alive:
        log(f"живых меньше {args.min_alive} — похоже на сбой сети, сохраняем полный список")
        write_atomic(args.out, rows)
        return 0

    # Сортируем по рейтингу VPN Gate (поле Score), лучшие первыми
    def score(item):
        try:
            return -int(item[0][2])
        except ValueError:
            return 0

    alive.sort(key=score)
    write_atomic(args.out, [fields for fields, _ in alive])
    log(f"сохранено {len(alive)} серверов → {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
PYEOF
chmod 755 "$INSTALL_DIR/vpngate_mirror.py"
ok "Скрипт: $INSTALL_DIR/vpngate_mirror.py"

cat > "$CONF_FILE" <<EOF
DOMAIN=$DOMAIN
SECRET=$SECRET
HTTP_PORT=$HTTP_PORT
INTERVAL=$INTERVAL
URL=$URL
EOF

info "Первое скачивание и проверка серверов (10–60 секунд)…"
if python3 "$INSTALL_DIR/vpngate_mirror.py" --out "$DATA_DIR/servers.csv" $CHECK_ARGS; then
    ok "Список готов: $(grep -cv '^[*#]' "$DATA_DIR/servers.csv" || true) серверов"
else
    warn "Не удалось получить список прямо сейчас — таймер попробует снова."
    warn "Проверьте, открывается ли с сервера: curl -I http://www.vpngate.net/api/iphone/"
fi

info "Настраиваю обновление по расписанию…"
cat > "/etc/systemd/system/$SERVICE.service" <<EOF
[Unit]
Description=VPN Gate server list mirror
Wants=network-online.target
After=network-online.target

[Service]
Type=oneshot
ExecStart=/usr/bin/env python3 $INSTALL_DIR/vpngate_mirror.py --out $DATA_DIR/servers.csv $CHECK_ARGS
Nice=10
EOF
cat > "/etc/systemd/system/$SERVICE.timer" <<EOF
[Unit]
Description=Обновление зеркала VPN Gate каждые $INTERVAL мин

[Timer]
OnBootSec=1min
OnUnitActiveSec=${INTERVAL}min
Persistent=true

[Install]
WantedBy=timers.target
EOF
systemctl daemon-reload
systemctl enable --now "$SERVICE.timer" >/dev/null
ok "Таймер включён (журнал: journalctl -u $SERVICE)"

NGINX_CONF="$(nginx_conf_path)"
mkdir -p "$(dirname "$NGINX_CONF")"
LOCATION_BLOCK="    location = /$SECRET/servers.csv {
        alias $DATA_DIR/servers.csv;
        default_type text/plain;
        add_header Cache-Control \"public, max-age=120\";
    }"

if [ -n "$DOMAIN" ]; then
    info "Настраиваю nginx и получаю сертификат…"
    # Этап 1: только HTTP, чтобы Let's Encrypt проверил домен
    cat > "$NGINX_CONF" <<EOF
server {
    listen 80;
    server_name $DOMAIN;
    location /.well-known/acme-challenge/ { root $ACME_DIR; }
    location / { return 404; }
}
EOF
    open_firewall 80 443
    nginx_enable

    if [ -n "$EMAIL" ]; then MAIL_ARGS=(-m "$EMAIL"); else MAIL_ARGS=(--register-unsafely-without-email); fi
    if [ ! -f "/etc/letsencrypt/live/$DOMAIN/fullchain.pem" ]; then
        certbot certonly --webroot -w "$ACME_DIR" -d "$DOMAIN" \
            --non-interactive --agree-tos "${MAIL_ARGS[@]}" \
            --deploy-hook "systemctl reload nginx" \
            || die "Не удалось получить сертификат. Проверьте, что домен указывает на этот сервер и порт 80 открыт."
    fi
    ok "Сертификат получен (продлевается автоматически)"

    # Этап 2: полноценный HTTPS
    cat > "$NGINX_CONF" <<EOF
server {
    listen 80;
    server_name $DOMAIN;
    location /.well-known/acme-challenge/ { root $ACME_DIR; }
    location / { return 301 https://\$host\$request_uri; }
}

server {
    listen 443 ssl;
    server_name $DOMAIN;

    ssl_certificate     /etc/letsencrypt/live/$DOMAIN/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/$DOMAIN/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;

$LOCATION_BLOCK

    location / { return 404; }
}
EOF
    nginx_enable
else
    info "Настраиваю nginx (HTTP на порту $HTTP_PORT)…"
    cat > "$NGINX_CONF" <<EOF
server {
    listen $HTTP_PORT;
    server_name _;

$LOCATION_BLOCK

    location / { return 404; }
}
EOF
    open_firewall "$HTTP_PORT"
    nginx_enable
fi
ok "nginx настроен: $NGINX_CONF"

# ---------- проверка ----------
step "Проверка"
sleep 1
if [ -n "$DOMAIN" ]; then
    TEST_URL="$URL"
    CURL_EXTRA=(--resolve "$DOMAIN:443:127.0.0.1")
else
    TEST_URL="http://127.0.0.1:$HTTP_PORT/$SECRET/servers.csv"
    CURL_EXTRA=()
fi
CODE="$(curl -s -o /tmp/vpngate-test.csv -w '%{http_code}' --max-time 15 ${CURL_EXTRA[@]+"${CURL_EXTRA[@]}"} "$TEST_URL" || true)"
if [ "$CODE" = "200" ] && head -n1 /tmp/vpngate-test.csv | grep -q '^\*vpn_servers'; then
    ok "Файл отдаётся: $(grep -cv '^[*#]' /tmp/vpngate-test.csv) серверов"
elif [ "$CODE" = "404" ] && [ ! -s "$DATA_DIR/servers.csv" ]; then
    warn "nginx работает, но список ещё не скачан. Подождите обновления таймера."
else
    warn "Проверка вернула код ${CODE:-нет ответа}. Посмотрите: nginx -t; journalctl -u $SERVICE"
fi
rm -f /tmp/vpngate-test.csv

cat <<EOF

${C_GRN}${C_BLD}  Готово!${C_RST}
$( [ -z "$SERVER_IP" ] && [ -z "$DOMAIN" ] && echo "
  ${C_YLW}Не удалось узнать внешний IP — замените IP_СЕРВЕРА в адресе на IP вашего сервера.${C_RST}" )

  Адрес списка для приложения:

      ${C_BLD}$URL${C_RST}

  В приложении: Настройки → «Список серверов и пинг» → «Адрес списка»,
  вставьте адрес → «Сохранить адрес» → вкладка «Страны» → «Обновить».

  Полезное:
    обновить список сейчас:  sudo systemctl start $SERVICE
    журнал обновлений:       sudo journalctl -u $SERVICE -n 30
    настройки установки:     $CONF_FILE
    удалить зеркало:         sudo bash $0 uninstall

EOF
