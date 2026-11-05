import { useEffect, useRef, useState, type CSSProperties } from 'react'
import { jumping, TAP_MODE } from '../../sectionNav.ts'
import styles from './LetsDoIt.module.css'

// "Let's Do it!!" — 화면에 고정된 채 세로 스크롤만큼 3단계가 옆으로 지나가고, 다 보면 고정이 풀려 아래로 내려간다
// 섹션 높이 = (단계 수 × --hold + 1) 화면(LetsDoIt.module.css), 안쪽은 sticky. 스크롤 위치로 현재 단계를 정해 --i 로 넘기면 CSS 가 탄성 곡선으로 줄을 옮긴다
// 패널마다 멈춤: 입력은 건드리지 않고, 각 패널이 --hold 화면만큼 스크롤하는 동안 제자리에 머물다 경계를 넘는 순간 슉 넘어감
// 동작 줄이기 · 낮은 화면이면 고정 없이 세로로 나열(CSS)
// 세로 화면(모바일): 스크롤해 이 섹션이 화면에 맞으면 화면을 멈추고(세로 스크롤 잠금), 오른쪽을 누르면 다음 · 왼쪽은 이전, 밀어도 넘김(패널 줄은 scroll-snap 으로 옆으로).
//   STEP 3 에서 한 번 더 넘기면 잠금이 풀리며 다음 섹션(브랜드 필름) 화면에 딱 맞춰 멈추고, STEP 1 에서 손가락으로 뒤로는 막힘(인트로로 돌아가지 않음, 휠 · 키보드만 위로). 진행 막대를 눌러도 그 단계로
export const order = 20

type Step = { step: string, title: string, body: string, image?: string }
const STEPS: Step[] = [
  { step: 'STEP 1', title: '키링과 함께 일상으로', body: '키링을 등록하고 평소처럼 지니고 다녀요.' },
  { step: 'STEP 2', title: '스치면 조용히 알려요', body: '잘 맞는 사람과 가까워지면 키링이 짧게 알려 줘요.' },
  { step: 'STEP 3', title: '서로 수락하면 대화', body: '앱에서 제안을 확인하고 두 사람 모두 원할 때 대화가 열려요.' },
]
const LAST = STEPS.length - 1
const STEP_KEYS: Record<string, 1 | -1> = { ArrowDown: 1, ArrowRight: 1, PageDown: 1, ' ': 1, ArrowUp: -1, ArrowLeft: -1, PageUp: -1 }
const WHEEL_GAP_MS = 500                                                       // 잠금 중 휠은 이 간격마다 한 단계
const UNLOCK_GAP_MS = 800                                                      // 풀린 직후 이 동안은 다시 잠그지 않음(빠져나가는 스크롤)
const slide = (t: HTMLOListElement, i: number) => {                            // 세로 화면: 패널 줄을 i 번째 패널로
  const li = t.children
  t.scrollTo({ left: (li[i] as HTMLElement).offsetLeft - (li[0] as HTMLElement).offsetLeft, behavior: 'smooth' })
}

export default function LetsDoIt() {
  const section = useRef<HTMLElement>(null)
  const track = useRef<HTMLOListElement>(null)
  const [active, setActive] = useState(0)

  useEffect(() => {
    const el = section.current
    if (!el) return
    let frame = 0
    const portrait = matchMedia(TAP_MODE)
    // 세로 화면: 패널 줄의 가로 스크롤 위치로 현재 단계
    const step = () => { const li = track.current?.children; const w = li && li.length > 1 ? (li[1] as HTMLElement).offsetLeft - (li[0] as HTMLElement).offsetLeft : 0; return w > 0 ? w : 1 }
    let cur = 0                                                              // 세로 화면의 현재 단계(잠금 중 넘기기 기준)
    const onSwipe = () => { const t = track.current; if (t && portrait.matches) { cur = Math.min(LAST, Math.max(0, Math.round(t.scrollLeft / step()))); setActive(cur) } }
    track.current?.addEventListener('scroll', onSwipe, { passive: true })

    // 세로 화면 잠금: 섹션(위 여백 지난 곳)이 화면 맨 위에 닿으면 멈추고, 입력을 단계 넘기기로 바꿈
    const root = document.documentElement
    let locked = false, lockedAt = 0, lastY = scrollY, freeUntil = 0, wheelUntil = 0, touchedAt = 0, inputAt = -1e9
    let touch: { x: number, y: number, at: number, track: boolean, button: boolean } | null = null
    const lockY = () => el.getBoundingClientRect().top + scrollY + parseFloat(getComputedStyle(el).paddingTop)
    const setLock = (on: boolean) => {
      locked = on
      lockedAt = performance.now()
      if (on) root.dataset.stepsLock = 'on'
      else delete root.dataset.stepsLock
    }
    const unlock = (dir: 1 | -1) => {                                        // 끝에서 한 번 더 넘기면 풀림: 앞으로는 다음 섹션(브랜드 필름) 화면에 딱 맞춰, 뒤로는 반 화면 위로
      setLock(false)
      freeUntil = performance.now() + UNLOCK_GAP_MS
      const next = el.nextElementSibling
      if (dir > 0 && next) scrollTo({ top: next.getBoundingClientRect().top + scrollY, behavior: 'smooth' })
      else scrollBy({ top: dir * innerHeight * 0.5, behavior: 'smooth' })
    }
    // byTouch: 손가락(누르기 · 밀기)으로 넘길 때 — STEP 1 에서 뒤로는 막음(인트로로 돌아가지 않게). 휠 · 키보드는 위로 빠져나갈 수 있게 둠
    const move = (dir: 1 | -1, byTouch = false) => {
      const t = track.current
      if (!t) return
      const next = cur + dir
      if (next < 0 && byTouch) return
      if (next < 0 || next > LAST) unlock(dir)
      else slide(t, next)
    }
    const pin = () => {
      const y = scrollY, L = lockY()
      if (locked && jumping()) setLock(false)                                // 메뉴 링크로 이동 중이면 풀고 보냄
      if (locked) { if (Math.abs(y - L) > 1) scrollTo({ top: L, behavior: 'instant' }); return }   // 관성 스크롤이 남아도 제자리
      const byUser = performance.now() - inputAt < 1000 && Math.abs(y - L) < innerHeight   // 직접 스크롤해 들어올 때만(메뉴 링크 · 한 번에 건너뛰는 이동은 통과)
      if (byUser && !jumping() && performance.now() > freeUntil && ((lastY < L - 1 && y >= L - 1) || (lastY > L + 1 && y <= L + 1))) {
        setLock(true)
        scrollTo({ top: L, behavior: 'instant' })
      }
      lastY = scrollY
    }
    const onTouchStart = (e: TouchEvent) => {
      inputAt = performance.now()
      const target = e.target as Element
      touch = { x: e.touches[0].clientX, y: e.touches[0].clientY, at: performance.now(), track: !!track.current?.contains(target), button: !!target.closest('button, a, header') }
    }
    const onTouchMove = (e: TouchEvent) => {
      inputAt = performance.now()
      if (!locked || !touch) return
      const dx = e.touches[0].clientX - touch.x, dy = e.touches[0].clientY - touch.y
      if (touch.track && Math.abs(dx) > Math.abs(dy)) return                 // 패널 줄 가로 밀기는 그대로(scroll-snap)
      e.preventDefault()                                                       // 세로 스크롤 막음
    }
    const onTouchEnd = (e: TouchEvent) => {
      if (!locked || !touch || touch.button || touch.at < lockedAt) { touch = null; return }   // 진행 막대는 onClick 으로, 잠기게 만든 그 손짓은 넘기기로 치지 않음
      const dx = e.changedTouches[0].clientX - touch.x, dy = e.changedTouches[0].clientY - touch.y, onTrack = touch.track
      touch = null
      touchedAt = performance.now()
      if (Math.abs(dx) < 10 && Math.abs(dy) < 10) { move(e.changedTouches[0].clientX < innerWidth / 2 ? -1 : 1, true); return }   // 오른쪽을 누르면 다음, 왼쪽은 이전
      if (Math.abs(dx) > Math.abs(dy)) { if (!onTrack) move(dx < 0 ? 1 : -1, true); return }   // 패널 줄 밖에서 옆으로 밀기
      move(dy < 0 ? 1 : -1, true)                                              // 위로 밀면 다음, 아래로 밀면 이전
    }
    const onPointer = () => { inputAt = performance.now() }                  // 마우스 클릭으로 섹션 이동해 들어와도 '직접 이동'으로 침
    const onClick = (e: MouseEvent) => {                                     // 마우스 클릭(세로로 줄인 데스크톱 창)도 같은 규칙. 터치 뒤 따라오는 클릭은 무시
      if (!locked || performance.now() - touchedAt < 700 || (e.target as Element).closest('button, a, header')) return
      move(e.clientX < innerWidth / 2 ? -1 : 1)
    }
    const onWheel = (e: WheelEvent) => {
      inputAt = performance.now()
      if (!locked) return
      e.preventDefault()
      if (performance.now() < wheelUntil || Math.abs(e.deltaY) < 4) return
      wheelUntil = performance.now() + WHEEL_GAP_MS
      move(e.deltaY > 0 ? 1 : -1)
    }
    const onKey = (e: KeyboardEvent) => {
      inputAt = performance.now()
      const dir = STEP_KEYS[e.key]
      if (!locked || !dir || (e.target as Element).closest?.('button, a, input, textarea')) return
      e.preventDefault()
      move(dir)
    }
    const listen = (on: boolean) => {                                        // 누르기 모드에서만(데스크톱 스크롤에 non-passive 리스너를 걸지 않음)
      const set = on ? addEventListener : removeEventListener
      set('touchstart', onTouchStart as EventListener, { passive: true })
      set('touchmove', onTouchMove as EventListener, { passive: false })
      set('touchend', onTouchEnd as EventListener)
      set('wheel', onWheel as EventListener, { passive: false })
      set('click', onClick as EventListener)
      set('keydown', onKey as EventListener)
      set('pointerdown', onPointer)
    }
    let listening = portrait.matches
    listen(listening)
    const onMode = () => { if (portrait.matches !== listening) { listening = portrait.matches; listen(listening) } }
    portrait.addEventListener('change', onMode)

    const update = () => {
      frame = 0
      if (portrait.matches) { onSwipe(); pin(); return }
      if (locked) setLock(false)                                             // 가로로 돌리면 풀림
      const r = el.getBoundingClientRect()
      const gap = parseFloat(getComputedStyle(el).paddingTop)               // 위 여백(--rise-gap)은 지나간 뒤부터 고정
      const travel = r.height - gap - innerHeight                          // 고정된 채 스크롤되는 거리
      const progress = travel > 0 ? Math.min(1, Math.max(0, (-r.top - gap) / travel)) : 0
      setActive(Math.min(LAST, Math.floor(progress * STEPS.length)))       // 고정 구간을 단계 수로 똑같이 나눠 각 패널이 --hold 화면씩 머묾
    }

    const onScroll = () => { if (!frame) frame = requestAnimationFrame(update) }
    update()
    addEventListener('scroll', onScroll, { passive: true })
    addEventListener('resize', onScroll)
    portrait.addEventListener('change', onScroll)
    const t = track.current
    return () => {
      removeEventListener('scroll', onScroll)
      removeEventListener('resize', onScroll)
      portrait.removeEventListener('change', onScroll)
      t?.removeEventListener('scroll', onSwipe)
      listen(false)
      portrait.removeEventListener('change', onMode)
      cancelAnimationFrame(frame)
      setLock(false)
    }
  }, [])

  // 진행 막대를 누르면 그 단계 구간의 가운데로 부드럽게 스크롤(시작 지점으로 가면 앞으로 갈 때 감속 끝에야 경계를 넘어 늦게 바뀜)
  const goTo = (i: number) => {
    const el = section.current
    if (!el) return
    const t = track.current
    if (t && matchMedia(TAP_MODE).matches) { slide(t, i); return }          // 세로 화면: 패널 줄을 옆으로
    const r = el.getBoundingClientRect()
    const gap = parseFloat(getComputedStyle(el).paddingTop)
    const travel = r.height - gap - innerHeight
    scrollTo({ top: r.top + scrollY + gap + travel * ((i + 0.5) / STEPS.length) })
  }

  return (
    <section id='how-it-works' ref={section} className={styles.section} data-snap='content' style={{ '--steps': STEPS.length, '--i': active } as CSSProperties} aria-labelledby='how-it-works-title'>
      <div className={styles.sticky}>
        <h2 id='how-it-works-title' className={styles.label}>Let's Do it!!</h2>
        <ol ref={track} className={styles.track} data-swipe='x'>
          {STEPS.map((s, i) => (
            <li key={s.step} className={styles.panel} aria-current={i === active ? 'step' : undefined}>
              {s.image ? <img className={styles.visual} src={s.image} alt='' /> : <div className={styles.visual} aria-hidden />}
              <div className={styles.text}>
                <p className={styles.step}>{s.step}</p>
                <h3 className={styles.title}>{s.title}</h3>
                <p className={styles.body}>{s.body}</p>
              </div>
            </li>
          ))}
        </ol>
        <div className={styles.bars}>
          {STEPS.map((s, i) => (
            <button key={s.step} type='button' data-label={s.step} className={i === active ? styles.on : undefined} aria-label={`${s.step} 보기`} aria-current={i === active ? 'step' : undefined} onClick={() => goTo(i)} />
          ))}
        </div>
      </div>
    </section>
  )
}
