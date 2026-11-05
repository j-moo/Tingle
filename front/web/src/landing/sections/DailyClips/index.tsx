import { useEffect, useRef, useState, type MouseEvent } from 'react'
import { goSection, sideOf, TAP_MODE } from '../../sectionNav.ts'
import styles from './DailyClips.module.css'
import clip1 from './media/clip1.mp4'
import clip1Poster from './media/clip1.jpg'
import clip2 from './media/clip2.mp4'
import clip2Poster from './media/clip2.jpg'
import clip3 from './media/clip3.mp4'
import clip3Poster from './media/clip3.jpg'

// 일상 영상 3개: 인트로 분홍 바탕에 계단처럼(데스크톱 가로로 오른쪽이 점점 아래, 모바일 대각선)
// 영상: 브랜드 필름 컷(집 앞 · 캠퍼스 길 · 카페), 소리 없는 5초 반복(480p H.264 faststart) + 첫 장면 poster. 동작 줄이기면 재생하지 않고 poster 만
// 세로 화면(모바일): 섹션 오른쪽이나 아래 "다음"을 누르면 다음 섹션(앱 다운로드 + 푸터 한 화면), 왼쪽은 이전(브랜드 필름)
export const order = 40

const CLIPS = [
  { video: clip1, poster: clip1Poster },
  { video: clip2, poster: clip2Poster },
  { video: clip3, poster: clip3Poster },
]
const STILL = matchMedia('(prefers-reduced-motion: reduce)').matches

export default function DailyClips() {
  const section = useRef<HTMLElement>(null)
  const [portrait, setPortrait] = useState(() => matchMedia(TAP_MODE).matches)

  useEffect(() => {
    const mq = matchMedia(TAP_MODE)
    const onChange = () => setPortrait(mq.matches)
    mq.addEventListener('change', onChange)
    return () => mq.removeEventListener('change', onChange)
  }, [])

  const toNext = () => { if (portrait) goSection(section.current, 1) }
  const onSectionClick = (e: MouseEvent) => { if (portrait) goSection(section.current, sideOf(e.clientX)) }

  return (
    <section id='daily' ref={section} className={styles.daily} aria-labelledby='daily-title' onClick={onSectionClick}>
      <h2 id='daily-title' className={styles.title}>{'#자연스런  #만남  #추구'}</h2>
      <div className={styles.stairs}>
        {CLIPS.map((c) => (
          <video key={c.video} className={styles.clip} src={c.video} poster={c.poster} autoPlay={!STILL} muted loop playsInline preload={STILL ? 'none' : 'auto'} aria-hidden />
        ))}
      </div>
      {portrait && (
        <button className={styles.next} type='button' onClick={(e) => { e.stopPropagation(); toNext() }} aria-label='다음 섹션으로'>
          다음
          <svg viewBox='0 0 24 24' aria-hidden>
            <path d='M6 9l6 6 6-6' />
          </svg>
        </button>
      )}
    </section>
  )
}
