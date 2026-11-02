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
    args = ap.parse_args()
    if args.no_tls:
        args.mode = "tcp"

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
