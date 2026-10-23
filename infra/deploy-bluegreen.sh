#!/bin/sh
# 운영 블루-그린 배포 (인프라 계획 7절). Jenkins 가 master 머지 때 실행한다. 직접 실행: sh infra/deploy-bluegreen.sh back|ai [rollback]
# 1) 쉬고 있는 색을 새 이미지로 띄우고 2) 상태 확인이 통과하면 3) nginx 를 새 색으로 돌린 뒤 4) 30초 뒤 옛 색을 끈다.
# 상태 확인이 실패하면 새 색만 내리고 nginx 는 그대로 둔다 (운영은 옛 색으로 계속).
# rollback: 꺼 둔 옛 색을 다시 켜고 nginx 를 되돌린다 (옛 색은 지난 배포 이미지 그대로 남아 있다).
# 어느 색이 운영인지는 /srv/tingle/nginx-state/<서비스>.conf 에 적힌다 (nginx 가 include, 없으면 blue).
# back 은 /api/ /ws/ 경로, ai 는 nginx 내부 8000 포트(별칭 ai)가 전환된다.
set -eu
svc=$1
mode=${2:-deploy}
case $svc in
  back) port=8080 health=/actuator/health ;;
  ai) port=8000 health=/health ;;
  *) echo "블루-그린 대상이 아님: $svc"; exit 1 ;;
esac
root=$(cd "$(dirname "$0")/.." && pwd)
dc="docker compose --env-file /srv/tingle/.env --env-file /srv/tingle/app.env -f $root/infra/docker-compose.yml"
state=/srv/tingle/nginx-state/$svc.conf

cur=none
if [ -f "$state" ]; then cur=blue; grep -q green "$state" && cur=green; fi
new=blue; [ "$cur" = blue ] && new=green
echo "$svc: 운영 $cur → $new ($mode)"

if [ "$mode" = rollback ]; then
  [ "$cur" != none ] || { echo "되돌릴 색이 없음"; exit 1; }
  $dc start "$svc-$new"
else
  $dc up -d --build --no-deps "$svc-$new"
fi

# 상태 확인: 2초 간격 최대 90초
i=0
until docker exec tingle-nginx-1 wget -q -O /dev/null "http://$svc-$new:$port$health" 2>/dev/null; do
  i=$((i + 1))
  if [ "$i" -ge 45 ]; then
    echo "$svc-$new 상태 확인 실패 — 새 색을 내리고 운영은 $cur 그대로"
    $dc stop "$svc-$new"
    exit 1
  fi
  sleep 2
done

# 전환: 상태 파일을 바꾸고 nginx 설정 검사 후 reload. 기존 연결은 옛 worker 가 끝까지 처리한다.
mkdir -p "$(dirname "$state")"
old=$(cat "$state" 2>/dev/null || true)
echo "set \$up $svc-$new:$port;" > "$state"
if ! docker exec tingle-nginx-1 nginx -t -q; then
  if [ -n "$old" ]; then echo "$old" > "$state"; else rm -f "$state"; fi
  $dc stop "$svc-$new"
  echo "nginx 설정 오류 — 되돌림"
  exit 1
fi
docker exec tingle-nginx-1 nginx -s reload
echo "$svc: nginx → $svc-$new"

# 정리: 옛 색은 30초 뒤 끈다 (graceful shutdown 20초 + 여유). 지우지 않고 멈춰만 둬서 rollback 에 쓴다.
if [ "$cur" != none ]; then
  sleep 30
  $dc stop -t 25 "$svc-$cur"
  echo "$svc-$cur 멈춤"
fi
