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
