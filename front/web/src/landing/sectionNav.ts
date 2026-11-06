// 누르기 모드(세로 화면 · 폰 가로 화면)에서 섹션 사이를 오갈 때 쓰는 공통 함수
// 오른쪽 절반을 누르면 다음 섹션, 왼쪽 절반은 이전 섹션 화면에 맞춰 부드럽게 스크롤
// data-snap="content" 인 섹션(Let's Do it!! — 위 여백 뒤에서 멈춤)은 그 안쪽 첫 요소 위치로
export const TAP_MODE = '(orientation: portrait), (orientation: landscape) and (max-height: 500px)'   // 누르기로 넘기는 화면(CSS 의 같은 미디어 조건과 짝)
export const PHONE_LANDSCAPE = '(orientation: landscape) and (max-height: 500px)'

const snapTop = (el: Element) => {
  const target = el instanceof HTMLElement && el.dataset.snap === 'content' ? el.firstElementChild ?? el : el
  return target.getBoundingClientRect().top + scrollY
}

// 한 번에 슉: 빠르게 출발해 부드럽게 멈추는 짧은 스크롤(브라우저 smooth 보다 빠르고 일정). 동작 줄이기면 바로 이동
export const SWIFT_MS = 520
let swift = 0
export function swiftTo(top: number) {
  cancelAnimationFrame(swift)
  if (matchMedia('(prefers-reduced-motion: reduce)').matches) { scrollTo({ top, behavior: 'instant' }); return }
  const from = scrollY, t0 = performance.now(), ease = (t: number) => 1 - (1 - t) ** 4
  const tick = (now: number) => {
    const t = Math.min(1, (now - t0) / SWIFT_MS)
    scrollTo({ top: from + (top - from) * ease(t), behavior: 'instant' })
    if (t < 1) swift = requestAnimationFrame(tick)
  }
  swift = requestAnimationFrame(tick)
}

export function goSection(from: Element | null, dir: 1 | -1) {
  const to = dir > 0 ? from?.nextElementSibling : from?.previousElementSibling
  if (to) swiftTo(snapTop(to))
}

// 누른 위치(화면 왼쪽 · 오른쪽 절반)로 방향
export const sideOf = (clientX: number): 1 | -1 => (clientX < innerWidth / 2 ? -1 : 1)

// 메뉴 링크로 이동하는 중(약 1.5초)인지 — Let's Do it!! 이 지나가는 중간에 멈춰 세우지 않게
let jumpAt = -1e9
export const markJump = () => { jumpAt = performance.now() }
export const jumping = () => performance.now() - jumpAt < 1500

// 누르기 모드: 손가락으로 끌어서 하는 페이지 스크롤을 막음(누르기로만 섹션 이동). 휠 · 스크롤바 · 키보드 스크롤은 그대로
// 폰 가로 화면은 인트로 다음 첫 화면(Let's Do it!! 멈춤 자리)까지만 손가락 스크롤을 열어 둠 — 그 스크롤로 브라우저 주소창이 접힘
// 두 손가락(확대)과 data-swipe="x" 안의 가로 끌기(Let's Do it!! 패널 넘기기)는 허용
export function blockTouchScroll() {
  let start: { x: number, y: number, swipeX: boolean } | null = null
  const onStart = (e: TouchEvent) => {
    start = { x: e.touches[0].clientX, y: e.touches[0].clientY, swipeX: !!(e.target as Element).closest?.('[data-swipe="x"]') }
  }
  const onMove = (e: TouchEvent) => {
    if (!matchMedia(TAP_MODE).matches || e.touches.length > 1 || !start || !e.cancelable) return
    if (matchMedia(PHONE_LANDSCAPE).matches) {
      const first = document.querySelector('[data-snap="content"]')
      if (first && scrollY < snapTop(first) - 1) return                         // 아직 인트로 쪽: 기본 스크롤(주소창 접힘)
    }
    const dx = e.touches[0].clientX - start.x, dy = e.touches[0].clientY - start.y
    if (start.swipeX && Math.abs(dx) > Math.abs(dy)) return
    e.preventDefault()
  }
  addEventListener('touchstart', onStart, { passive: true })
  addEventListener('touchmove', onMove, { passive: false })
  return () => {
    removeEventListener('touchstart', onStart)
    removeEventListener('touchmove', onMove)
  }
}
