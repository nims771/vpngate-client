# Своё зеркало списка серверов

Скрипт `vpngate_mirror.py` работает на вашем сервере. Он раз в 15 минут скачивает список VPN Gate, проверяет каждый сервер (TCP и TLS, как при SSTP), выбрасывает неработающие и кладёт результат в файл. Ваш веб-сервер отдаёт этот файл по HTTPS, а приложение берёт список с вашего домена.

**Зачем это нужно:**
- vpngate.net может быть заблокирован, а ваш домен нет;
- список заранее очищен от мёртвых серверов, поэтому автовыбор быстрее находит рабочий;
- список идёт по HTTPS, и провайдер не видит, что вы скачиваете (видно только имя домена).

Пинг с телефона приложение всё равно меряет само. Задержка зависит от вашей сети, и сервер её не узнает.

Важно: ваш сервер должен находиться **вне** сети с блокировками (например, VPS за границей), иначе он сам не скачает список с vpngate.net.

---

## 1. Скрипт и проверка

```bash
sudo mkdir -p /opt/vpngate-mirror /var/www/vpngate
sudo curl -o /opt/vpngate-mirror/vpngate_mirror.py \
  https://raw.githubusercontent.com/nims771/vpngate-client/main/mirror/vpngate_mirror.py

# пробный запуск (займёт 10–30 секунд)
sudo python3 /opt/vpngate-mirror/vpngate_mirror.py --out /var/www/vpngate/servers.csv
head -c 300 /var/www/vpngate/servers.csv
```

В выводе должно быть что-то вроде `работают 87 из 98` и `сохранено 87 серверов`.

## 2. Автообновление раз в 15 минут (cron)

```bash
sudo crontab -e
```
Добавьте строку:
```
*/15 * * * * /usr/bin/python3 /opt/vpngate-mirror/vpngate_mirror.py --out /var/www/vpngate/servers.csv >> /var/log/vpngate-mirror.log 2>&1
```

## 3. Раздача по HTTPS (nginx)

Пусть домен называется `vpn.example.com` (замените на свой) и уже указывает на сервер.

Придумайте секретную часть пути, чтобы файл не нашли случайные сканеры:
```bash
openssl rand -hex 8      # например 3f9a1c7e5b2d4a60
```

`/etc/nginx/sites-available/vpngate-mirror`:
```nginx
server {
    listen 80;
    server_name vpn.example.com;
    location /.well-known/acme-challenge/ { root /var/www/html; }
    location / { return 301 https://$host$request_uri; }
}

server {
    listen 443 ssl http2;
    server_name vpn.example.com;

    ssl_certificate     /etc/letsencrypt/live/vpn.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/vpn.example.com/privkey.pem;

    # список серверов для приложения
    location = /3f9a1c7e5b2d4a60/servers.csv {
        alias /var/www/vpngate/servers.csv;
        default_type text/plain;
        add_header Cache-Control "public, max-age=300";
    }

    location / { return 404; }
}
```

```bash
sudo ln -s /etc/nginx/sites-available/vpngate-mirror /etc/nginx/sites-enabled/
sudo apt install -y certbot python3-certbot-nginx
sudo certbot certonly --nginx -d vpn.example.com
sudo nginx -t && sudo systemctl reload nginx

# проверка
curl -s https://vpn.example.com/3f9a1c7e5b2d4a60/servers.csv | head -3
```

Если на сервере вместо nginx стоит Caddy, достаточно одного блока (сертификат Caddy получит сам):
```
vpn.example.com {
    handle /3f9a1c7e5b2d4a60/servers.csv {
        rewrite * /servers.csv
        root * /var/www/vpngate
        file_server
    }
    respond 404
}
```

## 4. Адрес в приложении

**Настройки → Список серверов и пинг → Адрес списка**:
```
https://vpn.example.com/3f9a1c7e5b2d4a60/servers.csv
```
Нажмите **«Сохранить адрес»**, затем на вкладке **«Страны»** нажмите «Обновить».

Если хотите, чтобы этот адрес стоял в приложении сразу после установки, поменяйте `DEFAULT_URL` в `app/src/main/java/com/example/vpngateclient/data/Storage.kt`. GitHub соберёт новый APK автоматически.

---

## Параметры скрипта

| Параметр | Что делает |
|---|---|
| `--out ПУТЬ` | куда сохранить список (обязательно) |
| `--source URL` | откуда брать список; можно указать несколько, они пробуются по порядку |
| `--timeout 5` | сколько секунд ждать ответа одного сервера |
| `--workers 64` | сколько серверов проверять одновременно |
| `--no-tls` | проверять только TCP, без TLS |
| `--no-check` | не проверять серверы, просто зеркалировать |
| `--min-alive 10` | если работающих меньше этого числа, сохраняется полный список (защита от сбоя сети на сервере) |

Если vpngate.net не отвечает, старый файл не трогается, и приложение продолжает получать последний удачный список.
