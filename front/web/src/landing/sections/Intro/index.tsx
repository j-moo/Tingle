import { useEffect, useRef, useState } from 'react'
import styles from './Intro.module.css'

// 인트로: 진동 → 진동이 오면 → 꽃잎이 덮음 → 만남이 시작된다 → 5칸 뒤집기 → tingle 로고 · "스친 순간, 새로운 시작."
// intro.json 은 tools/intro-lottie 생성기 결과물(pnpm --dir tools/intro-lottie build)
// 재생 중에는 스크롤을 잠그고 <html data-intro="playing"> 을 단다(네비가 이걸 보고 숨었다가 내려옴). 건너뛰기 없음
// 새로고침(F5)할 때마다 처음부터 재생. 동작 줄이기 설정이면 바로 마지막 장면
export const order = 10

const LOAD_LIMIT_MS = 4000                                               // 잠근 뒤 이 안에 lottie · intro.json 을 못 받으면 잠금 해제(느린 네트워크)
const WIDE = '(min-aspect-ratio: 6/5)'                                    // 가로 화면은 장면(16:9)으로 화면을 꽉 채움(남는 위아래 · 양옆은 잘림), 세로 화면은 잘리면 글자가 끊겨 장면 전체를 맞춰 넣음
const fit = () => (matchMedia(WIDE).matches ? 'xMidYMid slice' : 'xMidYMid meet')
const SLACK_MS = 1500                                                    // 재생 시작 후 인트로 길이 + 이만큼 지나면 무슨 일이 있어도 잠금 해제
const SCROLL_KEYS = new Set([' ', 'PageDown', 'PageUp', 'ArrowDown', 'ArrowUp', 'Home', 'End'])

// 재생 중 스크롤 잠금: 입력(휠 · 터치 · 키 · 스크롤바 끌기)만 막는다. 스크롤바는 index.css 가 숨기고 빈 폭을 메워 화면이 밀리지 않게
const blockScroll = (e: Event) => {
  if (e instanceof KeyboardEvent && !SCROLL_KEYS.has(e.key)) return
  e.preventDefault()
}
const backToTop = () => scrollTo({ top: 0, behavior: 'instant' })          // 포커스 이동 등으로 내려간 경우(부드러운 스크롤로 되감기지 않게)
const lockScroll = (on: boolean) => {
  const set = on ? addEventListener : removeEventListener
  for (const type of ['wheel', 'touchmove', 'keydown']) set(type, blockScroll, { passive: false })
  set('scroll', backToTop)
}

export default function Intro() {
  const stage = useRef<HTMLDivElement>(null)
  const section = useRef<HTMLElement>(null)
  const [done, setDone] = useState(false)
  const [drawn, setDrawn] = useState(false)                                 // 애니메이션이 화면에 그려졌는지(못 불러왔으면 글자로 대신)

  useEffect(() => {
    const root = document.documentElement
    const sbw = `${innerWidth - root.clientWidth}px`                         // 스크롤바 폭(겹쳐 보이는 스크롤바면 0). 재생 동안 숨기고 그만큼 메움(Intro.module.css · index.css)
    section.current?.style.setProperty('--intro-sbw', sbw)
    const still = matchMedia('(prefers-reduced-motion: reduce)').matches
    let cancelled = false
    let finished = false
    let anim: { destroy: () => void } | undefined
    let safety: number | undefined
    const wide = matchMedia(WIDE)
    const refit = () => stage.current?.querySelector('svg')?.setAttribute('preserveAspectRatio', fit())
    wide.addEventListener('change', refit)

    const finish = () => {
      if (cancelled || finished) return
      finished = true
      root.removeAttribute('data-intro')
      if (!still) root.style.removeProperty('--sbw')                       // 직접 단 경우만 — 동작 줄이기면 브랜드 필름이 단 값일 수 있음
      lockScroll(false)
      clearTimeout(safety)
      setDone(true)
    }

    if (!still) {
      root.style.setProperty('--sbw', sbw)
      root.setAttribute('data-intro', 'playing')
      lockScroll(true)
      history.scrollRestoration = 'manual'                                 // 새로고침 때 브라우저가 이전 스크롤 위치로 되돌리지 않게(인트로는 맨 위에서)
      scrollTo(0, 0)
      safety = window.setTimeout(finish, LOAD_LIMIT_MS)                    // 불러오는 동안에도 잠금이 끝없이 이어지지 않게
    }

    Promise.all([import('lottie-web/build/player/lottie_light'), import('./intro.json')])
      .then(([{ default: lottie }, { default: data }]) => {
        if (cancelled || !stage.current) return
        const total = data.op - data.ip
        const play = !still && !finished                                    // 제한 시간이 지나 이미 풀렸으면 다시 잠그거나 재생하지 않고 마지막 장면만
        const a = lottie.loadAnimation({ container: stage.current, renderer: 'svg', loop: false, autoplay: play, animationData: data, rendererSettings: { preserveAspectRatio: fit() } })
        anim = a
        setDrawn(true)
        if (!play) { a.goToAndStop(total - 1, true); finish(); return }
        clearTimeout(safety)
        safety = window.setTimeout(finish, (total / data.fr) * 1000 + SLACK_MS)
        a.addEventListener('complete', finish)
      })
      .catch(finish)                                                       // 불러오기 실패 — 잠근 채로 두지 않음

    return () => {
      cancelled = true
      wide.removeEventListener('change', refit)
      clearTimeout(safety)
      anim?.destroy()
      root.removeAttribute('data-intro')
      if (!still) root.style.removeProperty('--sbw')
      lockScroll(false)
    }
  }, [])

  return (
    <section ref={section} className={styles.intro} aria-labelledby='intro-title'>
      {/* 평소엔 화면 읽기용(숨김). 애니메이션 없이 끝났으면(불러오기 실패 · 시간 초과) 이 글자를 마지막 장면 대신 보여줌 */}
      <h1 id='intro-title' className={done && !drawn ? styles.fallback : styles.srOnly}>
        <span className={styles.wordmark}>tingle</span>
        <span className={styles.tagline}>스친 순간, 새로운 시작.</span>
      </h1>
      <div ref={stage} className={styles.stage} aria-hidden />
      <a className={`${styles.hint} ${done ? styles.show : ''}`} href='#how-it-works' aria-hidden={!done} tabIndex={done ? 0 : -1}>
        아래로 스크롤하세요
        <svg viewBox='0 0 24 24' aria-hidden>
          <path d='M6 9l6 6 6-6' />
        </svg>
      </a>
    </section>
  )
}
