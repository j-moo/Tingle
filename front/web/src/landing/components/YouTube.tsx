import { useEffect, useRef, useState } from 'react'
import styles from './YouTube.module.css'

// 브랜드 필름 유튜브 플레이어(소리 · 유튜브 컨트롤). 짧은 장식 클립(단계 · 일상 영상)은 섹션 폴더의 로컬 mp4 를 쓴다
// - 부모가 load 하면 iframe 을 미리 만들고, poster 가 꺼질 때까지 그 위를 큰 썸네일로 덮음(로딩 중 검은 화면 안 보이게)
// - 재생 · 멈춤은 부모의 playing(누른 동작에서 켜야 소리 있는 재생이 허용됨)
//   플레이어 준비(onReady)를 확인해, 준비 전에 누르면 썸네일 위 로딩 표시를 띄워 두었다가 준비되는 즉시 재생(명령 유실 방지)
// - 영상은 잘리지 않게 상자 안에 16:9 로 넣고, 남는 곳은 흐린 썸네일 + 가장자리 그라데이션
// 재생 제어는 유튜브 IFrame API 의 postMessage 명령(enablejsapi=1)으로 한다 — 스크립트를 따로 받지 않음

const HOST = 'https://www.youtube-nocookie.com'
const LISTEN_EVERY_MS = 250                                                  // 준비 신호를 받을 때까지 '듣고 있음'을 다시 알리는 간격

function youtubeId(url: string): string | null {
  try {
    const u = new URL(url)
    const id = u.hostname.endsWith('youtu.be') ? u.pathname.slice(1) : u.searchParams.get('v') ?? u.pathname.match(/\/(?:embed|shorts|live)\/([^/?]+)/)?.[1]
    return id && /^[\w-]{11}$/.test(id) ? id : null
  } catch {
    return null
  }
}

type Props = { url: string, title: string, load?: boolean, poster?: boolean, playing?: boolean, className?: string }

export default function YouTube({ url, title, load = false, poster = true, playing = false, className }: Props) {
  const frame = useRef<HTMLIFrameElement>(null)
  const id = youtubeId(url)
  const started = load                                                      // iframe 을 만들지(부모가 load 하면 썸네일 아래에 미리 만듦, 자동 재생 안 함)
  const [ready, setReady] = useState(false)                                 // 유튜브 플레이어가 명령을 받을 수 있게 됐는지(onReady)
  const waiting = playing && !ready                                         // 눌렀는데 아직 준비 전 → 썸네일 · 로딩 표시 유지, 준비되면 재생

  const command = (func: string) => frame.current?.contentWindow?.postMessage(JSON.stringify({ event: 'command', func, args: [] }), HOST)

  // 준비 신호 받기 — IFrame API 는 'listening' 을 받은 뒤부터 onReady 등 상태를 보내 줌. 받을 때까지 조금씩 다시 알림
  useEffect(() => {
    if (!started || ready) return
    const onMessage = (e: MessageEvent) => {
      if (e.origin !== HOST || e.source !== frame.current?.contentWindow) return
      try {
        const data = JSON.parse(e.data)
        if (data.event === 'onReady' || data.event === 'initialDelivery' || data.event === 'infoDelivery') setReady(true)
      } catch { /* 유튜브가 아닌 형식은 무시 */ }
    }
    addEventListener('message', onMessage)
    const listen = () => frame.current?.contentWindow?.postMessage(JSON.stringify({ event: 'listening', id: 'tingle-film', channel: 'widget' }), HOST)
    const timer = setInterval(listen, LISTEN_EVERY_MS)
    listen()
    return () => {
      removeEventListener('message', onMessage)
      clearInterval(timer)
    }
  }, [started, ready])

  // 재생 · 멈춤은 부모가 playing 으로 정함(재생 버튼 · 언제 멈출지는 부모 화면이 판단). 미리 만든 플레이어에 명령만 보냄
  // 재생한 적 없을 때(미리 만든 직후)는 일시정지 명령을 보내지 않음 — 준비 전 플레이어에 명령이 가면 유튜브 쪽 오류가 남
  const played = useRef(false)
  useEffect(() => {
    if (!started || (playing && !ready)) return                             // 준비 전이면 기다렸다가 ready 가 되면 다시 돌아 재생
    if (playing) { played.current = true; command('playVideo'); frame.current?.focus({ preventScroll: true }) }   // 누른 버튼이 사라져도 키보드 포커스는 플레이어로
    else if (played.current) {
      command('pauseVideo')
      if (document.activeElement === frame.current) frame.current?.blur()   // 썸네일에 다시 가려지므로 포커스를 빼 둠(Space 가 숨은 플레이어를 다시 재생하지 않게)
    }
  }, [started, playing, ready])

  const params = { controls: '1', playsinline: '1', rel: '0', iv_load_policy: '3', enablejsapi: '1', origin: location.origin }
  const src = id && `${HOST}/embed/${id}?${new URLSearchParams(params)}`
  const thumb = id && `https://i.ytimg.com/vi/${id}/hqdefault.jpg`             // 어느 영상에나 있는 썸네일(흐린 배경 · 큰 썸네일이 없을 때 대신)
  const toThumb = (img: HTMLImageElement) => { if (!img.src.endsWith('/hqdefault.jpg')) img.src = thumb! }   // 큰 썸네일이 없으면(오류 또는 120px 회색 대체 이미지) 작은 썸네일로

  return (
    <div className={[styles.box, className ?? ''].join(' ')}>
      {id && <img className={styles.backdrop} src={thumb!} alt='' aria-hidden />}
      {id && (!started || poster || waiting) && <img className={`${styles.frame} ${styles.poster}`} src={`https://i.ytimg.com/vi/${id}/maxresdefault.jpg`} onError={(e) => toThumb(e.currentTarget)} onLoad={(e) => { if (e.currentTarget.naturalWidth <= 120) toThumb(e.currentTarget) }} alt='' aria-hidden />}
      {waiting && <span className={styles.loading} role='status' aria-label='영상 불러오는 중' />}
      {id && started && <iframe ref={frame} className={styles.frame} src={src!} title={title} allow='autoplay; encrypted-media; picture-in-picture; fullscreen' referrerPolicy='strict-origin-when-cross-origin' tabIndex={!poster && !waiting ? 0 : -1} />}
    </div>
  )
}
