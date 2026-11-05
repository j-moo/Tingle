// "이 오면" 들어오는 모션(+ "진동" 밀림) 가속 시안 20개(enters.html). make({ t, dur, from, tilt, cx }) → { p: [[t, [x, y], ease]], r: [[t, 도, ease]] }
// t = 들어오기 시작 프레임, cx = 제자리 x(회전 중심), from = 오른쪽에서 출발하는 거리. slide: "진동" 밀림 { at(들어오기 기준 시작 차이), dur, ease }
const E = {
  out: { o: { x: [0.22], y: [1] }, i: { x: [0.36], y: [1] } },          // 지금 쓰는 감속
  expo: { o: { x: [0.16], y: [1] }, i: { x: [0.3], y: [1] } },          // 확 들어와 길게 감속
  quart: { o: { x: [0.25], y: [1] }, i: { x: [0.5], y: [1] } },
  inout: { o: { x: [0.65], y: [0] }, i: { x: [0.35], y: [1] } },
  inoutHard: { o: { x: [0.8], y: [0] }, i: { x: [0.2], y: [1] } },      // 천천히 → 확 → 천천히
  in: { o: { x: [0.55], y: [0] }, i: { x: [0.85], y: [0.4] } },          // 가속(부딪히듯)
  back: { o: { x: [0.34], y: [1.4] }, i: { x: [0.64], y: [1] } },        // 살짝 지나쳤다 돌아옴
  backBig: { o: { x: [0.34], y: [1.9] }, i: { x: [0.64], y: [1] } },
  lin: { o: { x: [0], y: [0] }, i: { x: [1], y: [1] } },
}
const go = (t, dur, x0, x1, ease, y0 = 0) => [[t, [x0, y0], ease], [t + dur, [x1, 0]]]
const tilt = (t, dur, a, ease = E.back) => [[t, a, ease], [t + dur, 0]]

export function enterGallery() {
  return [
    ['지금 그대로', '감속으로 들어오고 기울기만 살짝 튕김', {}, ({ t, dur, from, tilt: a, cx }) => ({ p: go(t, dur, cx + from, cx, E.out), r: tilt(t, dur, a) })],
    ['강한 감속', '확 들어와 길게 미끄러지며 멈춤', {}, ({ t, dur, from, tilt: a, cx }) => ({ p: go(t, dur + 6, cx + from, cx, E.expo), r: tilt(t, dur + 6, a, E.expo) })],
    ['가속 → 감속', '천천히 출발해 빨라졌다 부드럽게 섬', {}, ({ t, dur, from, tilt: a, cx }) => ({ p: go(t, dur + 4, cx + from, cx, E.inout), r: tilt(t, dur + 4, a, E.inout) })],
    ['강한 가속 → 감속', '거의 멈춰 있다 휙 들어와 착', {}, ({ t, dur, from, tilt: a, cx }) => ({ p: go(t, dur + 6, cx + from, cx, E.inoutHard), r: tilt(t, dur + 6, a, E.inoutHard) })],
    ['살짝 지나쳤다 복귀', '빠르게 들어와 조금 넘어갔다 돌아옴', {}, ({ t, dur, from, tilt: a, cx }) => ({ p: go(t, dur, cx + from, cx, E.back), r: tilt(t, dur, a) })],
    ['스프링', '두 번 출렁이며 자리 잡음', {}, ({ t, from, tilt: a, cx }) => ({ p: [[t, [cx + from, 0], E.expo], [t + 14, [cx - 26, 0], E.inout], [t + 24, [cx + 10, 0], E.inout], [t + 32, [cx, 0]]], r: [[t, a, E.expo], [t + 14, -6, E.inout], [t + 24, 2, E.inout], [t + 32, 0]] })],
    ['기울기 크게 튕김', '많이 기운 채 들어와 바로 섬', {}, ({ t, dur, from, cx }) => ({ p: go(t, dur, cx + from, cx, E.expo), r: tilt(t, dur + 4, 30, E.backBig) })],
    ['반대로 넘어갔다 섬', '기울기가 반대쪽으로 한 번 넘어감', {}, ({ t, dur, from, tilt: a, cx }) => ({ p: go(t, dur, cx + from, cx, E.expo), r: [[t, a, E.expo], [t + dur * 0.6, -8, E.inout], [t + dur + 8, 0]] })],
    ['느리고 길게', '천천히 미끄러져 들어옴', {}, ({ t, from, tilt: a, cx }) => ({ p: go(t, 36, cx + from, cx, E.expo), r: tilt(t, 36, a, E.expo) })],
    ['짧고 빠르게', '툭 들어와 바로 멈춤', {}, ({ t, from, tilt: a, cx }) => ({ p: go(t, 14, cx + from, cx, E.expo), r: tilt(t, 16, a) })],
    ['밀림과 동시에', '"진동"이 밀리는 순간 같이 출발', { at: 0 }, ({ t, dur, from, tilt: a, cx }) => ({ p: go(t, dur, cx + from, cx, E.expo), r: tilt(t, dur, a) })],
    ['밀린 뒤 늦게', '"진동"이 다 밀린 뒤 들어옴', { at: 14 }, ({ t, dur, from, tilt: a, cx }) => ({ p: go(t, dur, cx + from, cx, E.expo), r: tilt(t, dur, a) })],
    ['밀림도 가속', '"진동"도 천천히 → 확 → 천천히 밀림', { ease: E.inoutHard, dur: 20 }, ({ t, dur, from, tilt: a, cx }) => ({ p: go(t, dur + 4, cx + from, cx, E.inoutHard), r: tilt(t, dur + 4, a, E.inoutHard) })],
    ['밀림은 감속만', '"진동"이 확 밀리고 서서히 멈춤', { ease: E.expo, dur: 22 }, ({ t, dur, from, tilt: a, cx }) => ({ p: go(t, dur, cx + from, cx, E.expo), r: tilt(t, dur, a, E.expo) })],
    ['부딪히듯 착', '가속해 들어와 툭 부딪혀 섬', {}, ({ t, from, tilt: a, cx }) => ({ p: go(t, 14, cx + from, cx, E.in), r: [[t, a, E.in], [t + 14, a * 0.6, E.back], [t + 26, 0]] })],
    ['멀리서', '화면 끝에서 길게 날아옴', {}, ({ t, tilt: a, cx }) => ({ p: go(t, 30, cx + 760, cx, E.expo), r: tilt(t, 30, a, E.expo) })],
    ['가까이서', '바로 옆에서 짧게 들어옴', {}, ({ t, tilt: a, cx }) => ({ p: go(t, 20, cx + 200, cx, E.expo), r: tilt(t, 20, a) })],
    ['회전 없이 미끄러짐', '기울기 없이 강한 감속만', {}, ({ t, dur, from, cx }) => ({ p: go(t, dur + 6, cx + from, cx, E.expo), r: [[t, 0, E.lin], [t + 1, 0]] })],
    ['위에서 비스듬히', '오른쪽 위에서 기울며 내려앉음', {}, ({ t, dur, from, tilt: a, cx }) => ({ p: go(t, dur + 6, cx + from * 0.8, cx, E.expo, -160), r: tilt(t, dur + 6, a + 6, E.back) })],
    ['착지 후 부르르', '들어와 멈추며 짧게 떨림', {}, ({ t, dur, from, tilt: a, cx }) => {
      const p = [[t, [cx + from, 0], E.expo], [t + dur, [cx, 0], E.lin]]
      for (let f = 2, s = 1; f <= 12; f += 2, s = -s) p.push([t + dur + f, [cx + s * 5 * (1 - f / 12), 0], E.lin])
      p[p.length - 1] = [p[p.length - 1][0], [cx, 0]]
      return { p, r: tilt(t, dur, a) }
    }],
  ].map(([name, desc, slide, make], idx) => ({ key: `e${idx + 1}`, name: `${idx + 1} · ${name}`, desc, slide, make }))
}
