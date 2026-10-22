# 서버 열린 포트

서버 `k15e106.p.ssafy.io` 에서 외부 접속을 허용한 포트다. 방화벽은 ufw 이고, 아래 외의 수신은 모두 차단한다.

기준일: 2026-10-22

| 포트 | 프로토콜 | 용도 | 비고 |
| --- | --- | --- | --- |
| 22 | TCP | SSH 접속 | 모든 IP 허용 |
| 80 | TCP | HTTP — 인증서 발급(certbot), HTTPS 로 넘기기 | 외부 접속 확인 |
| 443 | TCP | HTTPS — nginx | nginx·인증서 설치 후 확인 예정 |

DB(5432)·Jenkins(8080)·AI 서버(8000)·백엔드는 외부에 열지 않고 Docker 내부망으로만 연결한다. Docker 가 publish 한 포트는 ufw 를 우회하므로, compose 에서 호스트 포트는 nginx 의 80·443 만 연다.

포트를 바꾸면 이 표와 기준일을 함께 고친다. 서버에서 확인하는 명령:

```bash
sudo ufw status numbered
```
