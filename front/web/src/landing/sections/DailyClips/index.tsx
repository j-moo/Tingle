import { useEffect, useRef, useState, type MouseEvent } from 'react'
import YouTube from '../../components/YouTube.tsx'
import { goSection, sideOf, TAP_MODE } from '../../sectionNav.ts'
import styles from './DailyClips.module.css'

// 일상 영상 3개: 인트로 분홍 바탕에 계단처럼(데스크톱 가로로 오른쪽이 점점 아래, 모바일 대각선). 영상은 유튜브 URL
// 세로 화면(모바일): 섹션 오른쪽이나 아래 "다음"을 누르면 다음 섹션(앱 다운로드 + 푸터 한 화면), 왼쪽은 이전(브랜드 필름)
export const order = 40

const CLIPS = [
  { url: '', title: '일상 영상 1' },
  { url: '', title: '일상 영상 2' },
  { url: '', title: '일상 영상 3' },
]

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
          <YouTube key={c.title} className={styles.clip} url={c.url} title={c.title} />
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
