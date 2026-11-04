import YouTube from '../../components/YouTube.tsx'
import styles from './DailyClips.module.css'

// 일상 영상 3개: 인트로 분홍 바탕에 계단처럼(데스크톱 가로로 오른쪽이 점점 아래, 모바일 대각선). 영상은 유튜브 URL
export const order = 40

const CLIPS = [
  { url: '', title: '일상 영상 1' },
  { url: '', title: '일상 영상 2' },
  { url: '', title: '일상 영상 3' },
]

export default function DailyClips() {
  return (
    <section id='daily' className={styles.daily} aria-labelledby='daily-title'>
      <h2 id='daily-title' className={styles.title}>{'#자연스런  #만남  #추구'}</h2>
      <div className={styles.stairs}>
        {CLIPS.map((c) => (
          <YouTube key={c.title} className={styles.clip} url={c.url} title={c.title} />
        ))}
      </div>
    </section>
  )
}
