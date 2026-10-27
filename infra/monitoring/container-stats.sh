#!/bin/sh
# 컨테이너별 메모리·CPU 를 15초마다 node-exporter textfile(/textfile/containers.prom)로 쓴다. Prometheus 는 node-exporter 에서 같이 가져간다.
# cAdvisor 를 쓰지 않는 이유: 이 서버 Docker 는 containerd 이미지 저장소(overlayfs snapshotter)라 cAdvisor 가 컨테이너를 못 찾는다 (2026-10-27 확인).
# 값은 cgroup v2 파일 그대로: 메모리 = memory.current - inactive_file (docker stats 와 같은 기준), CPU = cpu.stat usage_usec 누적.
# ponytail: 컨테이너 메모리·CPU 만. 네트워크·디스크 I/O 가 필요해지면 여기에 io.stat 을 더하거나 cAdvisor 가 snapshotter 를 지원할 때 되돌린다.
set -eu
out=/textfile/containers.prom
while true; do
  {
    echo '# TYPE tingle_container_memory_bytes gauge'
    echo '# TYPE tingle_container_cpu_seconds_total counter'
    docker ps --no-trunc --format '{{.ID}} {{.Names}}' | while read -r id name; do
      case $name in tingle-*) ;; *) continue ;; esac # Jenkins 검사용 임시 컨테이너(이름 무작위)는 뺀다
      d=/sys/fs/cgroup/system.slice/docker-$id.scope
      [ -r "$d/memory.current" ] || continue
      mem=$(awk -v cur="$(cat "$d/memory.current")" '$1 == "inactive_file" { print cur - $2 }' "$d/memory.stat")
      cpu=$(awk '$1 == "usage_usec" { printf "%.3f", $2 / 1000000 }' "$d/cpu.stat")
      echo "tingle_container_memory_bytes{name=\"$name\"} $mem"
      echo "tingle_container_cpu_seconds_total{name=\"$name\"} $cpu"
    done
  } > "$out.tmp"
  mv "$out.tmp" "$out"
  sleep 15
done
