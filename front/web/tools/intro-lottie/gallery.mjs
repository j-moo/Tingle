// 등장 모션 후보 30개(gallery.html). 각 후보는 make(k, n, [x, y]) → 글자 k 의 투명도 o · 위치 p · 크기 s · 회전 r 키프레임.
// k = 글자 순서(0부터), n = 글자 수, [x, y] = 제자리. 모두 54프레임(0.9초) 안에 끝나고, 뒤에 진동이 붙는다
export function gallery(kf) {
  const IN_OUT = { o: { x: [0.65], y: [0] }, i: { x: [0.35], y: [1] } }
  const IN = { o: { x: [0.55], y: [0] }, i: { x: [0.85], y: [0.4] } }
  const BACK = { o: { x: [0.34], y: [1.56] }, i: { x: [0.64], y: [1] } }
  const S = (sx, sy = sx) => [sx, sy, 100]
  const fade = (t, d = 12) => kf([[t, [0]], [t + d, [100]]])
  const at = (x, y) => [x, y, 0]
  let seed = 11
  const rand = () => ((seed = (seed * 16807) % 2147483647) / 2147483647) * 2 - 1

  return [
    ['페이드 업', '아래에서 떠오르며 나타남', (k, n, [x, y]) => { const t = 4 + k * 8; return { o: fade(t, 30), p: kf([[t, at(x, y + 60)], [t + 30, at(x, y)]]) } }],
    ['페이드 다운', '위에서 내려오며 나타남', (k, n, [x, y]) => { const t = 4 + k * 8; return { o: fade(t, 30), p: kf([[t, at(x, y - 60)], [t + 30, at(x, y)]]) } }],
    ['왼쪽에서', '왼쪽에서 밀려 들어옴', (k, n, [x, y]) => { const t = 4 + k * 8; return { o: fade(t, 24), p: kf([[t, at(x - 120, y)], [t + 30, at(x, y)]]) } }],
    ['오른쪽에서', '오른쪽에서 밀려 들어옴', (k, n, [x, y]) => { const t = 4 + k * 8; return { o: fade(t, 24), p: kf([[t, at(x + 120, y)], [t + 30, at(x, y)]]) } }],
    ['서서히', '제자리에서 천천히 나타남', (k) => ({ o: fade(4 + k * 10, 36) })],
    ['팝', '점에서 커지며 살짝 넘침', (k) => { const t = 4 + k * 8; return { o: fade(t, 8), s: kf([[t, S(0)], [t + 28, S(100)]], BACK) } }],
    ['줌 아웃', '크게 시작해 제자리로 줄어듦', (k) => { const t = 4 + k * 8; return { o: fade(t, 16), s: kf([[t, S(300)], [t + 30, S(100)]]) } }],
    ['찌그러짐', '납작했다가 길쭉했다가 제자리(말랑)', (k) => { const t = 4 + k * 8; return { o: fade(t, 6), s: kf([[t, S(140, 30)], [t + 12, S(85, 125)], [t + 22, S(108, 94)], [t + 30, S(100)]]) } }],
    ['스침', '양옆에서 다가와 맞닿음', (k, n, [x, y]) => { const dx = (k - (n - 1) / 2) * 360; return { o: fade(4, 16), p: kf([[4, at(x + dx, y)], [40, at(x, y)]]) } }],
    ['자리 바꾸기', '서로 자리에서 출발해 엇갈려 제자리', (k, n, [x, y]) => { const dx = ((n - 1) / 2 - k) * 2 * 120; return { o: fade(4, 12), p: kf([[4, at(x + dx, y + (k % 2 ? 40 : -40))], [40, at(x, y)]], IN_OUT) } }],
    ['신호 깜빡', '두세 번 깜빡이다 켜짐', (k) => { const t = 6 + k * 4; return { o: kf([[0, [0], 'hold'], [t, [100], 'hold'], [t + 5, [0], 'hold'], [t + 13, [100], 'hold'], [t + 17, [0], 'hold'], [t + 24, [100]]]) } }],
    ['네온 깜빡', '불규칙하게 지직거리다 켜짐', (k) => { const t = 4 + k * 6; return { o: kf([[0, [0], 'hold'], [t, [60], 'hold'], [t + 2, [0], 'hold'], [t + 6, [100], 'hold'], [t + 8, [20], 'hold'], [t + 9, [100], 'hold'], [t + 15, [30], 'hold'], [t + 17, [100]]]) } }],
    ['낙하 착지', '위에서 떨어져 튕기며 착지', (k, n, [x, y]) => { const t = 4 + k * 7; return { o: fade(t, 8), p: kf([[t, at(x, y - 320), IN], [t + 22, at(x, y + 14)], [t + 32, at(x, y)]]) } }],
    ['솟아오름', '아래에서 튀어 올라 살짝 넘침', (k, n, [x, y]) => { const t = 4 + k * 7; return { o: fade(t, 8), p: kf([[t, at(x, y + 200)], [t + 26, at(x, y)]], BACK) } }],
    ['회전 등장', '반 바퀴 돌며 커짐', (k) => { const t = 4 + k * 8; return { o: fade(t, 10), r: kf([[t, [-180]], [t + 32, [0]]]), s: kf([[t, S(0)], [t + 32, S(100)]]) } }],
    ['기울여 들어옴', '오른쪽에서 기운 채 들어와 바로 섬', (k, n, [x, y]) => { const t = 4 + k * 8; return { o: fade(t, 14), r: kf([[t, [25]], [t + 30, [0]]]), p: kf([[t, at(x + 140, y)], [t + 30, at(x, y)]]) } }],
    ['가로 뒤집기', '카드처럼 가로로 펼쳐짐', (k) => { const t = 4 + k * 8; return { s: kf([[t, S(0, 100)], [t + 26, S(100)]], BACK), o: fade(t, 6) } }],
    ['세로 펼침', '위아래로 펼쳐짐', (k) => { const t = 4 + k * 8; return { s: kf([[t, S(100, 0)], [t + 26, S(100)]], BACK), o: fade(t, 6) } }],
    ['고무줄', '여러 번 출렁이며 자리 잡음', (k) => { const t = 4 + k * 6; return { o: fade(t, 6), s: kf([[t, S(0)], [t + 12, S(120)], [t + 22, S(92)], [t + 30, S(104)], [t + 38, S(100)]], IN_OUT) } }],
    ['쾅', '아주 크게에서 확 내려꽂힘', (k) => { const t = 4 + k * 10; return { o: fade(t, 6), s: kf([[t, S(400), IN], [t + 14, S(94)], [t + 22, S(100)]]) } }],
    ['타자', '한 글자씩 툭 찍힘', (k) => ({ o: kf([[0, [0], 'hold'], [8 + k * 14, [100]]]) })],
    ['가로로 늘였다가', '옆으로 길게 늘어났다 줄어듦', (k) => { const t = 4 + k * 8; return { o: fade(t, 12), s: kf([[t, S(300, 100)], [t + 30, S(100)]]) } }],
    ['물결', '차례로 솟았다 내려앉음', (k, n, [x, y]) => { const t = 4 + k * 6; return { o: fade(t, 10), p: kf([[t, at(x, y + 80)], [t + 16, at(x, y - 24)], [t + 28, at(x, y)]], IN_OUT) } }],
    ['떨며 등장', '덜덜 떨면서 선명해짐(진동 예고)', (k, n, [x, y]) => {
      const t = 4 + k * 4, keys = [[t, at(x, y), 'lin']]
      for (let f = 2, s = 1; f <= 36; f += 2, s = -s) keys.push([t + f, at(x + s * 6 * (1 - f / 36), y), 'lin'])
      return { o: fade(t, 36), p: kf(keys.map(([a, b]) => [a, b, { o: { x: [0], y: [0] }, i: { x: [1], y: [1] } }])) }
    }],
    ['두근', '나타난 뒤 심장처럼 두 번 뜀', (k) => { const t = 4 + k * 4; return { o: fade(t, 10), s: kf([[t + 10, S(100)], [t + 18, S(116)], [t + 26, S(100)], [t + 32, S(110)], [t + 42, S(100)]], IN_OUT) } }],
    ['굴러옴', '왼쪽에서 한 바퀴 굴러 들어옴', (k, n, [x, y]) => { const t = 4 + k * 8; return { o: fade(t, 10), r: kf([[t, [-360]], [t + 34, [0]]]), p: kf([[t, at(x - 420, y)], [t + 34, at(x, y)]]) } }],
    ['흩어졌다 모임', '여기저기서 날아와 모임', (k, n, [x, y]) => { const t = 4, dx = rand() * 420, dy = rand() * 260, dr = rand() * 70; return { o: fade(t, 14), p: kf([[t, at(x + dx, y + dy)], [t + 36, at(x, y)]], IN_OUT), r: kf([[t, [dr]], [t + 36, [0]]], IN_OUT) } }],
    ['흔들며 올라옴', '좌우로 기우뚱하며 떠오름', (k, n, [x, y]) => { const t = 4 + k * 8; return { o: fade(t, 14), p: kf([[t, at(x, y + 70)], [t + 30, at(x, y)]]), r: kf([[t, [-12]], [t + 12, [8]], [t + 22, [-4]], [t + 30, [0]]], IN_OUT) } }],
    ['펼쳐짐', '가운데 겹쳐 있다가 양옆으로 벌어짐', (k, n, [x, y]) => ({ o: fade(4, 12), p: kf([[8, at(0, y)], [38, at(x, y)]], BACK) })],
    ['진자', '시계추처럼 흔들리다 멈춤', (k) => { const t = 4 + k * 6; return { o: fade(t, 10), r: kf([[t, [40]], [t + 14, [-18]], [t + 26, [8]], [t + 36, [0]]], IN_OUT) } }],
  ].map(([name, desc, make], idx) => ({ key: `g${idx + 1}`, name: `${idx + 1} · ${name}`, desc, make }))
}
