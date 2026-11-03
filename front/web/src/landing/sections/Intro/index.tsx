import { useEffect, useRef, useState } from 'react'
import styles from './Intro.module.css'

// 인트로: 진동 → 진동이 오면 → 꽃잎이 덮음 → 만남이 시작된다 → 5칸 뒤집기 → tingle 로고 · "스친 순간, 새로운 시작."
// intro.json 은 tools/intro-lottie 생성기 결과물(pnpm --dir tools/intro-lottie build)
// 재생 중에는 스크롤을 잠그고 <html data-intro="playing"> 을 단다(네비가 이걸 보고 숨었다가 내려옴). 건너뛰기 없음
// 동작 줄이기 설정이거나 이번 세션에 이미 봤으면 바로 마지막 장면
export const order = 10

const SEEN_KEY = 'tingle:intro-seen'
const LOAD_LIMIT_MS = 4000                                               // 잠근 뒤 이 안에 lottie · intro.json 을 못 받으면 잠금 해제(느린 네트워크)
const SLACK_MS = 1500                                                    // 재생 시작 후 인트로 길이 + 이만큼 지나면 무슨 일이 있어도 잠금 해제

const seenBefore = () => {
  try { return sessionStorage.getItem(SEEN_KEY) === '1' } catch { return false }
}
const markSeen = () => {
  try { sessionStorage.setItem(SEEN_KEY, '1') } catch { /* 저장 못 해도 다음에 한 번 더 볼 뿐 */ }
}

export default function Intro() {
  const stage = useRef<HTMLDivElement>(null)
  const [done, setDone] = useState(false)
  const [drawn, setDrawn] = useState(false)                                 // 애니메이션이 화면에 그려졌는지(못 불러왔으면 글자로 대신)

  useEffect(() => {
    const root = document.documentElement
    const still = seenBefore() || matchMedia('(prefers-reduced-motion: reduce)').matches
    let cancelled = false
    let finished = false
    let anim: { destroy: () => void } | undefined
    let safety: number | undefined

    const finish = () => {
      if (cancelled || finished) return
      finished = true
      root.removeAttribute('data-intro')
      root.style.overflow = ''
      clearTimeout(safety)
      markSeen()
      setDone(true)
    }

    if (!still) {
      root.setAttribute('data-intro', 'playing')
      root.style.overflow = 'hidden'
      scrollTo(0, 0)
      safety = window.setTimeout(finish, LOAD_LIMIT_MS)                    // 불러오는 동안에도 잠금이 끝없이 이어지지 않게
    }

    Promise.all([import('lottie-web/build/player/lottie_light'), import('./intro.json')])
      .then(([{ default: lottie }, { default: data }]) => {
        if (cancelled || !stage.current) return
        const total = data.op - data.ip
        const play = !still && !finished                                    // 제한 시간이 지나 이미 풀렸으면 다시 잠그거나 재생하지 않고 마지막 장면만
        const a = lottie.loadAnimation({ container: stage.current, renderer: 'svg', loop: false, autoplay: play, animationData: data, rendererSettings: { preserveAspectRatio: 'xMidYMid meet' } })
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
      clearTimeout(safety)
      anim?.destroy()
      root.removeAttribute('data-intro')
      root.style.overflow = ''
    }
  }, [])

  return (
    <section className={styles.intro} aria-labelledby='intro-title'>
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
