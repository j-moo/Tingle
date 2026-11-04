import { useEffect, useRef } from 'react'
import YouTube from '../../components/YouTube.tsx'
import styles from './BrandFilm.module.css'

// 브랜드 필름: 앞뒤 검은 구간 사이에 길게 둔 track 안에서 영상 화면을 고정. 영상이 화면을 꽉 덮는 동안은 네비 · 스크롤바를 숨기고(<html data-film="on">) 재생 버튼만
// 더 내리면 고정이 풀리며 네비 · 스크롤바가 돌아오고 다음 섹션으로. 누를 때만 재생(소리 · 컨트롤), 벗어나면 일시정지
// 영상 화면이 화면과 어긋난 만큼(들어오고 나가는 동안) 흐리게, 딱 맞으면 선명(--miss)
// 영상은 잘리지 않게 넣고 남는 곳은 흐린 썸네일(YouTube fit='contain'). URL 은 유튜브 주소(비어 있으면 "영상 준비 중")
export const order = 30

const BRAND_FILM_URL = 'https://www.youtube.com/watch?v=sB1Mr-eXR3c'                 // 임시 영상(브랜드 필름 나오면 교체)

export default function BrandFilm() {
  const track = useRef<HTMLDivElement>(null)
  const stage = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const el = track.current
    if (!el) return
    const root = document.documentElement
    let frame = 0
    const update = () => {
      frame = 0
      const r = el.getBoundingClientRect()
      const s = stage.current?.getBoundingClientRect()
      if (s) stage.current!.style.setProperty('--miss', String(Math.min(1, Math.abs(s.top) / innerHeight)))   // 영상 화면이 실제 화면에서 어긋난 정도(0 = 딱 맞음) → 흐림 세기
      const covered = r.top <= 0 && r.bottom >= innerHeight
      if (covered === root.hasAttribute('data-film')) return
      if (covered) {
        root.style.setProperty('--sbw', `${innerWidth - root.clientWidth}px`)   // 사라질 스크롤바 폭(index.css 가 그만큼 비워 둠)
        root.setAttribute('data-film', 'on')
      } else {
        root.removeAttribute('data-film')
        root.style.removeProperty('--sbw')
      }
    }
    const onScroll = () => { if (!frame) frame = requestAnimationFrame(update) }
    update()
    addEventListener('scroll', onScroll, { passive: true })
    addEventListener('resize', onScroll)
    return () => {
      removeEventListener('scroll', onScroll)
      removeEventListener('resize', onScroll)
      cancelAnimationFrame(frame)
      root.removeAttribute('data-film')
      root.style.removeProperty('--sbw')
    }
  }, [])

  return (
    <section id='brand-film' className={styles.film} data-nav='dark' aria-label='브랜드 필름'>
      <div ref={track} className={styles.track}>
        <div ref={stage} className={styles.sticky}>
          <YouTube className={styles.video} url={BRAND_FILM_URL} title='tingle 브랜드 필름' fit='contain' player />
        </div>
      </div>
    </section>
  )
}
