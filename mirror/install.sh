#!/usr/bin/env bash
# =============================================================================
#  ProXvpn — установщик зеркала списка серверов (и обновлений приложения)
#
#  Что делает (спрашивает всё по ходу):
#    • ставит python3, nginx, certbot;
#    • кладёт скрипт зеркала в /opt/vpngate-mirror;
#    • запускает обновление списка по расписанию (systemd-таймер);
#    • настраивает nginx: HTTPS с бесплатным сертификатом Let's Encrypt
#      для вашего домена (или HTTP по IP, если домена нет);
#    • раздаёт обновления приложения ProXvpn (APK и MSI) с вашего домена;
#    • в конце показывает готовый адрес для приложения.
#
#  Запуск:     sudo bash install.sh
#  Обновление: sudo bash install.sh update     (новая версия скрипта, без вопросов)
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

# ---------- скрипт зеркала (встроен в установщик) ----------
write_mirror_script() {
    mkdir -p "$INSTALL_DIR"
    cat > "$INSTALL_DIR/vpngate_mirror.py" <<'PYEOF'
#!/usr/bin/env python3
"""
Зеркало списка серверов VPN Gate для приложения VPN Gate Client (версия 2).

Что делает:
  1. Скачивает список из API VPN Gate (http://www.vpngate.net/api/iphone/).
     API отдаёт только ~100 лучших по рейтингу серверов, в основном Япония/Корея.
  2. Дополнительно разбирает главную страницу сайта (https://www.vpngate.net/en/),
     где перечислены ВСЕ серверы (обычно 200+ и больше стран), и узнаёт у каждого
     TCP-порт SoftEther/SSTP.
  3. Проверяет каждый сервер ровно так, как подключается телефон по SSTP:
     TCP → TLS → HTTP-запрос SSTP_DUPLEX_POST → ждёт «HTTP/1.1 200».
     Неработающие выбрасывает.
  4. Сохраняет в формате API VPN Gate (CSV) атомарно. В OpenVPN-конфиг каждой
     строки добавляется строка-подсказка «#sstp-port N» — по ней приложение знает,
     на какой порт подключаться по SSTP и какой порт пинговать.

Нужен только Python 3.8+ (без сторонних библиотек).

Пример:
  python3 vpngate_mirror.py --out /var/www/vpngate/servers.csv
"""

import argparse
import base64
import concurrent.futures
import html
import os
import re
import socket
import ssl
import sys
import tempfile
import time
import urllib.request
import uuid
from html.parser import HTMLParser

DEFAULT_SOURCE = "http://www.vpngate.net/api/iphone/"
DEFAULT_SITES = ["https://www.vpngate.net/en/", "http://www.vpngate.net/en/"]
HEADER = ("#HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,"
          "TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64")

REMOTE_RE = re.compile(r"^[ \t]*remote[ \t]+(\S+)[ \t]+(\d+)(?:[ \t]+(\S+))?", re.M)
PROTO_RE = re.compile(r"^[ \t]*proto[ \t]+(\S+)", re.M)
SSTP_HINT_RE = re.compile(r"^#sstp-port[ \t]+(\d+)[ \t]*$", re.M)
IP_RE = re.compile(r"\b(\d{1,3}(?:\.\d{1,3}){3})\b")
UDP_FALLBACK_PORTS = [443, 992, 1194, 5555]


def log(msg: str) -> None:
    print(time.strftime("%Y-%m-%d %H:%M:%S"), msg, file=sys.stderr, flush=True)


def fetch(source: str, timeout: int) -> str:
    if source.startswith("file://") or os.path.exists(source):
        path = source[len("file://"):] if source.startswith("file://") else source
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read()
    req = urllib.request.Request(source, headers={
        "User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36",
        "Accept-Language": "en",
    })
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read().decode("utf-8", errors="replace")


# --------------------------------------------------------------------------- API

def parse_rows(text: str):
    """Строки серверов из CSV API (списки полей). Служебные строки * и # пропускаются."""
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


def decode_config(fields) -> str:
    try:
        return base64.b64decode(fields[-1].strip()).decode("utf-8", errors="replace")
    except Exception:
        return ""


def endpoint(fields):
    """(ip, port, proto) из OpenVPN-конфига строки; None если не разобрать."""
    cfg = decode_config(fields)
    m = REMOTE_RE.search(cfg)
    if not m:
        return None
    pm = PROTO_RE.search(cfg)
    proto = m.group(3) or (pm.group(1) if pm else "udp")
    proto = "tcp" if proto.lower().startswith("tcp") else "udp"
    ip = fields[1].strip() or m.group(1)
    return ip, int(m.group(2)), proto


# ----------------------------------------------------------------------- сайт

class _TableParser(HTMLParser):
    """Собирает все таблицы страницы: [ {id, rows: [[cell_html, ...], ...]}, ... ]."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.tables = []
        self._stack = []        # открытые таблицы
        self._row = None
        self._cell = None
        self._cell_depth = 0     # вложенные таблицы внутри ячейки

    def handle_starttag(self, tag, attrs):
        raw = self.get_starttag_text() or ""
        if self._cell is not None and (tag != "td" or self._cell_depth > 0):
            if tag == "table":
                self._cell_depth += 1
            self._cell.append(raw)
            return
        if tag == "table":
            t = {"id": dict(attrs).get("id", ""), "rows": []}
            self.tables.append(t)
            self._stack.append(t)
        elif tag == "tr" and self._stack:
            self._row = []
        elif tag == "td" and self._row is not None:
            self._cell = []

    def handle_endtag(self, tag):
        if self._cell is not None:
            if tag == "table" and self._cell_depth > 0:
                self._cell_depth -= 1
                self._cell.append("</table>")
                return
            if tag == "td" and self._cell_depth == 0:
                self._row.append("".join(self._cell))
                self._cell = None
                return
            if tag != "tr":
                self._cell.append(f"</{tag}>")
                return
            # незакрытая ячейка — закрываем вместе со строкой
            self._row.append("".join(self._cell))
            self._cell = None
        if tag == "tr" and self._row is not None and self._stack:
            self._stack[-1]["rows"].append(self._row)
            self._row = None
        elif tag == "table" and self._stack:
            self._stack.pop()

    def handle_data(self, data):
        if self._cell is not None:
            self._cell.append(html.escape(data, quote=False))


def _text(fragment: str) -> str:
    s = re.sub(r"<br\s*/?>", "\n", fragment, flags=re.I)
    s = re.sub(r"<[^>]+>", " ", s)
    return re.sub(r"[ \t\r\f\v]+", " ", html.unescape(s)).strip()


def _num(s: str, default=0) -> int:
    try:
        return int(float(s.replace(",", "")))
    except (ValueError, AttributeError):
        return default


def parse_website(page: str):
    """Список серверов с главной страницы vpngate.net (словари)."""
    p = _TableParser()
    p.feed(page)
    servers = []
    for table in p.tables:
        for cells in table["rows"]:
            if len(cells) < 8:
                continue
            row_html = " ".join(cells)
            host_m = re.search(r"([A-Za-z0-9-]+)\.opengw\.net", row_html)
            ip_m = IP_RE.search(_text(cells[1])) or IP_RE.search(_text(row_html))
            if not host_m or not ip_m:
                continue
            flag_m = re.search(r"flags/([A-Za-z]{2})\.(?:png|gif|jpg)", cells[0])
            s = {
                "host": host_m.group(1),
                "ip": ip_m.group(1),
                "country": _text(cells[0]).split("\n")[0].strip() or "Unknown",
                "code": (flag_m.group(1).upper() if flag_m else "XX"),
                "sessions": 0, "uptime_ms": 0, "users": 0, "speed": 0, "ping": 0,
                "traffic": 0, "operator": "", "score": 0,
                "se_tcp": None, "ovpn_tcp": None, "ovpn_udp": None, "sstp": False, "sstp_port": None,
            }
            t2 = _text(cells[2])
            m = re.search(r"([\d,]+)\s*sessions?", t2, re.I)
            if m: s["sessions"] = _num(m.group(1))
            m = re.search(r"(\d+)\s*(day|hour|min)", t2, re.I)
            if m:
                mult = {"day": 86400, "hour": 3600, "min": 60}[m.group(2).lower()]
                s["uptime_ms"] = _num(m.group(1)) * mult * 1000
            m = re.search(r"Total\s*([\d,]+)\s*users?", t2, re.I)
            if m: s["users"] = _num(m.group(1))
            t3 = _text(cells[3])
            m = re.search(r"([\d.,]+)\s*Mbps", t3, re.I)
            if m: s["speed"] = int(float(m.group(1).replace(",", "")) * 1_000_000)
            m = re.search(r"Ping:?\s*([\d,]+)\s*ms", t3, re.I) or re.search(r"([\d,]+)\s*ms", t3, re.I)
            if m: s["ping"] = _num(m.group(1))
            m = re.search(r"([\d.,]+)\s*(TB|GB|MB|KB)\b", t3, re.I)
            if m:
                k = {"KB": 1 << 10, "MB": 1 << 20, "GB": 1 << 30, "TB": 1 << 40}[m.group(2).upper()]
                s["traffic"] = int(float(m.group(1).replace(",", "")) * k)
            if len(cells) > 4:
                m = re.search(r"TCP:?\s*(\d+)", _text(cells[4]), re.I)
                if m: s["se_tcp"] = _num(m.group(1))
            m = re.search(r"do_openvpn\.aspx\?([^\"'>\s]+)", row_html)
            if m:
                params = dict(kv.split("=", 1) for kv in html.unescape(m.group(1)).split("&") if "=" in kv)
                if params.get("tcp", "0") not in ("", "0"): s["ovpn_tcp"] = _num(params["tcp"])
                if params.get("udp", "0") not in ("", "0"): s["ovpn_udp"] = _num(params["udp"])
            if re.search(r"howto_sstp|MS-SSTP|SSTP\s*Hostname", row_html, re.I):
                s["sstp"] = True
                m = re.search(r"opengw\.net:(\d+)", row_html[row_html.lower().find("sstp"):])
                if m: s["sstp_port"] = _num(m.group(1))
            if len(cells) > 8:
                m = re.search(r"By\s+([^\n<]+)", _text(cells[8]))
                if m: s["operator"] = m.group(1).strip().replace(",", " ")[:60]
            m = re.search(r"([\d,]{2,})", _text(cells[-1]))
            if m: s["score"] = _num(m.group(1))
            servers.append(s)
    # дубликаты (по IP) убираем
    uniq = {}
    for s in servers:
        uniq.setdefault(s["ip"], s)
    return list(uniq.values())


def website_row(s, port: int):
    """Строка CSV для сервера, которого нет в API (без полного OpenVPN-конфига: только SSTP)."""
    cfg = ("# Сервер с сайта VPN Gate: полного OpenVPN-конфига нет, только SSTP\r\n"
           f"client\r\ndev tun\r\nproto tcp\r\nremote {s['ip']} {port}\r\n")
    b64 = base64.b64encode(cfg.encode()).decode()
    return [s["host"], s["ip"], str(s["score"]), str(s["ping"]), str(s["speed"]),
            s["country"].replace(",", " "), s["code"], str(s["sessions"]), str(s["uptime_ms"]),
            str(s["users"]), str(s["traffic"]), "2weeks", s["operator"], "", b64]


def with_sstp_hint(fields, port: int):
    """Добавляет в конфиг строки подсказку «#sstp-port N» (OpenVPN считает её комментарием)."""
    cfg = SSTP_HINT_RE.sub("", decode_config(fields)).rstrip("\r\n")
    cfg += f"\r\n#sstp-port {port}\r\n"
    out = list(fields)
    out[-1] = base64.b64encode(cfg.encode()).decode()
    return out


# -------------------------------------------------------------------- проверка

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


def probe(ip: str, port: int, timeout: float, mode: str):
    """
    mode: "sstp" — полный SSTP-запрос, "tls" — TCP+TLS, "tcp" — только TCP.
    Возвращает время в мс или None. Две попытки — от случайных потерь.
    """
    for attempt in range(2):
        start = time.monotonic()
        try:
            with socket.create_connection((ip, port), timeout=timeout) as sock:
                if mode == "tcp":
                    return int((time.monotonic() - start) * 1000) or 1
                sock.settimeout(timeout)
                with TLS_CTX.wrap_socket(sock, server_hostname=None) as tls:
                    if mode == "tls":
                        return int((time.monotonic() - start) * 1000) or 1
                    req = ("SSTP_DUPLEX_POST /sra_{BA195980-CD49-458b-9E23-C84EE0ADCD75}/ HTTP/1.1\r\n"
                           "Content-Length: 18446744073709551615\r\n"
                           f"Host: {ip}\r\n"
                           f"SSTPCORRELATIONID: {{{uuid.uuid4()}}}\r\n\r\n")
                    tls.sendall(req.encode())
                    data = b""
                    while b"\r\n" not in data and len(data) < 512:
                        chunk = tls.recv(512)
                        if not chunk:
                            break
                        data += chunk
                    if data.startswith(b"HTTP/") and b" 200" in data.split(b"\r\n", 1)[0]:
                        return int((time.monotonic() - start) * 1000) or 1
                    return None  # сервер ответил, но SSTP не принимает — повтор не поможет
        except (OSError, ssl.SSLError):
            if attempt == 0:
                time.sleep(0.3)
    return None


def find_port(ip: str, ports, timeout: float, mode: str):
    """Первый работающий порт из списка: (port, ms) или (None, None)."""
    for p in ports:
        if not p:
            continue
        ms = probe(ip, p, timeout, mode)
        if ms is not None:
            return p, ms
    return None, None


# -------------------------------------------------------------------- запись

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


# ---------------------------------------------------------------------------
# Обновления приложения ProXvpn: раз в полчаса смотрим последний выпуск на GitHub
# и кладём APK/MSI + version.json рядом со списком (…/<секрет>/proxvpn/).
# Приложения сначала ищут обновление здесь, на вашем домене, и только потом на GitHub.
# ---------------------------------------------------------------------------
DEFAULT_UPDATES_REPO = "nims771/vpngate-client"


def _download(url: str, timeout: int = 120) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "ProXvpn-mirror", "Accept": "*/*"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def sync_updates(dest: str, repo: str, every: int = 1800) -> None:
    import json
    os.makedirs(dest, exist_ok=True)
    stamp = os.path.join(dest, ".checked")
    if os.path.exists(stamp) and time.time() - os.path.getmtime(stamp) < every:
        return
    with open(stamp, "w") as f:
        f.write(time.strftime("%Y-%m-%d %H:%M:%S"))
    rel = json.loads(_download(f"https://api.github.com/repos/{repo}/releases/latest", 30))
    assets = {a["name"]: a["browser_download_url"] for a in rel.get("assets", [])}
    if "version.json" not in assets:
        log(f"обновления: в выпуске {rel.get('tag_name')} нет version.json — пропускаем")
        return
    new_meta = _download(assets["version.json"], 30)
    cur_path = os.path.join(dest, "version.json")
    if os.path.exists(cur_path) and open(cur_path, "rb").read() == new_meta:
        return
    meta = json.loads(new_meta)
    for key in ("android", "windows"):
        entry = meta.get(key) or {}
        name = entry.get("file")
        if not name or "/" in name or name not in assets:
            continue
        data = _download(assets[name], 600)
        tmp = os.path.join(dest, name + ".tmp")
        with open(tmp, "wb") as f:
            f.write(data)
        os.chmod(tmp, 0o644)
        os.replace(tmp, os.path.join(dest, name))
        log(f"обновления: {name} ({len(data) // 1024} КБ)")
    tmp = cur_path + ".tmp"
    with open(tmp, "wb") as f:
        f.write(new_meta)
    os.chmod(tmp, 0o644)
    os.replace(tmp, cur_path)
    log(f"обновления: выпуск {rel.get('tag_name')} опубликован в {dest}")


def main() -> int:
    ap = argparse.ArgumentParser(description="Зеркало списка серверов VPN Gate (v2)")
    ap.add_argument("--out", required=True, help="куда сохранить CSV (например /var/www/vpngate/servers.csv)")
    ap.add_argument("--source", action="append",
                    help=f"CSV API (можно несколько, по порядку). По умолчанию {DEFAULT_SOURCE}")
    ap.add_argument("--site", action="append",
                    help="страница сайта со списком всех серверов (можно несколько). По умолчанию www.vpngate.net/en/")
    ap.add_argument("--no-site", action="store_true", help="не брать серверы с сайта, только API")
    ap.add_argument("--mode", choices=["sstp", "tls", "tcp"], default="sstp",
                    help="как проверять: sstp — как телефон (по умолчанию), tls или tcp — мягче")
    ap.add_argument("--timeout", type=float, default=6.0, help="таймаут проверки одного порта, с")
    ap.add_argument("--workers", type=int, default=64, help="сколько серверов проверять одновременно")
    ap.add_argument("--no-check", action="store_true", help="не проверять серверы, просто зеркалировать")
    ap.add_argument("--keep-openvpn-only", action="store_true",
                    help="оставлять серверы, у которых SSTP не отвечает (для OpenVPN)")
    ap.add_argument("--min-alive", type=int, default=10,
                    help="если живых меньше — сохранить непроверенный список (защита от сбоя сети)")
    ap.add_argument("--no-tls", action="store_true", help=argparse.SUPPRESS)  # совместимость с v1
    ap.add_argument("--updates-dir", help="куда класть обновления приложения (по умолчанию <папка списка>/proxvpn)")
    ap.add_argument("--updates-repo", default=DEFAULT_UPDATES_REPO, help="репозиторий GitHub с выпусками")
    ap.add_argument("--no-updates", action="store_true", help="не зеркалировать обновления приложения")
    args = ap.parse_args()
    if args.no_tls:
        args.mode = "tcp"

    if not args.no_updates:
        try:
            sync_updates(args.updates_dir or os.path.join(os.path.dirname(os.path.abspath(args.out)), "proxvpn"),
                         args.updates_repo)
        except Exception as e:  # noqa: BLE001
            log(f"обновления: ошибка: {e}")

    # ---- API
    api_rows = []
    for src in args.source or [DEFAULT_SOURCE]:
        try:
            api_rows = parse_rows(fetch(src, timeout=60))
            if api_rows:
                log(f"API: {len(api_rows)} серверов ({src})")
                break
            log(f"API {src}: пусто или формат не распознан")
        except Exception as e:  # noqa: BLE001
            log(f"API {src}: ошибка: {e}")

    # ---- сайт
    site = []
    if not args.no_site:
        for url in args.site or DEFAULT_SITES:
            try:
                site = parse_website(fetch(url, timeout=60))
                if site:
                    log(f"сайт: {len(site)} серверов, стран: {len({s['code'] for s in site})} ({url})")
                    break
                log(f"сайт {url}: таблица серверов не найдена (возможно, изменилась вёрстка)")
            except Exception as e:  # noqa: BLE001
                log(f"сайт {url}: ошибка: {e}")

    if not api_rows and not site:
        log("не удалось получить список ни из одного источника — старый файл не трогаем")
        return 1

    # ---- объединяем: кандидаты = (строка CSV, ip, список портов для SSTP)
    site_by_ip = {s["ip"]: s for s in site}
    candidates = []
    seen = set()
    for fields in api_rows:
        ep = endpoint(fields)
        if ep is None:
            continue
        ip, port, proto = ep
        s = site_by_ip.get(ip)
        ports = []
        if proto == "tcp":
            ports.append(port)
        if s:
            ports += [s["sstp_port"], s["se_tcp"], s["ovpn_tcp"]]
        ports += UDP_FALLBACK_PORTS if proto == "udp" and not s else []
        ports = [p for i, p in enumerate(ports) if p and p not in ports[:i]]
        candidates.append((fields, ip, ports, True))
        seen.add(ip)
    added = 0
    for s in site:
        if s["ip"] in seen:
            continue
        ports = [p for p in (s["sstp_port"], s["se_tcp"], s["ovpn_tcp"]) if p]
        if not ports:
            continue
        ports = [p for i, p in enumerate(ports) if p not in ports[:i]]
        candidates.append((website_row(s, ports[0]), s["ip"], ports, False))
        seen.add(s["ip"])
        added += 1
    if site:
        log(f"с сайта добавлено {added} серверов, которых нет в API")

    if args.no_check:
        rows = [with_sstp_hint(f, ports[0]) if ports else f for f, _, ports, _ in candidates]
        write_atomic(args.out, rows)
        log(f"сохранено {len(rows)} серверов без проверки → {args.out}")
        return 0

    # ---- проверка
    started = time.monotonic()
    alive = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = {pool.submit(find_port, ip, ports, args.timeout, args.mode): (fields, from_api)
                   for fields, ip, ports, from_api in candidates}
        for fut in concurrent.futures.as_completed(futures):
            fields, from_api = futures[fut]
            port, ms = fut.result()
            if port is not None:
                alive.append((with_sstp_hint(fields, port), ms))
            elif from_api and args.keep_openvpn_only:
                alive.append((fields, 99999))

    countries = len({f[6] for f, _ in alive})
    log(f"работают {len(alive)} из {len(candidates)} ({countries} стран), проверка {time.monotonic() - started:.0f} с")

    if len(alive) < args.min_alive:
        log(f"живых меньше {args.min_alive} — похоже на сбой сети, сохраняем непроверенный список")
        rows = [with_sstp_hint(f, ports[0]) if ports else f for f, _, ports, _ in candidates]
        write_atomic(args.out, rows)
        return 0

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
}

# ---------- обновление скрипта без вопросов ----------
update_mirror() {
    [ "$(id -u)" -eq 0 ] || die "Запустите от root: sudo bash $0 update"
    [ -f "$CONF_FILE" ] || die "Зеркало ещё не установлено — запустите: sudo bash $0"
    step "Обновление скрипта зеркала"
    write_mirror_script
    ok "Скрипт обновлён: $INSTALL_DIR/vpngate_mirror.py"
    # shellcheck disable=SC1090
    . "$CONF_FILE"
    # раздача обновлений приложения ProXvpn: …/<секрет>/proxvpn/
    local nconf
    nconf="$(nginx_conf_path)"
    mkdir -p "$DATA_DIR/proxvpn"
    # сжатие списка (в 3–4 раза меньше — важно в медленной сети)
    if [ -f "$nconf" ] && ! grep -q "gzip_types text/plain" "$nconf"; then
        cp "$nconf" "$nconf.bak"
        sed -i "s#^\(\s*\)alias $DATA_DIR/servers.csv;#&\n\1gzip on;\n\1gzip_comp_level 6;\n\1gzip_types text/plain;#" "$nconf"
        if nginx -t >/dev/null 2>&1; then
            systemctl reload nginx
            ok "nginx: включено сжатие списка"
        else
            mv "$nconf.bak" "$nconf"
            warn "nginx не принял сжатие — оставил прежние настройки"
        fi
    fi
    if [ -f "$nconf" ] && ! grep -q "/$SECRET/proxvpn/" "$nconf"; then
        cp "$nconf" "$nconf.bak"
        sed -i "s#^    location = /$SECRET/servers.csv {#    location /$SECRET/proxvpn/ {\n        alias $DATA_DIR/proxvpn/;\n        add_header Cache-Control \"no-cache\";\n    }\n&#" "$nconf"
        if nginx -t >/dev/null 2>&1; then
            systemctl reload nginx
            ok "nginx: добавлена раздача обновлений приложения"
        else
            mv "$nconf.bak" "$nconf"
            warn "nginx не принял изменения — оставил прежние настройки"
        fi
    fi
    info "Запускаю обновление списка (до пары минут)…"
    systemctl start "$SERVICE.service" || true
    journalctl -u "$SERVICE" -n 8 --no-pager 2>/dev/null | sed 's/^/   /' || true
    # shellcheck disable=SC1090
    . "$CONF_FILE"
    ok "Готово. Адрес списка прежний: $URL"
    exit 0
}

[ "${1:-}" = "uninstall" ] && uninstall
[ "${1:-}" = "update" ] && update_mirror

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
echo "Проверять серверы перед публикацией? Сервер подключится к каждому так же, как телефон"
echo "по SSTP, и оставит только рабочие. Список берётся из API и с сайта VPN Gate (больше стран)."
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
write_mirror_script
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
LOCATION_BLOCK="    location /$SECRET/proxvpn/ {
        alias $DATA_DIR/proxvpn/;
        add_header Cache-Control \"no-cache\";
    }
    location = /$SECRET/servers.csv {
        alias $DATA_DIR/servers.csv;
        default_type text/plain;
        gzip on;
        gzip_comp_level 6;
        gzip_types text/plain;
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
    обновить скрипт:         sudo bash $0 update
    удалить зеркало:         sudo bash $0 uninstall

EOF
