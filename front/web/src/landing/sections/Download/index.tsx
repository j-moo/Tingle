import { useEffect, useState, type CSSProperties, type MouseEvent } from 'react'
import { goSection, sideOf, TAP_MODE } from '../../sectionNav.ts'
import { detectPlatform } from './detectPlatform.ts'
import styles from './Download.module.css'

// 앱 다운로드: 컴퓨터 = QR + 버튼 둘([Android | Mac] 전환 · [설치하기])(누르면 전환, 기본값은 접속 OS), 폰 · 태블릿(Android · iOS) = 설치하기 하나
// 폰 · 태블릿은 폭과 상관없이 OS 로 판단(자기 화면의 QR 은 찍을 수 없음). 좁은 컴퓨터 창은 QR 화면을 세로로 쌓음
// 설치 링크 · QR 은 아직 없음 → 비워 두면 "준비 중"으로 보이고 눌러도 이동하지 않는다(가짜 링크 넣지 않음)
export const order = 50

type Target = 'android' | 'mac'
const APPS: Record<Target, { label: string, href: string, qr: string }> = {
  android: { label: 'Android', href: '', qr: '' },          // qr = 설치 링크 QR 이미지 경로(public 기준이면 BASE_URL 붙여서)
  mac: { label: 'Mac', href: '', qr: '' },
}

export default function Download() {
  const [footerH, setFooterH] = useState(0)                                  // 세로 화면: 다운로드 + 푸터가 딱 한 화면이 되게(Download.module.css)
  useEffect(() => {
    const footer = document.querySelector('footer')
    if (!footer) return
    const ro = new ResizeObserver(() => setFooterH(footer.offsetHeight))
    ro.observe(footer)
    return () => ro.disconnect()
  }, [])
  // 세로 화면: 버튼 · 링크 밖 왼쪽 절반을 누르면 이전 섹션(일상 영상)으로(마지막 섹션이라 오른쪽은 없음)
  const onSectionClick = (e: MouseEvent) => {
    if (!matchMedia(TAP_MODE).matches || (e.target as Element).closest('button, a') || sideOf(e.clientX) > 0) return
    goSection(e.currentTarget, -1)
  }
  const os = detectPlatform()
  const [target, setTarget] = useState<Target>(os === 'mac' ? 'mac' : 'android')
  const app = APPS[target]
  const handheld = os === 'android' || os === 'ios'
  const mobileHref = os === 'android' ? APPS.android.href : ''      // 폰은 접속 기기 = 설치 플랫폼(아이폰 앱은 없음)

  return (
    <section id='download' className={styles.download} style={{ '--footer-h': `${footerH}px` } as CSSProperties} data-nav='dark' data-device={handheld ? 'handheld' : 'computer'} aria-labelledby='download-title' onClick={onSectionClick}>
      <div className={styles.row}>
        <div className={styles.copy}>
          <p className={styles.eyebrow}>곧 만나요</p>
          <h2 id='download-title' className={styles.title}>Tingle 앱을 준비하고 있어요</h2>
          <p className={styles.body} aria-live='polite'>{app.label} 버전이에요!</p>
        </div>

        <div className={styles.desktop}>
          <div className={styles.qr}>
            {app.qr ? <img src={app.qr} alt={`${app.label} 설치 QR 코드`} /> : <span>QR 준비 중</span>}
          </div>
          <div className={styles.toggle}>
            <button className={styles.platforms} type='button' onClick={() => setTarget((t) => (t === 'android' ? 'mac' : 'android'))} aria-label={`${app.label} 선택됨, 누르면 ${target === 'android' ? 'Mac' : 'Android'} 으로 바꾸기`}>
              {(['android', 'mac'] as const).map((t) => (
                <span key={t} className={t === target ? styles.on : styles.off}>{APPS[t].label}</span>
              ))}
            </button>
            {app.href ? <a className={styles.install} href={app.href}>설치하기</a> : <a className={styles.install} role='link' aria-disabled='true'>설치하기 · 준비 중</a>}
          </div>
        </div>

        <div className={styles.mobile}>
          {mobileHref ? (
            <a className={styles.mobileButton} href={mobileHref}>설치하기</a>
          ) : (
            <a className={styles.mobileButton} role='link' aria-disabled='true'>설치하기 · 준비 중</a>
          )}
        </div>
      </div>
    </section>
  )
}
