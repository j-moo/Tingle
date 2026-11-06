import { useEffect, useRef, useState, type MouseEvent } from 'react'
import YouTube from '../../components/YouTube.tsx'
import { goSection, sideOf, swiftTo, SWIFT_MS, TAP_MODE } from '../../sectionNav.ts'
import styles from './BrandFilm.module.css'

// 브랜드 필름(당근 brandnew 브랜드 필름 흐름을 참고하되, 어둡게 대신 흐림으로)
// 검은 화면에서 문구 세 줄(바쁘게 지나간 하루 → 무심코 지나간 인연을 이어줄게요 → 브랜드 필름 보기)이 스크롤만큼 흐림이 걷히며 나타남(데스크톱)
// → 화면 고정 → ① 작은 영상(75%)이 제자리에서 아래로부터 떠오르며 나타남(감속) → ② 잠시 멈춤
// → ③ 커지며 전체 화면, 흐림 · 문구가 걷힘 → ④ 머묾 → ⑤ 다음 섹션(일상 영상)이 위로 올라와 덮으며 다시 흐려짐. 단계 길이는 BrandFilm.module.css 의 .track 변수
// 커지기 시작할 때 유튜브 플레이어를 썸네일 아래에 미리 만들어 둠. 전체 화면이 되면 흰 재생 버튼 → 누르면 썸네일이 걷히고 소리와 함께 재생(유튜브 컨트롤). 덮이거나 전체 화면을 벗어나면 일시정지 · 버튼 다시
// 전체 화면인 동안은 네비 · 스크롤바 숨김(<html data-film="on">, index.css · Nav)
// 세로 화면(모바일): 스크롤 연출 없이 16:9 영상 카드 → 누르면 기기 전체화면(가능하면 가로로 돌림)으로 재생, ✕ · 뒤로가기로 닫으면 제자리 · 일시정지
//   전체화면을 못 쓰는 브라우저(아이폰 사파리)는 화면을 덮는 창으로 펼침(유튜브 컨트롤의 전체화면으로 가로 재생)
//   영상 카드 밖 검은 영역의 오른쪽(또는 아래 "다음")을 누르면 다음 섹션(일상 영상), 왼쪽은 이전 섹션(Let's Do it!!)
export const order = 30

const BRAND_FILM_URL = 'https://youtu.be/UMA1McwDOhI'                               // 팅글 브랜드 필름(TINGLE 팅글 채널)
const IDLE_BLUR = 8                                                                    // 작은 영상일 때 흐림(px). 나타나는 동안은 여기에 +16, 커지며 0
const COVER_BLUR = 28                                                                  // 다음 섹션이 다 덮었을 때 흐림(px)
const LINE_REVEAL = 0.5                                                                // 문구 한 줄이 나타나는 데 쓰는 몫(나머지 몫은 다음 줄까지 쉼)

const clamp = (v: number) => Math.min(1, Math.max(0, v))
const easeOut = (t: number) => 1 - (1 - t) ** 3
const easeInOut = (t: number) => (t < 0.5 ? 4 * t ** 3 : 1 - (-2 * t + 2) ** 3 / 2)

export default function BrandFilm() {
  const section = useRef<HTMLElement>(null)
  const track = useRef<HTMLDivElement>(null)
  const stage = useRef<HTMLDivElement>(null)
  const copy = useRef<HTMLDivElement>(null)
  const [load, setLoad] = useState(false)                                     // 커지기 시작하면 플레이어를 미리 만듦
  const [full, setFull] = useState(false)                                     // 전체 화면이고 반 넘게 덮이지 않았으면 흰 재생 버튼
  const fullNow = useRef(false)                                               // 같은 값(마지막 스크롤 계산 기준) — 예약된 재생이 실행될 때 확인
  const watchTimer = useRef(0)                                                // "브랜드 필름 보기" 로 예약한 재생
  const [playing, setPlaying] = useState(false)                               // 흰 재생 버튼으로 켬, 덮이거나 위로 벗어나면 끔
  const [portrait, setPortrait] = useState(() => matchMedia(TAP_MODE).matches)
  const [expanded, setExpanded] = useState(false)                             // 세로 화면: 카드를 눌러 전체 화면으로 펼쳤는지

  useEffect(() => {
    const el = track.current, st = stage.current
    if (!el || !st) return
    const root = document.documentElement
    let frame = 0
    const mq = matchMedia(TAP_MODE)
    const update = () => {
      frame = 0
      if (document.fullscreenElement) return                                 // 전체화면(가로로 돌아감)으로 보는 동안은 스크롤 연출 · 정지 판단 안 함
      setPortrait(mq.matches)
      const r = el.getBoundingClientRect()
      if (mq.matches) {                                                      // 세로 화면: 스크롤 연출 없음. 가까워지면 플레이어만 미리 만듦
        if (r.top < innerHeight * 2) setLoad(true)
        if (root.hasAttribute('data-film')) { root.removeAttribute('data-film'); root.style.removeProperty('--sbw') }
        return
      }
      setExpanded(false)                                                     // 펼친 채 가로로 돌리면 닫음
      const css = getComputedStyle(el)
      const svh = (name: string) => parseFloat(css.getPropertyValue(name)) / 100 * innerHeight
      const fade = svh('--fade-length'), hold = svh('--center-hold'), grow = svh('--grow-length'), words = svh('--copy-length'), lead = svh('--lead-top')
      const t = -r.top - words                                               // 문구 구간을 지나 영상 단계에서 스크롤한 거리
      const shown = easeOut(clamp(t / fade))                                 // ① 나타남
      const fullness = easeInOut(clamp((t - fade - hold) / grow))            // ③ 커짐(0 = 작음, 1 = 전체 화면)
      const next = section.current?.nextElementSibling?.getBoundingClientRect().top ?? Infinity   // ⑤ 위로 올라오는 다음 섹션
      const cover = clamp((innerHeight - next) / innerHeight)
      const idle = (IDLE_BLUR + (1 - shown) * 16) * (1 - fullness)
      st.style.setProperty('--shown', String(shown))
      st.style.setProperty('--full', String(fullness))
      st.style.setProperty('--cover', String(cover))
      st.style.setProperty('--blur', `${Math.max(idle, cover * COVER_BLUR)}px`)
      const cp = copy.current
      if (cp) {                                                              // 문구: 섹션 맨 위부터 (검은 구간 + 문구 구간)의 90% 동안 세 줄이 차례로(각 칸의 앞 LINE_REVEAL 만큼 나타나고 나머지는 쉼)
        const q = clamp((r.top - lead) / -(lead + words) / 0.9) * 3
        const out = clamp((fullness - 0.55) / 0.45)                          // 영상이 커지며 초점이 거의 맞을 때까지 문구 유지, 그 뒤 걷힘
        cp.style.setProperty('--c1', String(clamp(q / LINE_REVEAL)))
        cp.style.setProperty('--c2', String(clamp((q - 1) / LINE_REVEAL)))
        cp.style.setProperty('--c3', String(clamp((q - 2) / LINE_REVEAL)))
        cp.style.setProperty('--out', String(out))
        cp.style.visibility = out >= 1 ? 'hidden' : ''
        const ready = q > 2 + LINE_REVEAL * 0.8 && out < 0.5                 // 버튼이 거의 다 나왔을 때만 누를 수 있게
        cp.toggleAttribute('data-ready', ready)
        cp.querySelector('button')?.toggleAttribute('inert', !ready)         // 안 보이는 동안은 키보드(Tab)로도 닿지 않게
      }
      if (fullness > 0) setLoad(true)
      fullNow.current = fullness >= 1 && cover <= 0.5
      setFull(fullNow.current)
      if (cover > 0.5 || fullness < 1) setPlaying(false)                     // 반 넘게 덮이거나 전체 화면을 벗어나면(작아지기 시작) 일시정지
      const hideChrome = fullness >= 1 && r.bottom >= innerHeight && next >= innerHeight - 1   // 덮이지 않은 전체 화면인 동안만 네비 · 스크롤바 숨김
      if (hideChrome === root.hasAttribute('data-film')) return
      if (hideChrome) {
        root.style.setProperty('--sbw', `${innerWidth - root.clientWidth}px`)   // 사라질 스크롤바 폭(index.css 가 그만큼 비워 둠)
        root.setAttribute('data-film', 'on')
      } else {
        root.removeAttribute('data-film')
        root.style.removeProperty('--sbw')
      }
    }
    const onScroll = () => { if (!frame) frame = requestAnimationFrame(update) }
    onScroll()
    addEventListener('scroll', onScroll, { passive: true })
    addEventListener('resize', onScroll)
    mq.addEventListener('change', onScroll)
    return () => {
      removeEventListener('scroll', onScroll)
      removeEventListener('resize', onScroll)
      mq.removeEventListener('change', onScroll)
      cancelAnimationFrame(frame)
      if (!root.hasAttribute('data-film')) return                           // 자기가 단 경우만 지움(인트로가 단 --sbw 는 그대로)
      root.removeAttribute('data-film')
      root.style.removeProperty('--sbw')
    }
  }, [portrait])                                                             // 세로 ↔ 가로가 바뀌어 문구가 새로 그려지면 바로 한 번 계산(스크롤 전에도 보이게)

  // 세로 화면에서 펼친 동안: 뒤 페이지 스크롤 잠금 · Esc 로 닫기
  useEffect(() => {
    if (!expanded) return
    const root = document.documentElement
    root.style.overflow = 'hidden'
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') close() }
    addEventListener('keydown', onKey)
    return () => {
      root.style.overflow = ''
      removeEventListener('keydown', onKey)
    }
  }, [expanded])

  // 전체화면에서 나오면(뒤로가기 · ✕) 닫고 일시정지
  const ownFullscreen = useRef(false)                                          // 이 섹션이 연 전체화면인지(유튜브 컨트롤의 전체화면과 구분)
  useEffect(() => {
    const onChange = () => {
      if (document.fullscreenElement || !ownFullscreen.current) return
      ownFullscreen.current = false
      screen.orientation?.unlock?.()
      setExpanded(false)
      setPlaying(false)
    }
    document.addEventListener('fullscreenchange', onChange)
    return () => document.removeEventListener('fullscreenchange', onChange)
  }, [])

  const play = () => {
    if (portrait) {
      setExpanded(true)
      const st = stage.current
      if (st?.requestFullscreen) {                                           // 누른 동작 안에서 바로 요청해야 허용됨
        ownFullscreen.current = true
        st.requestFullscreen({ navigationUI: 'hide' })
          .then(() => (screen.orientation as ScreenOrientation & { lock?: (o: string) => Promise<void> }).lock?.('landscape'))
          .catch(() => { if (document.fullscreenElement !== st) ownFullscreen.current = false })   // 회전만 거절되면 전체화면으로 봄, 전체화면이 거절되면 아래 창(.expanded)으로 보고 표시도 지움
      }
    }
    setPlaying(true)
  }
  const watch = () => {                                                      // 브랜드 필름 보기: 전체 화면 자리까지 이동한 뒤 재생
    const el = track.current
    if (!el) return
    const css = getComputedStyle(el)
    const len = ['--copy-length', '--fade-length', '--center-hold', '--grow-length'].reduce((sum, name) => sum + parseFloat(css.getPropertyValue(name)), 0)
    swiftTo(el.getBoundingClientRect().top + scrollY + len / 100 * innerHeight + 2)
    clearTimeout(watchTimer.current)
    watchTimer.current = window.setTimeout(() => { if (fullNow.current) setPlaying(true) }, SWIFT_MS + 80)   // 그사이 섹션을 벗어났으면(End 키 등) 재생하지 않음
  }
  useEffect(() => () => clearTimeout(watchTimer.current), [])
  const toNext = () => goSection(section.current, 1)                         // 세로 화면: 다음 섹션 화면에 맞춰
  const onFilmClick = (e: MouseEvent) => {                                   // 카드 · 버튼 밖(검은 영역): 오른쪽은 다음, 왼쪽은 이전
    if (!portrait || expanded || stage.current?.contains(e.target as Node) || (e.target as Element).closest('button')) return
    goSection(section.current, sideOf(e.clientX))
  }
  function close() {
    if (document.fullscreenElement) { document.exitFullscreen().catch(() => {}); return }   // 나머지는 fullscreenchange 에서
    setExpanded(false)
    setPlaying(false)
  }

  return (
    <section id='brand-film' ref={section} className={styles.film} style={expanded ? { zIndex: 100 } : undefined} data-nav='dark' aria-label='브랜드 필름' onClick={onFilmClick}>
      {!portrait && (
        <div ref={copy} className={styles.copy}>
          <p className={styles.line1}>바쁘게 지나간 하루</p>
          <p className={styles.line2}>무심코 지나간 인연을 이어줄게요</p>
          <button className={styles.watch} type='button' onClick={watch}>
            브랜드 필름 보기
          </button>
        </div>
      )}
      <div ref={track} className={styles.track}>
        <div ref={stage} className={`${styles.sticky} ${expanded ? styles.expanded : ''}`} role={expanded ? 'dialog' : undefined} aria-modal={expanded || undefined} aria-label={expanded ? 'tingle 브랜드 필름' : undefined}>
          <div className={styles.frame}>
            <YouTube className={styles.video} url={BRAND_FILM_URL} title='tingle 브랜드 필름' load={load} poster={!playing} playing={playing} />
          </div>
          {BRAND_FILM_URL && (portrait || full) && !playing && (
            <button className={styles.play} type='button' onClick={play} aria-label='tingle 브랜드 필름 재생'>
              <svg viewBox='0 0 24 24' aria-hidden>
                <path d='M8 5.5v13l11-6.5z' />
              </svg>
            </button>
          )}
          {expanded && (
            <button className={styles.close} type='button' onClick={close} aria-label='브랜드 필름 닫기'>
              <svg viewBox='0 0 24 24' aria-hidden>
                <path d='M6 6l12 12M18 6L6 18' />
              </svg>
            </button>
          )}
        </div>
      </div>
      {portrait && !expanded && (
        <button className={styles.next} type='button' onClick={toNext} aria-label='다음 섹션으로'>
          다음
          <svg viewBox='0 0 24 24' aria-hidden>
            <path d='M6 9l6 6 6-6' />
          </svg>
        </button>
      )}
    </section>
  )
}
