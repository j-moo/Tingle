// 랜딩 인트로 Lottie 생성기 — After Effects 없이 글꼴 외곽선(path)으로 Lottie JSON 을 만든다.
// 실행(front/web 에서): pnpm --dir tools/intro-lottie install && pnpm --dir tools/intro-lottie build
//   → src/landing/sections/Intro/intro.json
// 장면: "진동" 팝 · 부르르 → "이 오면" 스프링 → 꽃잎이 올라와 덮음 → "만남이 시작된다" → 5칸 뒤집기 → tingle 로고 · "스친 순간, 새로운 시작."
// 장면 값은 아래 SCENE, 등장 모션 후보는 gallery.mjs · enter-gallery.mjs, 꽃잎 모양은 petal-shapes.mjs
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import opentype from 'opentype.js'
import { gallery } from './gallery.mjs'
import { enterGallery } from './enter-gallery.mjs'
import { petalShapeGallery } from './petal-shapes.mjs'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const FONT_DIR = path.join(HERE, 'fonts')
const font = file => opentype.loadSync(path.join(FONT_DIR, file))   // tools/intro-lottie/fonts (SIL OFL, OFL-*.txt)
const OUT_FILE = path.join(HERE, '../../src/landing/sections/Intro/intro.json')

const FPS = 60, W = 1600, H = 900
const FONTS = {
  pretendard: 'Pretendard-Bold.ttf',   // 프리텐다드(SIL OFL) — Regular · Medium · Bold · Black 등 굵기별 파일
  nunito: 'Nunito-Black.ttf',          // Nunito Black 900(SIL OFL, Google Fonts) — 로고 "tingle"
}
const WHITE = [1, 1, 1, 1]
// 진동: 휴대폰처럼 부르르(start 부터 frames 동안) 두 번. 회전 없이 매 프레임 작게 좌우로 덜덜 떤다(위아래 y: 0)
const BUZZ = { bursts: [{ start: 12, frames: 24 }, { start: 44, frames: 24 }], x: 5, y: 0, step: 1 }   // start 는 등장이 끝난 뒤부터 센다

// 키프레임 묶음: [시각, 값, 곡선('hold' 면 다음 키까지 그대로)]
const OUT = { o: { x: [0.22], y: [1] }, i: { x: [0.36], y: [1] } }          // 빠르게 시작해 부드럽게 멈춤
const kf = (frames, ease = OUT) => ({ a: 1, k: frames.map(([t, v, e], idx) => (idx < frames.length - 1 ? { t, s: v, ...(e === 'hold' ? { h: 1 } : { o: (e ?? ease).o, i: (e ?? ease).i }) } : { t, s: v })) })
const still = v => ({ a: 0, k: v })

// opentype 경로(M/L/Q/C/Z)를 Lottie 베지어(정점 v, 들어오는 손잡이 i, 나가는 손잡이 o — 정점 기준 상대값)로 바꾼다
function contours(cmds) {
  const out = []
  let cur = null, P = null
  const sub = (a, b) => [a[0] - b[0], a[1] - b[1]]
  for (const c of cmds) {
    if (c.type === 'M') { cur = { v: [[c.x, c.y]], i: [[0, 0]], o: [] }; P = [c.x, c.y]; continue }
    if (c.type === 'Z') {
      const n = cur.v.length
      cur.o.push([0, 0])
      const last = cur.v[n - 1], first = cur.v[0]
      if (n > 1 && Math.abs(last[0] - first[0]) < 1e-6 && Math.abs(last[1] - first[1]) < 1e-6) {
        cur.i[0] = cur.i[n - 1]; cur.v.pop(); cur.i.pop(); cur.o.pop()   // 시작점과 같은 마지막 점은 합친다
      }
      out.push(cur); cur = null; continue
    }
    const N = [c.x, c.y]
    let c1, c2
    if (c.type === 'L') { c1 = P; c2 = N }
    else if (c.type === 'C') { c1 = [c.x1, c.y1]; c2 = [c.x2, c.y2] }
    else if (c.type === 'Q') { // 2차 → 3차
      const q = [c.x1, c.y1]
      c1 = [P[0] + (2 / 3) * (q[0] - P[0]), P[1] + (2 / 3) * (q[1] - P[1])]
      c2 = [N[0] + (2 / 3) * (q[0] - N[0]), N[1] + (2 / 3) * (q[1] - N[1])]
    }
    cur.o.push(sub(c1, P)); cur.v.push(N); cur.i.push(sub(c2, N)); P = N
  }
  return out
}

// 글자 하나 = 레이어 하나. 줄 가운데가 화면 가운데, 글자 실제 높이의 가운데가 centerY 에 오게 놓는다.
// 기준점(anchor)은 글자 한가운데 — 크기가 글자 가운데를 중심으로 바뀌게
function textLayers(text, f, size, centerY, firstInd, parent, entrance, frames) {
  const scale = size / f.unitsPerEm
  const glyphs = f.stringToGlyphs(text)
  const xs = []
  let x = 0
  glyphs.forEach((g, k) => {
    xs.push(x)
    x += g.advanceWidth * scale + (k < glyphs.length - 1 ? f.getKerningValue(g, glyphs[k + 1]) * scale : 0)
  })
  const box = f.getPath(text, 0, 0, size).getBoundingBox()      // 실제 잉크 높이로 세로 가운데
  const left = W / 2 - x / 2, baseline = centerY - (box.y1 + box.y2) / 2
  return glyphs.map((g, k) => {
    const path = g.getPath(0, 0, size), gb = path.getBoundingBox()
    const a = [(gb.x1 + gb.x2) / 2, (gb.y1 + gb.y2) / 2]
    const home = [left + xs[k] - W / 2 + a[0], baseline - H / 2 + a[1]]   // 부모(화면 가운데) 기준 제자리
    const e = entrance ? entrance.make(k, glyphs.length, home) : {}
    return {
      ddd: 0, ind: firstInd + k, ty: 4, parent, nm: `glyph ${text[k]}`, sr: 1,
      ks: { o: e.o ?? still(100), r: e.r ?? still(0), p: e.p ?? still([...home, 0]), a: still([...a, 0]), s: e.s ?? still([100, 100, 100]) },
      ao: 0,
      shapes: [{ ty: 'gr', nm: text[k], it: [
        ...contours(path.commands).map(c => ({ ty: 'sh', ks: still({ i: c.i, o: c.o, v: c.v, c: true }) })),
        { ty: 'fl', c: still(WHITE), o: still(100), r: 1 },
        { ty: 'tr', p: still([0, 0]), a: still([0, 0]), s: still([100, 100]), r: still(0), o: still(100), sk: still(0), sa: still(0) },
      ] }],
      ip: 0, op: frames, st: 0, bm: 0,
    }
  })
}

// 진동 키프레임: 묶음(null) 하나를 흔들고 글자는 그 자식으로 따라간다. offset = 등장이 끝나는 프레임
function buzzFrames(offset) {
  const p = []
  const at = (t, dx, dy) => p.push({ t, s: [dx, dy, 0] })   // 등장 묶음(화면 가운데) 기준 이동량
  let seed = 7
  const rand = () => ((seed = (seed * 16807) % 2147483647) / 2147483647) * 2 - 1   // 매번 같은 결과가 나오는 난수
  at(0, 0, 0)
  for (const b of BUZZ.bursts) {
    at(offset + b.start, 0, 0)
    for (let t = BUZZ.step, n = 0; t < b.frames; t += BUZZ.step, n++) {
      const side = n % 2 === 0 ? 1 : -1                                  // 좌우는 번갈아, 크기는 조금씩 다르게
      at(offset + b.start + t, side * BUZZ.x * (0.6 + 0.4 * Math.abs(rand())), BUZZ.y * rand())
    }
    at(offset + b.start + b.frames, 0, 0)
  }
  const LINEAR = { o: { x: [0], y: [0] }, i: { x: [1], y: [1] } }        // 키프레임 사이는 직선(빠른 떨림)
  return { p: { a: 1, k: p.map((k, n) => (n < p.length - 1 ? { ...k, ...LINEAR } : k)) } }
}

const dump = json => JSON.stringify(json, (k, v) => (typeof v === 'number' ? Math.round(v * 100) / 100 : v))

// --- 인트로 장면(11-03): "진동" 팝 → "진동" 부르르(두 번) → 왼쪽으로 밀림 + "이 오면" 기울여 들어옴 ---------------
const SCENE = {
  first: '진동', rest: '이 오면', font: 'pretendard', size: 200,
  pop: { start: 1, dur: 16 },                 // "진동" 한 번에 팝
  slide: { start: 96, dur: 16 },              // "진동" 이 왼쪽으로 밀림(빠르게 — 들어오는 글자와 겹치지 않게)
  enter: { start: 102, dur: 22, from: 420, tilt: 18 },   // "이 오면" 오른쪽에서 기울어진 채 들어와 바로 섬(위치는 지나치지 않고 기울기만 살짝 튕김)
  enterPick: 'e6',                            // "이 오면" 들어오는 모션 = enters.html 6번 스프링(두 번 출렁이며 자리 잡음, 11-03 선택). null 이면 위 enter 기본값
  rise: { start: 136, spread: 70, count: 22, size: [30, 260], dur: [60, 100] },   // "진동이 오면" 완성 직후 크기 · 모양이 제각각인 꽃잎이 아래에서 올라옴(작은 건 글자 뒤, 큰 건 글자 앞)
  hero: { start: 196, dur: 58, scale: 46, color: [1, 0.84, 0.89] },   // 마지막에 꽃잎 하나가 올라오며 커져 화면 전체를 덮음(당근 브랜드 사이트 인트로 끝처럼)
  ones: [   // 속도: 시안 8번 '빠름 · 느림 번갈아'(13.3 · 26.7 px/프레임, 11-03 선택). 꽃잎 한 장씩(참고 이미지의 떨어진 꽃잎, size = 높이 px. flower: true 면 꽃 한 송이, size = 지름) 아래에서 위로 비스듬히 올라감 — 당근 브랜드 사이트 도형 모션(x = 화면 가로 비율, drift = 가로 이동 px, dir · tilt = 기우는 방향 · 각도). 빈 배열이면 없음
    { start: 124, speed: 13.3, x: 0.8, drift: -150, dir: 1, tilt: 14, size: 110, front: true, color: [1, 0.8, 0.83], alpha: 100 },    // 첫 번째 꽃잎
    { start: 135, speed: 26.7, x: 0.2, drift: 120, dir: -1, tilt: 12, size: 150, front: true, color: [1, 0.8, 0.83], alpha: 100 },  // 두 번째 꽃잎 — 나머지 5장은 이 뒤로 차례로, 각자 다른 속도(speed px/프레임)로 끝까지 등속
    { start: 156, speed: 13.3, x: 0.38, drift: 90, dir: -1, tilt: 16, size: 240, front: true, color: [1, 0.86, 0.88], alpha: 100 },
    { start: 162, speed: 26.7, x: 0.62, drift: -80, dir: 1, tilt: 10, size: 380, front: true, color: [0.99, 0.76, 0.8], alpha: 100 },
    { start: 168, speed: 13.3, x: 0.08, drift: 140, dir: 1, tilt: 18, size: 290, front: true, color: [1, 0.83, 0.86], alpha: 100 },
    { start: 174, speed: 26.7, x: 0.93, drift: -130, dir: -1, tilt: 12, size: 210, front: true, color: [1, 0.89, 0.9], alpha: 100 },
    { start: 180, speed: 13.3, x: 0.5, drift: 60, dir: 1, tilt: 14, size: 320, front: true, color: [1, 0.8, 0.83], alpha: 100 },
    { start: 192, speed: 26.7, x: 0.22, drift: 70, dir: 1, tilt: 13, size: 660, front: true, color: [1, 0.85, 0.87], alpha: 100 },   // 다섯 장 뒤로 더 큰 6장
    { start: 200, speed: 13.3, x: 0.78, drift: -90, dir: -1, tilt: 11, size: 520, front: true, color: [0.99, 0.78, 0.82], alpha: 100 },
    { start: 207, speed: 26.7, x: 0.45, drift: 50, dir: 1, tilt: 15, size: 720, front: true, color: [1, 0.87, 0.9], alpha: 100 },
    { start: 214, speed: 13.3, x: 0.05, drift: 120, dir: -1, tilt: 17, size: 400, front: true, color: [1, 0.82, 0.85], alpha: 100 },
    { start: 221, speed: 26.7, x: 0.95, drift: -110, dir: 1, tilt: 12, size: 690, front: true, color: [0.99, 0.79, 0.83], alpha: 100 },
    { start: 228, speed: 13.3, x: 0.6, drift: -40, dir: -1, tilt: 14, size: 560, front: true, color: [1, 0.86, 0.89], alpha: 100 },
  ],
  cover: { start: 233, dur: 40, size: 3000, x: 0.5, tilt: 8, color: [1, 0.9, 0.92] },   // 마지막: 이미 거대한 꽃잎 하나(높이 size px)가 6장과 겹치며 아래에서 올라와 화면을 분홍으로 가득 채움(null 이면 없음)
  bg: [1, 1, 1], ink: [0.2, 0.19, 0.23],   // 인트로 바탕 흰색 · 글자 검정(11-03). 마지막 tingle 화면도 흰 바탕
  phrase: { text: '만남이 시작된다', font: 'pretendard', size: 150, color: [0.2, 0.19, 0.23], start: 250, entrance: 'g14', hold: 30 },   // 덮인 화면 위 문구(phrases.html 14번 솟아오름, 11-03 선택). hold = 다 나온 뒤 머무는 프레임. null 이면 없음
  flip: { strips: 5, after: 15, gaps: [9, 7, 5, 4], halves: [14, 12, 10, 9, 8], hold: 45,   // 오른쪽 첫 칸은 천천히, 갈수록 빨리(gaps = 칸 사이 시작 간격, halves = 칸마다 반 바퀴 프레임)
    back: [1, 1, 1], logoSize: 300, tagline: { text: '스친 순간, 새로운 시작.', size: 64, gap: 70, after: 6, dur: 30, hold: 50, color: [0.35, 0.34, 0.38] }, shine: { delay: 12, bursts: [{ at: 0, len: 24 }, { at: 32, len: 24 }], hold: 40 } },   // shine: 한 줄이 다 나온 delay 뒤 tingle 이 부르르 떨며 연보라 · 민트 잔상(SCENE.logo 색 · 어긋남)이 붙어 로고 완성   // 문구가 다 나온 뒤(after) 화면을 세로 5칸으로 나눠 오른쪽 칸부터 gap 간격으로 카드처럼 뒤집음(half = 반 바퀴 프레임). 뒷면 = 흰 바탕 + 검정 tingle(Nunito). null 이면 없음
  showLogo: false,   // 인트로(preview.html)에 로고를 넣을지(11-03 뺌). logos.html 시안은 이 값과 상관없이 아래 logo 설정으로 만든다
  logo: {   // 화면이 덮인 뒤 "tingle" 이 팝 → 좌우로 부르르(두 번). 뒤에 연보라 · 민트 글자가 어긋나 겹치고, 떨 때는 늦게 따라와 잔상(참고 이미지)
    text: 'tingle', font: 'nunito', size: 260, round: 0, start: 250, amp: 12, color: [0.2, 0.19, 0.23],   // Nunito Black(900, 11-03 설치). round = 같은 색 둥근 외곽선 두께(0 이면 없음)
    bursts: [{ at: 14, len: 24 }, { at: 46, len: 24 }],                            // 시작 기준 프레임 · 길이(2프레임마다 반대로, 점점 작게)
    trails: [{ d: 6, off: [12, 7], color: [0.66, 0.8, 0.71] }, { d: 3, off: [-12, -7], color: [0.78, 0.71, 0.86] }],   // 민트(오른쪽 아래) · 연보라(왼쪽 위), d = 늦게 따라오는 프레임
  },
  petals: false,                              // 꽃잎(올라오는 꽃잎 + 덮는 한 장) 켜기/끄기(11-03 끔)
  buzzAfter: 18,                              // 진동(BUZZ)을 시작할 기준 프레임 — 팝 직후. 진동이 끝난 뒤(약 96프레임) 밀림 · 등장
}
// 꽃잎 모양: 끝이 뾰족하고 배가 둥근 4점 잎. 높이 100px 기준(크기 %로 줄임)
const LEAF = (() => {
  const u = pts => pts.map(([a, b]) => [a * 50, b * 50])
  return { c: true, v: u([[0, -1], [0.6, 0.15], [0, 1], [-0.6, 0.15]]), i: u([[-0.2, 0.3], [0, -0.55], [0.35, 0], [0, 0.45]]), o: u([[0.2, 0.3], [0, 0.45], [-0.35, 0], [0, -0.55]]) }
})()
const SMOOTH = { o: { x: [0.4], y: [0] }, i: { x: [0.6], y: [1] } }
const anim3 = (keys, map) => ({ a: 1, k: keys.map(([t, v], n) => ({ t, s: map(v), ...(n < keys.length - 1 ? SMOOTH : {}) })) })
// 꽃잎 모양 5종(높이 100px, 끝이 위). 점 사이 접선은 이웃 점으로 자동 계산, corners 는 뾰족한 점
function smoothPath(pts, corners = [], k = 0.2) {
  const n = pts.length, v = [], i = [], o = []
  pts.forEach((pt, j) => {
    const a = pts[(j - 1 + n) % n], b = pts[(j + 1) % n], c = corners.includes(j), h = [(b[0] - a[0]) * k * 50, (b[1] - a[1]) * k * 50]
    v.push([pt[0] * 50, pt[1] * 50]); o.push(c ? [0, 0] : h); i.push(c ? [0, 0] : [-h[0], -h[1]])
  })
  return { c: true, v, i, o }
}
const PETAL_SHAPES = [
  LEAF,
  smoothPath([[-0.24, -0.98], [0, -0.78], [0.24, -0.98], [0.56, -0.3], [0.32, 0.6], [0, 1], [-0.32, 0.6], [-0.56, -0.3]], [1, 5]),   // 끝이 파인 벚꽃잎
  smoothPath([[0, -1], [0.62, -0.4], [0.55, 0.45], [0, 1], [-0.55, 0.45], [-0.62, -0.4]]),                                          // 둥근 잎
  smoothPath([[0, -1], [0.3, -0.3], [0.22, 0.5], [0, 1], [-0.22, 0.5], [-0.3, -0.3]], [0]),                                          // 가는 잎
  smoothPath([[-0.35, -0.95], [0, -0.6], [0.35, -0.95], [0.65, -0.4], [0, 1], [-0.65, -0.4]], [1, 4]),                              // 하트 잎
]
const PINKS2 = [[1, 0.78, 0.84], [1, 0.86, 0.9], [0.98, 0.66, 0.77], [1, 0.92, 0.94], [0.96, 0.58, 0.72]]
const shapeLayer = (ind, nm, shape, color, ks, ip, op) => ({
  ddd: 0, ind, ty: 4, nm, sr: 1, ks, ao: 0,
  shapes: [{ ty: 'gr', nm: 'petal', it: [
    { ty: 'sh', ks: still(shape) }, { ty: 'fl', c: still([...color, 1]), o: still(100), r: 1 },
    { ty: 'tr', p: still([0, 0]), a: still([0, 0]), s: still([100, 100]), r: still(0), o: still(100), sk: still(0), sa: still(0) },
  ] }],
  ip, op, st: 0, bm: 0,
})
// 올라오는 꽃잎: 화면 아래 밖에서 위 밖으로, 좌우로 살랑이며 천천히 돈다. 크기가 클수록 빨리(가까워 보이게). 작은 건 back(글자 뒤)
function riseLayers(cfg, frames, firstInd) {
  let seed = 61
  const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647
  const lerp = (a, b, t) => a + (b - a) * t
  const front = [], back = []
  for (let n = 0; n < cfg.count; n++) {
    const size = lerp(cfg.size[0], cfg.size[1], rnd() ** 1.6)                // 작은 꽃잎이 더 많게
    const near = (size - cfg.size[0]) / (cfg.size[1] - cfg.size[0])
    const t0 = cfg.start + Math.round(rnd() * cfg.spread), dur = Math.round(lerp(cfg.dur[1], cfg.dur[0], near) * lerp(0.9, 1.1, rnd()))
    const x0 = lerp(0.06, 0.94, (n + rnd()) / cfg.count) * W, drift = lerp(-160, 160, rnd()), sway = lerp(20, 70, rnd()), waves = 1 + rnd() * 1.5
    const rot0 = lerp(-40, 40, rnd()), spin = lerp(-120, 120, rnd()), squash = lerp(0.75, 1.05, rnd())
    const STEPS = 6
    const p = Array.from({ length: STEPS + 1 }, (_, k) => { const q = k / STEPS; return [t0 + Math.round(dur * q), [x0 + drift * q + Math.sin(q * waves * Math.PI * 2) * sway, lerp(H + size, -size, q)]] })
    const sc = Array.from({ length: STEPS + 1 }, (_, k) => { const q = k / STEPS; return [t0 + Math.round(dur * q), [size * squash * (0.75 + 0.25 * Math.cos(q * Math.PI * 2 * waves + n)), size]] })   // 살짝 뒤집히듯
    const ks = { o: still(100), r: anim3([[t0, rot0], [t0 + dur, rot0 + spin]], v => [v]), p: anim3(p, ([x, y]) => [x, y, 0]), a: still([0, 0, 0]), s: anim3(sc, ([a, b]) => [a, b, 100]) }
    const layer = shapeLayer(firstInd + n, `rise ${n + 1}`, PETAL_SHAPES[n % PETAL_SHAPES.length], PINKS2[n % PINKS2.length], ks, t0, Math.min(frames, t0 + dur + 1))
    ;(size < 90 ? back : front).push(layer)
  }
  return { front, back }
}
// 둥근 꽃잎(살짝 찌그러진 알 모양, 높이 100px)
const BLOB = smoothPath([[0.05, -1], [0.6, -0.78], [0.82, -0.12], [0.62, 0.62], [0.08, 1], [-0.5, 0.8], [-0.78, 0.1], [-0.6, -0.7]], [], 0.19)
// 꽃 한 송이(참고 이미지): 둥근 반투명 꽃잎 5장이 겹쳐(겹친 곳이 진해짐) 가운데에 연보라 꽃술. 지름 200px 기준
function flowerShapes(cfg, t0, t1) {
  const tr = (p, r = 0, sc = [100, 100]) => ({ ty: 'tr', p: still(p), a: still([0, 0]), s: still(sc), r: still(r), o: still(100), sk: still(0), sa: still(0) })
  const LILAC = [0.66, 0.58, 0.84, 1]
  // 젤리처럼 출렁임: 꽃잎마다 12프레임마다 가로 · 세로를 ±5% 엇갈리게(당근 도형은 외곽선을 10프레임(50fps)마다 바꿈)
  const jelly = (k, [bx, by]) => { if (t0 === undefined) return still([bx, by]); const list = []
    for (let t = t0, n = 0; t <= t1; t += 12, n++) { const w = Math.sin(n * 1.7 + k * 1.3) * 5; list.push({ t, s: [bx + w, by - w], ...(t + 12 <= t1 ? { o: { x: [0.45, 0.45], y: [0, 0] }, i: { x: [0.55, 0.55], y: [1, 1] } } : {}) }) }
    return { a: 1, k: list } }
  const petals = Array.from({ length: 5 }, (_, k) => {
    const a = -90 + k * 72 + [0, 6, -4, 5, -7][k], d = 38, rad = a * Math.PI / 180              // 꽃잎마다 각도 · 크기를 살짝 다르게(손그림 느낌)
    return { ty: 'gr', nm: `petal ${k + 1}`, it: [{ ty: 'sh', ks: still(BLOB) }, { ty: 'fl', c: still([...cfg.color, 1]), o: still(cfg.alpha), r: 1 },
      { ...tr([Math.cos(rad) * d, Math.sin(rad) * d], a + 90), s: jelly(k, [[96, 92, 100, 94, 98][k], [100, 96, 104, 98, 94][k]]) }] }
  })
  const stamen = { ty: 'gr', nm: 'stamen', it: [
    ...Array.from({ length: 6 }, (_, k) => { const a = k * 60 + 15, r = a * Math.PI / 180, L = 15 + (k % 2) * 3
      return { ty: 'gr', it: [
        { ty: 'sh', ks: still({ c: false, v: [[0, 0], [Math.cos(r) * L, Math.sin(r) * L]], i: [[0, 0], [0, 0]], o: [[0, 0], [0, 0]] }) },
        { ty: 'st', c: still(LILAC), o: still(100), w: still(2), lc: 2, lj: 2 },
        { ty: 'el', p: still([Math.cos(r) * L, Math.sin(r) * L]), s: still([8, 8]) }, { ty: 'fl', c: still(LILAC), o: still(100), r: 1 }, tr([0, 0])] } }),
    { ty: 'el', p: still([0, 0]), s: still([7, 7]) }, { ty: 'fl', c: still([1, 1, 1, 1]), o: still(85), r: 1 }, tr([0, 0])] }
  return [stamen, ...petals]
}
// 꽃잎 한 장(참고 이미지의 떨어진 꽃잎 모양)
// 꽃잎 모양: petal-shapes.html 의 5번(넓고 얕은 홈) · 15번(비대칭 입술) · 26번(홈 2개 · 깊이 다름)을 차례로 돌려 씀(11-03 선택)
const PETAL_PICK = ['s5', 's15', 's26'].map(k => petalShapeGallery().find(v => v.key === k).path)
function petalShapes(cfg) {
  return [{ ty: 'gr', nm: 'petal', it: [{ ty: 'sh', ks: still(cfg.shape ?? BLOB) }, { ty: 'fl', c: still([...cfg.color, 1]), o: still(cfg.alpha), r: 1 },
    { ty: 'tr', p: still([0, 0]), a: still([0, 0]), s: still([100, 100]), r: still(0), o: still(100), sk: still(0), sa: still(0) }] }]   // 출렁임 뺌(11-03)
}
// 꽃 한 송이(flower: true) / 꽃잎 한 장: 화면 아래 밖에서 위 밖으로, 좌우로 살랑이며 천천히 기울어진다
function onePetal(cfg, frames, ind) {
  // 당근 브랜드 사이트(Intro_3bubbles) 수치를 옮김: 위치는 키 2개 등속 대각선, 회전은 0 → 기울기 → 반대로 조금(완만한 기우뚱),
  // 가로만 100 → 90 → 100% 로 숨 쉼, 모양은 짧은 간격으로 출렁임(flowerShapes 의 꽃잎별 크기 키)
  const { start: t0, size } = cfg, dur = cfg.speed ? Math.round((H + size + 40) / cfg.speed) : cfg.dur   // speed = 프레임당 px(올라오는 속도 그대로 위로 빠져나감)
  const t1 = t0 + dur, tm = t0 + Math.round(dur / 2)
  const LIN = { o: { x: [0], y: [0] }, i: { x: [1], y: [1] } }                          // 완전 등속
  const E1 = { o: { x: [0.17], y: [0.17] }, i: { x: [0.67], y: [1] } }, E2 = { o: { x: [0.33], y: [0] }, i: { x: [0.83], y: [0.83] } }
  const keys = (list, map) => ({ a: 1, k: list.map(([t, v, e], n) => ({ t, s: map(v), ...(n < list.length - 1 ? e : {}) })) })
  const d = size / 2, unit = cfg.flower ? size / 2 : size                                // d = 반지름(위아래로 화면 밖에서 시작 · 끝)
  const ks = {
    o: still(100),
    p: keys([[t0, [W * cfg.x, H + d + 20], LIN], [t1, [W * cfg.x + cfg.drift, -d - 20]]], ([x, y]) => [x, y, 0]),
    r: keys([[t0, 0, E1], [tm, cfg.dir * cfg.tilt, E2], [t1, -cfg.dir * cfg.tilt * 0.5]], v => [v]),
    s: keys([[t0, 100, E1], [tm, 90, E2], [t1, 100]], v => [unit * v / 100, unit, 100]),   // 꽃: size = 지름(모양은 200px 기준), 꽃잎: size = 높이(모양은 100px 기준)
    a: still([0, 0, 0]),
  }
  return { ddd: 0, ind, ty: 4, nm: 'flower', sr: 1, ks, ao: 0, shapes: cfg.flower ? flowerShapes(cfg, t0, t1) : petalShapes(cfg), ip: t0, op: Math.min(frames, t1 + 1), st: 0, bm: 0 }
}
// "tingle" 로고: 같은 글자를 여러 벌(맨 위가 본체). 벌마다 off 만큼 어긋나 있고, 흔들림을 d 프레임씩 늦게 따라가 잔상이 된다
// ent = 등장 방식(logos.html 시안). 없으면 팝. from: 묶음(벌)의 시작 위치 · 크기 · 회전, glyph: 글자별 등장, blink: 잔상 먼저 깜빡
const LOGO_POP = { name: '팝', desc: '작게서 살짝 넘치며 커짐', dur: 16, from: { s: [70, 70] }, ease: 'back' }
function logoLayers(cfg, frames, firstInd, ent = LOGO_POP) {
  const f = font(FONTS[cfg.font]), LIN = { o: { x: [0], y: [0] }, i: { x: [1], y: [1] } }
  const EASE = { back: { o: { x: [0.34], y: [1.56] }, i: { x: [0.64], y: [1] } }, out: { o: { x: [0.22], y: [1] }, i: { x: [0.36], y: [1] } },
    drop: { o: { x: [0.55], y: [0] }, i: { x: [0.85], y: [0.4] } }, inout: { o: { x: [0.65], y: [0] }, i: { x: [0.35], y: [1] } } }
  const e = EASE[ent.ease ?? 'out'], T = cfg.start, dur = ent.dur ?? 16
  const shake = d => {                                                                // [t, x 이동] — 등장이 끝난 뒤부터
    const k = []
    for (const b of cfg.bursts) { const s0 = T + b.at + d; k.push([s0, 0]); for (let x = 2; x <= b.len; x += 2) k.push([s0 + x, ((x / 2) % 2 ? 1 : -1) * cfg.amp * (1 - x / b.len)]) }
    return k
  }
  const out = []
  let ind = firstInd
  const copies = [{ d: 0, off: [0, 0], color: cfg.color, main: true }, ...[...cfg.trails].reverse()]   // 본체 → 연보라 → 민트(앞이 위)
  copies.forEach((c, ci) => {
    const nul = ind++, t0 = T + c.d + (ent.blink && c.main ? 18 : 0), base = [W / 2 + c.off[0], H / 2 + c.off[1]]
    const fp = typeof ent.from?.p === 'function' ? ent.from.p(c, ci) : ent.from?.p ?? [0, 0]
    const enterP = [[t0, [base[0] + fp[0], base[1] + fp[1], 0], e], [t0 + dur, [base[0], base[1], 0], LIN]]
    const pk = [...enterP, ...shake(c.d).filter(([t]) => t > t0 + dur).map(([t, x]) => [t, [base[0] + x, base[1], 0], LIN])]
    const ks = { o: still(0), a: still([0, 0, 0]), p: kf(pk.map(([t, v, ee], n) => [t, v, n < pk.length - 1 ? ee : undefined])),
      s: ent.from?.s ? kf([[t0, [...ent.from.s, 100], e], [t0 + dur, [100, 100, 100]]]) : still([100, 100, 100]),
      r: ent.from?.r ? kf([[t0, [ent.from.r], e], [t0 + dur, [0]]]) : still(0) }
    out.push({ ddd: 0, ind: nul, ty: 3, nm: `logo +${c.d}f`, sr: 1, ao: 0, ip: T, op: frames, st: 0, ks })
    const glyphs = textLayers(cfg.text, f, cfg.size, H / 2, ind, nul, null, frames)
    glyphs.forEach((g, gi) => {
      const it = g.shapes[0].it, fi = it.findIndex(i => i.ty === 'fl'), col = still([...c.color, 1])
      it[fi].c = col
      if (cfg.round) it.splice(fi, 0, { ty: 'st', c: col, o: still(100), w: still(cfg.round), lc: 2, lj: 2 })   // 둥근 외곽선
      const tg = t0 + (ent.glyph ? gi * 4 : 0)                                                      // 글자별이면 4프레임씩 차례로
      if (ent.blink && !c.main) g.ks.o = kf([[T, [0], 'hold'], [T + 2, [100], 'hold'], [T + 6, [0], 'hold'], [T + 10, [100], 'hold'], [T + 13, [0], 'hold'], [T + 16, [100]]])
      else if (ent.fade) g.ks.o = kf([[tg, [0]], [tg + dur, [100]]])
      else g.ks.o = kf([[tg, [0]], [tg + 3, [100]]])
      if (ent.glyph === 'pop') g.ks.s = kf([[tg, [0, 0, 100]], [tg + 14, [100, 100, 100]]], EASE.back)
      if (ent.glyph === 'wave') { const h = g.ks.p.k; g.ks.p = kf([[tg, [h[0], h[1] + 90, 0]], [tg + 10, [h[0], h[1] - 20, 0]], [tg + 18, [h[0], h[1], 0]]], EASE.inout) }
      g.ip = T; out.push(g); ind++
    })
  })
  return out
}
// 문구: gallery.mjs 등장 모션을 start 만큼 늦춰 적용(키프레임 시각을 통째로 민다)
function phraseLayers(cfg, frames, firstInd) {
  const ent = gallery((list, ease) => kf(list.map(([t, v, e]) => [t + cfg.start, v, e]), ease)).find(e => e.key === cfg.entrance)
  const root = { ddd: 0, ind: firstInd, ty: 3, nm: 'phrase root', sr: 1, ao: 0, ip: 0, op: frames, st: 0, ks: { o: still(0), r: still(0), p: still([W / 2, H / 2, 0]), a: still([0, 0, 0]), s: still([100, 100, 100]) } }
  const glyphs = textLayers(cfg.text, font(FONTS[cfg.font]), cfg.size, H / 2, firstInd + 1, firstInd, ent, frames)
  glyphs.forEach(g => { g.shapes[0].it.find(i => i.ty === 'fl').c = still([...cfg.color, 1]); g.ip = cfg.start })
  return [...glyphs, root]
}
const phraseEnd = cfg => cfg.start + 4 + ([...cfg.text].length - 1) * 7 + 26 + cfg.hold    // 솟아오름: 글자마다 7프레임 간격, 26프레임
// 5칸 뒤집기: 앞면(덮은 분홍 + 다 나온 문구) · 뒷면(크림 + 검정 tingle)을 프리컴프로 두고, 칸마다 마스크로 잘라 가로 크기 100→0(앞) · 0→100(뒤)
function flipParts(cfg, phrase, frames, firstInd, t0) {
  const rect = (ind, color) => ({ ddd: 0, ind, ty: 4, nm: 'bg', sr: 1, ao: 0, ip: 0, op: frames, st: 0, ks: { o: still(100), r: still(0), p: still([W / 2, H / 2, 0]), a: still([0, 0, 0]), s: still([100, 100, 100]) },
    shapes: [{ ty: 'gr', it: [{ ty: 'rc', p: still([0, 0]), s: still([W + 4, H + 4]), r: still(0) }, { ty: 'fl', c: still([...color, 1]), o: still(100), r: 1 }, { ty: 'tr', p: still([0, 0]), a: still([0, 0]), s: still([100, 100]), r: still(0), o: still(100), sk: still(0), sa: still(0) }] }] })
  const root = { ddd: 0, ind: 1, ty: 3, nm: 'root', sr: 1, ao: 0, ip: 0, op: frames, st: 0, ks: { o: still(0), r: still(0), p: still([W / 2, H / 2, 0]), a: still([0, 0, 0]), s: still([100, 100, 100]) } }
  const tinted = (gs, color) => gs.map(g => { g.shapes[0].it.find(i => i.ty === 'fl').c = still([...color, 1]); return g })
  const front = [...tinted(textLayers(phrase.text, font(FONTS[phrase.font]), phrase.size, H / 2, 2, 1, null, frames), phrase.color), root, rect(99, SCENE.cover.color)]
  const back = [...tinted(textLayers('tingle', font(FONTS.nunito), cfg.logoSize, H / 2, 2, 1, null, frames), [0.2, 0.19, 0.23]), root, rect(99, cfg.back)]
  const assets = [{ id: 'flip_front', layers: front }, { id: 'flip_back', layers: back }]
  const SW = W / cfg.strips, IN = { o: { x: [0.7], y: [0] }, i: { x: [0.95], y: [0.8] } }, OUT = { o: { x: [0.05], y: [0.2] }, i: { x: [0.3], y: [1] } }   // 접힐 땐 천천히 시작해 빨라지고, 펴질 땐 빠르게 나와 부드럽게 멈춤
  const layers = []
  let ind = firstInd
  for (let k = 0; k < cfg.strips; k++) {                                              // k = 0 이 맨 오른쪽 칸
    const half = cfg.halves[k], ts = t0 + cfg.gaps.slice(0, k).reduce((a, b) => a + b, 0), tm = ts + half
    const l = W - (k + 1) * SW - 2, r = W - k * SW + 2, cx = W - (k + 0.5) * SW   // 이웃 칸과 2px 씩 겹침(경계 선 방지)
    const mask = { inv: false, mode: 'a', nm: 'strip', pt: still({ c: true, v: [[l, -2], [r, -2], [r, H + 2], [l, H + 2]], i: [[0, 0], [0, 0], [0, 0], [0, 0]], o: [[0, 0], [0, 0], [0, 0], [0, 0]] }), o: still(100), x: still(0) }
    const part = (id, ip, op, sk) => ({ ddd: 0, ind: ind++, ty: 0, nm: `${id} ${k + 1}`, refId: id, sr: 1, ao: 0, w: W, h: H, ip, op, st: 0, hasMask: true, masksProperties: [mask],
      ks: { o: still(100), r: still(0), p: still([cx, H / 2, 0]), a: still([cx, H / 2, 0]), s: sk } })
    layers.push(part('flip_front', t0, tm, { a: 1, k: [{ t: ts, s: [100, 100, 100], ...IN }, { t: tm, s: [0, 100, 100] }] }))
    layers.push(part('flip_back', tm, frames, { a: 1, k: [{ t: tm, s: [0, 100, 100], ...OUT }, { t: tm + half, s: [100, 100, 100] }] }))
  }
  const done = t0 + flipSpan(cfg)                          // 다 뒤집힌 뒤엔 경계 없는 뒷면 한 장으로 바꿈
  if (cfg.tagline) {   // 로고 밑에서 빠져나오는 한 줄: 안쪽(움직이는 글자) 프리컴프 + 바깥 레이어 마스크(로고 아래만 보임)
    const tg = cfg.tagline, nb = font(FONTS.nunito).getPath('tingle', 0, 0, cfg.logoSize).getBoundingBox()
    const logoBottom = H / 2 + (nb.y2 - nb.y1) / 2, tagY = logoBottom + tg.gap, ts = done + tg.after
    const tf = font(FONTS.pretendard), tb = tf.getPath(tg.text, 0, 0, tg.size).getBoundingBox(), th = tb.y2 - tb.y1
    const mover = { ddd: 0, ind: 1, ty: 3, nm: 'tag move', sr: 1, ao: 0, ip: 0, op: frames, st: 0, ks: { o: still(0), r: still(0), a: still([0, 0, 0]), s: still([100, 100, 100]),
      p: { a: 1, k: [{ t: ts, s: [W / 2, H / 2 - (tagY - logoBottom) - th / 2 - 6, 0], o: { x: [0.22], y: [1] }, i: { x: [0.36], y: [1] } }, { t: ts + tg.dur, s: [W / 2, H / 2, 0] }] } } }
    const tglyphs = tinted(textLayers(tg.text, tf, tg.size, tagY, 2, 1, null, frames), tg.color)
    tglyphs.forEach(g => { g.ks.o = kf([[ts, [0]], [ts + 16, [100]]]) })
    assets.push({ id: 'tagline', layers: [...tglyphs, mover] })
    const clip = { inv: false, mode: 'a', nm: 'below logo', pt: still({ c: true, v: [[0, logoBottom + 6], [W, logoBottom + 6], [W, H], [0, H]], i: [[0, 0], [0, 0], [0, 0], [0, 0]], o: [[0, 0], [0, 0], [0, 0], [0, 0]] }), o: still(100), x: still(0) }
    layers.unshift({ ddd: 0, ind: ind++, ty: 0, nm: 'tagline', refId: 'tagline', sr: 1, ao: 0, w: W, h: H, ip: ts, op: frames, st: 0, hasMask: true, masksProperties: [clip],
      ks: { o: still(100), r: still(0), p: still([W / 2, H / 2, 0]), a: still([W / 2, H / 2, 0]), s: still([100, 100, 100]) } })
  }
  layers.unshift({ ddd: 0, ind: ind++, ty: 0, nm: 'flip_back full', refId: 'flip_back', sr: 1, ao: 0, w: W, h: H, ip: done, op: frames, st: 0, ks: { o: still(100), r: still(0), p: still([W / 2, H / 2, 0]), a: still([W / 2, H / 2, 0]), s: still([100, 100, 100]) } })
  const ti = layers.findIndex(l => l.nm === 'tagline'); if (ti > 0) layers.unshift(layers.splice(ti, 1)[0])   // 한 줄 문구는 뒷면 한 장보다 위
  if (cfg.tagline && cfg.shine) {   // 뒷면 tingle 위에 같은 자리 · 같은 크기로 로고(본체 + 잔상)를 얹고 떨게 함 — 본체는 뒷면 글자와 겹쳐 티 나지 않음
    const T = done + cfg.tagline.after + cfg.tagline.dur + cfg.shine.delay
    layers.unshift(...logoLayers({ ...SCENE.logo, font: 'nunito', size: cfg.logoSize, round: 0, start: T, bursts: cfg.shine.bursts }, frames, 700, { name: '그대로', dur: 1, from: {} }))
    for (const g of assets.find(a => a.id === 'flip_back').layers) if (g.nm?.startsWith('glyph')) g.ks.o = kf([[T + 2, [100], 'hold'], [T + 3, [0]]])   // 로고 본체가 덮은 뒤 뒷면 글자는 숨김(떨 때 두 겹 방지)
  }
  layers.push({ ...rect(ind, cfg.back), nm: 'flip gap', ip: t0 })                      // 칸이 좁아진 틈 = 뒷면 바탕색(흰 선이 비치지 않게)                    // 칸이 좁아진 틈 뒤(아래 장면 가림)
  return { assets, layers }
}
const flipSpan = cfg => Math.max(...cfg.halves.map((h, k) => cfg.gaps.slice(0, k).reduce((a, b) => a + b, 0) + h * 2))   // 뒤집기 시작 ~ 마지막 칸 완료
const flipStart = () => SCENE.phrase ? phraseEnd(SCENE.phrase) - SCENE.phrase.hold + SCENE.flip.after : SCENE.cover.start + SCENE.cover.dur + SCENE.flip.after
// 화면 덮기: 이미 화면을 덮을 만큼 큰 꽃잎(BLOB) 하나가 아래 밖에서 올라와 가운데에 멈춤(커지지 않음)
function coverPetal(cfg, frames, ind) {
  const t0 = cfg.start, t1 = t0 + cfg.dur
  const RISE = { o: { x: [0.3], y: [0.3] }, i: { x: [0.45], y: [1] } }                          // 거의 등속으로 올라오다 끝에서 부드럽게 멈춤
  const ks = {
    o: still(100), a: still([0, 0, 0]), s: still([cfg.size, cfg.size, 100]),                       // BLOB 은 높이 100px → size% 가 곧 px
    p: { a: 1, k: [{ t: t0, s: [W * cfg.x, H + cfg.size / 2 + 20, 0], ...RISE }, { t: t1, s: [W * cfg.x, H / 2, 0] }] },
    r: { a: 1, k: [{ t: t0, s: [cfg.tilt], ...RISE }, { t: t1, s: [0] }] },
  }
  return { ddd: 0, ind, ty: 4, nm: 'cover petal', sr: 1, ks, ao: 0, shapes: petalShapes({ color: cfg.color, alpha: 100, shape: PETAL_PICK[0] }), ip: t0, op: frames, st: 0, bm: 0 }
}
// 마지막 한 장: 아래 가운데에서 올라오며 점점 빨리 커져(지수) 화면 전체를 덮는다
function heroLayer(cfg, frames, ind) {
  const t0 = cfg.start, t1 = t0 + cfg.dur, STEPS = 8
  const p = [], sc = []
  for (let k = 0; k <= STEPS; k++) {
    const q = k / STEPS, t = t0 + Math.round(cfg.dur * q), e = q * q * (3 - 2 * q)
    p.push([t, [W / 2 + 120 * (1 - e), (H + 140) + (H / 2 + 260 - (H + 140)) * e]])   // 아래 밖 → 가운데보다 조금 아래(잎 배가 화면 가운데를 덮게)
    sc.push([t, 140 * Math.pow(cfg.scale * 100 / 140, Math.pow(q, 1.6)) ])           // 140% → scale×100%, 처음엔 천천히 마지막에 확
  }
  const ks = { o: still(100), r: anim3([[t0, -24], [t1, 6]], v => [v]), p: anim3(p, ([x, y]) => [x, y, 0]), a: still([0, 0, 0]), s: anim3(sc, v => [v, v, 100]) }
  return shapeLayer(ind, 'hero petal', PETAL_SHAPES[1], cfg.color, ks, t0, frames)
}

const pickedEnter = () => (SCENE.enterPick ? enterGallery().find(v => v.key === SCENE.enterPick).make : null)
function buildScene() {
  const f = font(FONTS[SCENE.font]), size = SCENE.size, text = SCENE.first + SCENE.rest
  const scale = size / f.unitsPerEm, glyphs = f.stringToGlyphs(text)
  const xs = []; let x = 0
  glyphs.forEach((g, k) => { xs.push(x); x += g.advanceWidth * scale + (k < glyphs.length - 1 ? f.getKerningValue(g, glyphs[k + 1]) * scale : 0) })
  const box = f.getPath(text, 0, 0, size).getBoundingBox()
  const left = -x / 2, base = -(box.y1 + box.y2) / 2                     // 화면 가운데 기준
  const items = glyphs.map((g, k) => {
    const path = g.getPath(0, 0, size), gb = path.getBoundingBox()
    const a = [(gb.x1 + gb.x2) / 2, (gb.y1 + gb.y2) / 2]
    return { ch: text[k], path, a, home: [left + xs[k] + a[0], base + a[1]] }
  })
  const nFirst = [...SCENE.first].length
  const wFirst = xs[nFirst]                                              // "진동" 폭
  const shift = -wFirst / 2 - left                                       // 혼자일 때 가운데에 오게 하는 이동량
  const restL = left + xs[nFirst], restC = (restL + left + x) / 2        // "이 오면" 가운데(회전 중심)
  const last = BUZZ.bursts[BUZZ.bursts.length - 1]
  const logoEnd = SCENE.showLogo && SCENE.logo ? SCENE.logo.start + Math.max(...SCENE.logo.bursts.map(b => b.at + b.len)) + Math.max(0, ...SCENE.logo.trails.map(t => t.d)) + 24 : 0
  const flipEnd = SCENE.flip ? flipStart() + flipSpan(SCENE.flip) + Math.max(SCENE.flip.hold, SCENE.flip.tagline ? SCENE.flip.tagline.after + SCENE.flip.tagline.dur + (SCENE.flip.shine ? SCENE.flip.shine.delay + Math.max(...SCENE.flip.shine.bursts.map(b => b.at + b.len)) + 6 + SCENE.flip.shine.hold : SCENE.flip.tagline.hold) : 0) : 0
  const frames = SCENE.cover ? Math.max(SCENE.cover.start + SCENE.cover.dur + 20, logoEnd, SCENE.phrase ? phraseEnd(SCENE.phrase) : 0, flipEnd) : Math.max(SCENE.buzzAfter + last.start + last.frames, SCENE.enter.start + SCENE.enter.dur + 36, ...SCENE.ones.map(o => o.start + (o.speed ? Math.round((H + o.size + 40) / o.speed) : o.dur)), SCENE.petals ? SCENE.hero.start + SCENE.hero.dur + 16 : 0)
  const BACK = { o: { x: [0.34], y: [1.4] }, i: { x: [0.64], y: [1] } }
  const POP = { o: { x: [0.34], y: [1.56] }, i: { x: [0.64], y: [1] } }
  const IN_OUT = { o: { x: [0.65], y: [0] }, i: { x: [0.35], y: [1] } }
  const ROOT = 1, SLIDE = 2, SHAKE = 3, TILT = 4
  const nul = (ind, nm, ks, parent) => ({ ddd: 0, ind, ty: 3, nm, sr: 1, ...(parent ? { parent } : {}), ks: { o: still(0), r: still(0), a: still([0, 0, 0]), s: still([100, 100, 100]), p: still([0, 0, 0]), ...ks }, ao: 0, ip: 0, op: frames, st: 0 })
  const glyph = (it, ind, parent, p, o) => ({
    ddd: 0, ind, ty: 4, parent, nm: `glyph ${it.ch}`, sr: 1,
    ks: { o, r: still(0), p: still([...p, 0]), a: still([...it.a, 0]), s: still([100, 100, 100]) }, ao: 0,
    shapes: [{ ty: 'gr', nm: it.ch, it: [
      ...contours(it.path.commands).map(c => ({ ty: 'sh', ks: still({ i: c.i, o: c.o, v: c.v, c: true }) })),
      { ty: 'fl', c: still([...SCENE.ink, 1]), o: still(100), r: 1 },
      { ty: 'tr', p: still([0, 0]), a: still([0, 0]), s: still([100, 100]), r: still(0), o: still(100), sk: still(0), sa: still(0) },
    ] }],
    ip: 0, op: frames, st: 0, bm: 0,
  })
  const { pop, slide, enter } = SCENE
  // Lottie 는 목록 앞쪽이 위에 그려진다: 덮는 한 장(맨 위) > 큰 꽃잎 > 글자 > 작은 꽃잎 > 묶음
  const rise = SCENE.petals ? riseLayers(SCENE.rise, frames, 200) : { front: [], back: [] }
  const flip = SCENE.flip ? flipParts(SCENE.flip, SCENE.phrase, frames, 600, flipStart()) : { assets: [], layers: [] }
  const layers = [...flip.layers, ...(SCENE.phrase ? phraseLayers(SCENE.phrase, frames, 500) : []), ...(SCENE.showLogo && SCENE.logo ? logoLayers(SCENE.logo, frames, 400) : []), ...(SCENE.cover ? [coverPetal(SCENE.cover, frames, 320)] : []), ...SCENE.ones.flatMap((o, k) => (o.front ? [onePetal({ ...o, shape: PETAL_PICK[k % PETAL_PICK.length] }, frames, 310 + k)] : [])), ...(SCENE.petals ? [heroLayer(SCENE.hero, frames, 300), ...rise.front] : [])]
  let ind = 10
  items.forEach((it, k) => {
    if (it.ch === ' ') return
    if (k < nFirst) layers.push(glyph(it, ind++, SHAKE, it.home, kf([[pop.start, [0]], [pop.start + 4, [100]]])))
    else layers.push(glyph(it, ind++, TILT, [it.home[0] - restC, it.home[1]], kf([[enter.start, [0]], [enter.start + 6, [100]]])))
  })
  layers.push({ ddd: 0, ind: 9, ty: 4, nm: 'bg', sr: 1, ao: 0, ip: 0, op: frames, st: 0, ks: { o: still(100), r: still(0), p: still([W / 2, H / 2, 0]), a: still([0, 0, 0]), s: still([100, 100, 100]) },
    shapes: [{ ty: 'gr', it: [{ ty: 'rc', p: still([0, 0]), s: still([W + 4, H + 4]), r: still(0) }, { ty: 'fl', c: still([...SCENE.bg, 1]), o: still(100), r: 1 }, { ty: 'tr', p: still([0, 0]), a: still([0, 0]), s: still([100, 100]), r: still(0), o: still(100), sk: still(0), sa: still(0) }] }] })   // 맨 아래 바탕
  layers.push(...rise.back, ...SCENE.ones.flatMap((o, k) => (o.front ? [] : [onePetal(o, frames, 310 + k)])))   // 글자 뒤 꽃
  layers.push(
    nul(SHAKE, 'buzz(진동만)', { p: buzzFrames(SCENE.buzzAfter).p }, SLIDE),
    nul(SLIDE, 'slide', { p: kf([[slide.start, [shift, 0, 0]], [slide.start + slide.dur, [0, 0, 0]]], slide.ease ?? IN_OUT) }, ROOT),
    nul(TILT, 'enter 이 오면', (enter.make ?? pickedEnter())
      ? (m => ({ p: kf(m.p.map(([t, [x, y], e]) => [t, [x, y, 0], e])), r: kf(m.r.map(([t, a, e]) => [t, [a], e])) }))((enter.make ?? pickedEnter())({ t: enter.start, dur: enter.dur, from: enter.from, tilt: enter.tilt, cx: restC }))   // enters.html 시안
      : {
        p: kf([[enter.start, [restC + enter.from, 0, 0]], [enter.start + enter.dur, [restC, 0, 0]]]),
        r: kf([[enter.start, [enter.tilt]], [enter.start + enter.dur, [0]]], BACK),
      }, ROOT),
    nul(ROOT, 'root · 팝', { p: still([W / 2, H / 2, 0]), s: kf([[pop.start, [0, 0, 100]], [pop.start + pop.dur, [100, 100, 100]]], POP) }),
  )
  return { v: '5.7.4', fr: FPS, ip: 0, op: frames, w: W, h: H, nm: 'Tingle intro', ddd: 0, assets: flip.assets, layers, markers: [] }
}

const intro = dump(buildScene())
fs.mkdirSync(path.dirname(OUT_FILE), { recursive: true })
fs.writeFileSync(OUT_FILE, intro)
console.log(`${path.relative(process.cwd(), OUT_FILE)} ${(intro.length / 1024).toFixed(1)}KB · ${JSON.parse(intro).op} 프레임(${(JSON.parse(intro).op / 60).toFixed(2)}초)`)
