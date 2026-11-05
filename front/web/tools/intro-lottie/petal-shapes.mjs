// 끝에 작은 삼각형 홈을 판 꽃잎 모양 20개(petal-shapes.html). 높이 100px(±50), 끝이 위(y = -50).
// 몸통 너비 bw · 홈 반폭 nw · 홈 깊이 d · 홈 위치 sx(비대칭) · 입술(홈 양옆 끝) 뾰족 sharp · 밑동 뾰족 point · 어깨 높이 sh
export function smoothPath(pts, corners = [], k = 0.2) {
  const n = pts.length, v = [], i = [], o = []
  pts.forEach((pt, j) => {
    const a = pts[(j - 1 + n) % n], b = pts[(j + 1) % n], c = corners.includes(j), h = [(b[0] - a[0]) * k * 50, (b[1] - a[1]) * k * 50]
    v.push([pt[0] * 50, pt[1] * 50]); o.push(c ? [0, 0] : h); i.push(c ? [0, 0] : [-h[0], -h[1]])
  })
  return { c: true, v, i, o }
}
function notched({ bw = 0.7, nw = 0.2, d = 0.18, sx = 0, sharp = false, point = false, sh = -0.55, lipL = 0, lipR = 0, k = 0.2 }) {
  const pts = [
    [sx - nw, -1 + lipL], [sx, -1 + d], [sx + nw, -1 + lipR],          // 홈: 왼쪽 입술 · 바닥 · 오른쪽 입술
    [bw * 0.92, sh], [bw, 0.1], [bw * 0.62, 0.72], [0, 1],              // 오른쪽 어깨 · 배 · 아래 · 밑동
    [-bw * 0.62, 0.72], [-bw, 0.1], [-bw * 0.92, sh],                   // 왼쪽
  ]
  const corners = [1, ...(sharp ? [0, 2] : []), ...(point ? [6] : [])]
  return smoothPath(pts, corners, k)
}
// 홈 여러 개: 끝을 살짝 둥근 선(y = top + curve·x²)으로 두고, 그 선을 따라 홈(x 중심 · nw 반폭 · d 깊이)을 차례로 판다
function multi({ notches, bw = 0.74, sharp = false, point = false, sh = -0.6, top = -1, curve = 0.5, k = 0.2 }) {
  const y = x => top + curve * x * x, pts = [], corners = []
  for (const n of notches) {
    if (sharp) corners.push(pts.length)
    pts.push([n.x - n.nw, y(n.x - n.nw) + (n.lipL ?? 0)])
    corners.push(pts.length); pts.push([n.x, y(n.x) + n.d])
    if (sharp) corners.push(pts.length)
    pts.push([n.x + n.nw, y(n.x + n.nw) + (n.lipR ?? 0)])
  }
  const base = pts.length + 3
  pts.push([bw * 0.92, sh], [bw, 0.1], [bw * 0.62, 0.72], [0, 1], [-bw * 0.62, 0.72], [-bw, 0.1], [-bw * 0.92, sh])
  if (point) corners.push(base)
  return smoothPath(pts, corners, k)
}
const two = (gap = 0.22, nw = 0.12, d = 0.16, d2 = d) => [{ x: -gap, nw, d }, { x: gap, nw, d: d2 }]
const three = (gap = 0.3, nw = 0.1, d = 0.14, dc = d) => [{ x: -gap, nw, d }, { x: 0, nw, d: dc }, { x: gap, nw, d }]
export function petalShapeGallery() {
  return [
    ['기본', '둥근 꽃잎 끝에 작은 V 홈', {}],
    ['얕은 홈', '홈을 아주 살짝만', { d: 0.1, nw: 0.15 }],
    ['깊은 홈', '홈을 깊게 파서 두 갈래', { d: 0.32, nw: 0.22 }],
    ['좁고 깊은 홈', '바늘처럼 좁게 깊이', { d: 0.28, nw: 0.1 }],
    ['넓고 얕은 홈', '넓게 살짝 파임', { d: 0.12, nw: 0.32 }],
    ['뾰족한 입술', '홈 양옆 끝이 뾰족', { sharp: true }],
    ['뾰족한 입술 · 깊은 홈', '벚꽃잎에 가장 가까움', { sharp: true, d: 0.3, nw: 0.24 }],
    ['통통한 몸통', '폭이 넓은 둥근 잎', { bw: 0.82 }],
    ['날씬한 몸통', '폭이 좁은 길쭉한 잎', { bw: 0.52, nw: 0.15 }],
    ['밑동 뾰족', '아래가 뾰족하게 모임', { point: true }],
    ['밑동 뾰족 · 통통', '하트를 뒤집은 듯', { point: true, bw: 0.8, d: 0.22 }],
    ['어깨 높음', '위쪽이 넓은 부채꼴', { sh: -0.82, bw: 0.74 }],
    ['어깨 낮음', '아래쪽이 넓은 물방울', { sh: -0.3, bw: 0.7 }],
    ['비대칭 홈', '홈이 한쪽으로 치우침', { sx: 0.12 }],
    ['비대칭 입술', '한쪽 입술이 더 높음', { lipL: 0.12, d: 0.24 }],
    ['비대칭 · 뾰족', '치우친 홈 + 뾰족한 입술', { sx: -0.1, sharp: true, lipR: 0.08, d: 0.24 }],
    ['아주 작은 홈', '거의 둥근데 끝만 콕', { d: 0.07, nw: 0.08 }],
    ['둥글둥글', '곡선을 더 부풀림', { k: 0.26, bw: 0.76 }],
    ['또렷한 곡선', '곡선을 덜 부풀려 단정하게', { k: 0.15, bw: 0.72 }],
    ['깊은 홈 · 날씬', '좁은 잎에 깊은 홈', { bw: 0.56, d: 0.3, nw: 0.18, sharp: true }],
    // --- 홈 2개 ---
    ['홈 2개', '끝에 작은 V 두 개', { m: { notches: two() } }],
    ['홈 2개 · 깊게', '두 홈을 깊게', { m: { notches: two(0.24, 0.13, 0.28) } }],
    ['홈 2개 · 얕게', '살짝 두 번 파임', { m: { notches: two(0.22, 0.12, 0.09) } }],
    ['홈 2개 · 넓은 간격', '두 홈을 양쪽으로 벌림', { m: { notches: two(0.36, 0.12, 0.16), bw: 0.8 } }],
    ['홈 2개 · 뾰족', '홈 사이 끝이 뾰족', { m: { notches: two(0.24, 0.13, 0.2), sharp: true } }],
    ['홈 2개 · 깊이 다름', '한쪽 홈만 깊게', { m: { notches: two(0.22, 0.12, 0.26, 0.1) } }],
    ['홈 2개 · 날씬', '좁은 잎에 두 홈', { m: { notches: two(0.16, 0.09, 0.16), bw: 0.56 } }],
    ['홈 2개 · 통통', '넓은 잎에 두 홈', { m: { notches: two(0.28, 0.14, 0.18), bw: 0.86 } }],
    ['홈 2개 · 밑동 뾰족', '아래가 뾰족하게 모임', { m: { notches: two(), point: true } }],
    ['홈 2개 · 부채꼴', '위가 넓은 잎에 두 홈', { m: { notches: two(0.3, 0.14, 0.2), sh: -0.85, bw: 0.78, curve: 0.25 } }],
    // --- 홈 3개 ---
    ['홈 3개', '끝에 작은 V 세 개', { m: { notches: three() } }],
    ['홈 3개 · 가운데 깊게', '가운데 홈만 깊음', { m: { notches: three(0.3, 0.1, 0.1, 0.26) } }],
    ['홈 3개 · 양쪽 깊게', '가운데는 얕고 양쪽이 깊음', { m: { notches: three(0.3, 0.1, 0.24, 0.1) } }],
    ['홈 3개 · 아주 작게', '끝에 잔잔한 톱니', { m: { notches: three(0.26, 0.07, 0.08) } }],
    ['홈 3개 · 뾰족', '왕관처럼 뾰족한 끝', { m: { notches: three(0.3, 0.1, 0.18), sharp: true } }],
    ['홈 3개 · 통통', '넓은 잎에 세 홈', { m: { notches: three(0.36, 0.12, 0.16), bw: 0.88 } }],
    ['홈 3개 · 부채꼴', '위가 넓은 잎에 세 홈', { m: { notches: three(0.36, 0.11, 0.18), sh: -0.85, bw: 0.82, curve: 0.25 } }],
    ['홈 3개 · 밑동 뾰족', '아래가 뾰족하게 모임', { m: { notches: three(), point: true } }],
    ['홈 3개 · 불규칙', '홈 크기 · 간격이 제각각', { m: { notches: [{ x: -0.34, nw: 0.08, d: 0.12 }, { x: -0.04, nw: 0.13, d: 0.24 }, { x: 0.3, nw: 0.09, d: 0.1 }] } }],
    ['홈 3개 · 깊게 뾰족', '깊고 뾰족한 세 갈래', { m: { notches: three(0.3, 0.11, 0.28), sharp: true, bw: 0.78 } }],
  ].map(([name, desc, opt], idx) => ({ key: `s${idx + 1}`, name: `${idx + 1} · ${name}`, desc, path: opt.m ? multi(opt.m) : notched(opt) }))
}

// 15 · 5 · 26 섞음(11-03): 15 = 한쪽 입술이 더 높음(lipL), 5 = 넓고 얕은 홈(nw 크게 · d 작게), 26 = 홈 2개 · 한쪽만 깊게
export function mixGallery() {
  return [
    ['섞음 A', '26 의 두 홈을 5 처럼 넓고 얕게 + 15 처럼 왼쪽 끝을 높게', { notches: [{ x: -0.24, nw: 0.18, d: 0.12, lipL: 0.1 }, { x: 0.24, nw: 0.16, d: 0.22 }] }],
    ['섞음 B', '왼쪽은 넓고 얕은 홈(5), 오른쪽은 좁고 깊은 홈(26), 왼쪽 끝 높게(15)', { notches: [{ x: -0.22, nw: 0.2, d: 0.1, lipL: 0.12 }, { x: 0.26, nw: 0.1, d: 0.24 }] }],
    ['섞음 C', '넓고 얕은 홈 하나 옆에 작고 깊은 홈', { notches: [{ x: -0.12, nw: 0.26, d: 0.12, lipL: 0.08 }, { x: 0.32, nw: 0.08, d: 0.2 }] }],
    ['섞음 D', '26 그대로에 바깥쪽 끝 높낮이만 다르게(15)', { notches: [{ x: -0.22, nw: 0.12, d: 0.26, lipL: 0.12 }, { x: 0.22, nw: 0.12, d: 0.1 }] }],
    ['섞음 E', '홈 하나: 5 의 넓고 얕은 홈 + 15 의 비대칭 끝', { notches: [{ x: 0, nw: 0.3, d: 0.14, lipL: 0.12 }] }],
    ['섞음 F', '26 의 두 홈을 넓게(5), 깊이 차이는 그대로', { notches: [{ x: -0.24, nw: 0.17, d: 0.24 }, { x: 0.24, nw: 0.17, d: 0.1 }], bw: 0.78 }],
    ['섞음 G', '부드럽게: 얕은 두 홈 + 왼쪽 끝 살짝 높게', { notches: [{ x: -0.22, nw: 0.2, d: 0.16, lipL: 0.14 }, { x: 0.24, nw: 0.13, d: 0.08 }] }],
    ['섞음 H', 'B 를 좌우 반대로 · 오른쪽 끝 높게', { notches: [{ x: -0.26, nw: 0.1, d: 0.24 }, { x: 0.22, nw: 0.2, d: 0.1, lipR: 0.12 }] }],
  ].map(([name, desc, opt], idx) => ({ key: `m${idx + 1}`, name, desc, path: multi(opt) }))
}
