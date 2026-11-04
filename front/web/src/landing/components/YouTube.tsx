import { useEffect, useRef, useState } from 'react'
import styles from './YouTube.module.css'

// 유튜브 URL 로 넣는 영상(브랜드 필름 · 일상 영상). 영상 파일은 저장소 · 서버에 두지 않음
// - 배경(기본): 처음 화면에 들어올 때 iframe 을 만들어 무음 · 반복 · 컨트롤 없이 재생, 벗어나면 멈추고 다시 들어오면 재생
// - player: 누를 때만 재생(소리 · 유튜브 컨트롤 있음). 누르기 전엔 썸네일 + 재생 버튼, 화면에서 벗어나면 일시정지하고 버튼이 다시 뜸(이어 보기)
// - fit: cover = 상자를 16:9 영상으로 꽉 채움(넘치는 쪽 잘림) / contain = 잘리지 않게 넣고 남는 곳은 흐린 썸네일 + 가장자리 그라데이션
// - URL 이 비었거나 알아볼 수 없으면 자리 표시
// 재생 제어는 유튜브 IFrame API 의 postMessage 명령(enablejsapi=1)으로 한다 — 스크립트를 따로 받지 않음

const HOST = 'https://www.youtube-nocookie.com'

function youtubeId(url: string): string | null {
  try {
    const u = new URL(url)
    const id = u.hostname.endsWith('youtu.be') ? u.pathname.slice(1) : u.searchParams.get('v') ?? u.pathname.match(/\/(?:embed|shorts|live)\/([^/?]+)/)?.[1]
    return id && /^[\w-]{11}$/.test(id) ? id : null
  } catch {
    return null
  }
}

type Props = { url: string, title: string, fit?: 'cover' | 'contain', player?: boolean, className?: string }

export default function YouTube({ url, title, fit = 'cover', player = false, className }: Props) {
  const box = useRef<HTMLDivElement>(null)
  const frame = useRef<HTMLIFrameElement>(null)
  const [mounted, setMounted] = useState(false)                             // iframe 을 만들었는지
  const [paused, setPaused] = useState(true)                                // player: 재생 버튼을 보일지
  const id = youtubeId(url)

  const command = (func: string) => frame.current?.contentWindow?.postMessage(JSON.stringify({ event: 'command', func, args: [] }), HOST)

  useEffect(() => {
    const el = box.current
    if (!el || !id) return
    const io = new IntersectionObserver(([e]) => {
      if (player) {
        if (!e.isIntersecting) { command('pauseVideo'); setPaused(true) }
        return
      }
      if (e.isIntersecting) { setMounted(true); command('playVideo') }
      else command('pauseVideo')
    }, { threshold: player ? 0 : 0.15 })
    io.observe(el)
    return () => io.disconnect()
  }, [id, player])

  const play = () => {
    if (mounted) command('playVideo')
    else setMounted(true)                                                    // 처음 누르면 iframe 을 autoplay 로 만들어 바로 재생(누른 동작이 있어 소리도 허용)
    setPaused(false)
  }

  const params: Record<string, string> = player
    ? { autoplay: '1', controls: '1', playsinline: '1', rel: '0', iv_load_policy: '3', enablejsapi: '1', origin: location.origin }
    : { autoplay: '1', mute: '1', loop: '1', playlist: id ?? '', controls: '0', playsinline: '1', rel: '0', disablekb: '1', iv_load_policy: '3', enablejsapi: '1', origin: location.origin }
  const src = id && `${HOST}/embed/${id}?${new URLSearchParams(params)}`
  const thumb = id && `https://i.ytimg.com/vi/${id}/hqdefault.jpg`             // 어느 영상에나 있는 썸네일(흐린 배경 · 큰 썸네일이 없을 때 대신)
  const toThumb = (img: HTMLImageElement) => { if (!img.src.endsWith('/hqdefault.jpg')) img.src = thumb! }   // 큰 썸네일이 없으면(오류 또는 120px 회색 대체 이미지) 작은 썸네일로

  return (
    <div ref={box} className={[styles.box, styles[fit], player ? styles.player : '', className ?? ''].join(' ')}>
      {!id && <span className={styles.empty}>영상 준비 중</span>}
      {id && fit === 'contain' && <img className={styles.backdrop} src={thumb!} alt='' aria-hidden />}
      {id && player && !mounted && <img className={styles.frame} src={`https://i.ytimg.com/vi/${id}/maxresdefault.jpg`} onError={(e) => toThumb(e.currentTarget)} onLoad={(e) => { if (e.currentTarget.naturalWidth <= 120) toThumb(e.currentTarget) }} alt='' aria-hidden />}
      {id && mounted && <iframe ref={frame} className={styles.frame} src={src!} title={title} allow='autoplay; encrypted-media; picture-in-picture; fullscreen' referrerPolicy='strict-origin-when-cross-origin' tabIndex={player ? 0 : -1} />}
      {id && player && paused && (
        <button className={styles.play} type='button' onClick={play} aria-label={`${title} ${mounted ? '이어 보기' : '재생'}`}>
          <svg viewBox='0 0 24 24' aria-hidden>
            <path d='M8 5.5v13l11-6.5z' />
          </svg>
        </button>
      )}
    </div>
  )
}
