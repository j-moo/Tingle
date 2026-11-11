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
# 실패: 새 색 로그 끝부분을 Jenkins 로그에 남기고 새 색만 내린 뒤 exit 1 → Jenkins 빌드 FAILURE.
# 환경변수·docker inspect 는 찍지 않는다. 앱 로그 줄 자체에 값이 섞일 수는 있다(예: Spring 설정 바인딩 오류) — docker logs · Loki 에 이미 있는 그 로그이고 따로 거르지 않는다.
logs() {
  echo "--- $svc-$new 로그 마지막 100줄 ---"
  $dc logs --no-color --tail 100 "$svc-$new" || true
}
fail() {
  echo "$1"
  logs
  $dc stop "$svc-$new"
  exit 1
}
# 새 색 상태 확인: 2초 간격 최대 $1 번. 끝까지 실패하면 1 (set -e 에 걸리지 않게 `wait_healthy N || ...` 로 쓴다).
wait_healthy() {
  i=1
  until docker exec tingle-nginx-1 wget -q -O /dev/null "http://$svc-$new:$port$health" 2>/dev/null; do
    [ "$i" -lt "$1" ] || return 1
    i=$((i + 1))
    sleep 2
  done
}
restore_state() { if [ -n "$old" ]; then echo "$old" > "$state"; else rm -f "$state"; fi; }

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

# 상태 확인: 최대 90초
wait_healthy 45 || fail "$svc-$new 상태 확인 실패 — 새 색을 내리고 운영은 $cur 그대로"

# 전환: 상태 파일을 바꾸고 nginx 설정 검사 후 reload. 기존 연결은 옛 worker 가 끝까지 처리한다.
mkdir -p "$(dirname "$state")"
old=$(cat "$state" 2>/dev/null || true)
echo "set \$up $svc-$new:$port;" > "$state"
if ! docker exec tingle-nginx-1 nginx -t -q; then
  restore_state
  fail "nginx 설정 오류 — 되돌림"
fi
docker exec tingle-nginx-1 nginx -s reload
echo "$svc: nginx → $svc-$new"

# 재확인 후 정리: 30초(graceful shutdown 20초 + 여유) 뒤 옛 색을 끄기 직전에 새 색이 아직 살아 있는지 3번 본다.
# 전환 뒤 죽었으면 nginx 를 옛 색으로 되돌리고 새 색을 내린 뒤 실패 (옛 색은 계속 켜져 있다).
# nginx 되돌리기마저 실패하면 nginx 는 아직 새 색을 보므로 새 색을 내리지 않고, 상태 파일도 새 색으로 맞춰 둔 채 실패한다 (수동 확인).
# 옛 색은 지우지 않고 멈춰만 둬서 rollback 에 쓴다. 첫 배포(옛 색 없음)는 되돌릴 곳도 끌 것도 없어 건너뛴다.
if [ "$cur" != none ]; then
  sleep 30
  wait_healthy 3 || {
    restore_state
    if docker exec tingle-nginx-1 nginx -t -q && docker exec tingle-nginx-1 nginx -s reload; then
      fail "$svc-$new 재확인 실패 — nginx 를 $cur 로 되돌리고 새 색을 내림"
    fi
    echo "set \$up $svc-$new:$port;" > "$state"
    echo "$svc-$new 재확인 실패, nginx 되돌리기 실패 — 수동 확인 필요 (새 색은 켜 둠)"
    logs
    exit 1
  }
  $dc stop -t 25 "$svc-$cur"
  echo "$svc-$cur 멈춤"
fi
