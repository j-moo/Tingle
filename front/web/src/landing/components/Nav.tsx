import { useEffect, useRef, useState } from 'react'
import Logo from './Logo.tsx'
import { markJump } from '../sectionNav.ts'
import styles from './Nav.module.css'

// 투명 유리(흐림) 고정 네비. 인트로 재생 중(<html data-intro="playing">)에는 위로 숨었다가 끝나면 내려온다(Nav.module.css)
// 네비 아래가 어두운 섹션(data-nav="dark")이면 로고 · 메뉴 · 버튼을 흰색으로(data-tone="dark")
// 키링 섹션은 아직 없어서 맨 위로 보낸다
const ITEMS = [
  { label: '브랜드 필름', href: '#brand-film' },
  { label: '키링', href: '#top' },
]
const DOWNLOAD = { label: '어플 다운로드', href: '#download' }

export default function Nav() {
  const [open, setOpen] = useState(false)
  const [dark, setDark] = useState(false)
  const header = useRef<HTMLElement>(null)
  const close = () => setOpen(false)

  useEffect(() => {
    let frame = 0
    const update = () => {
      frame = 0
      const h = header.current
      if (!h) return
      const y = h.querySelector('div')!.getBoundingClientRect().height / 2      // 네비 줄 가운데 높이에서 바로 아래 깔린 요소
      const under = document.elementsFromPoint(innerWidth / 2, y).find((el) => !h.contains(el))
      setDark(!!under?.closest('[data-nav="dark"]'))
    }
    const onScroll = () => { if (!frame) frame = requestAnimationFrame(update) }
    update()
    addEventListener('scroll', onScroll, { passive: true })
    addEventListener('resize', onScroll)
    return () => {
      removeEventListener('scroll', onScroll)
      removeEventListener('resize', onScroll)
      cancelAnimationFrame(frame)
    }
  }, [])

  return (
    <header ref={header} onClickCapture={(e) => { if ((e.target as Element).closest('a[href^="#"]')) markJump() }} className={styles.nav} data-tone={dark ? 'dark' : 'light'} data-open={open || undefined}>
      <div className={styles.bar}>
        <a className={styles.home} href='#top' aria-label='tingle 맨 위로' onClick={close}>
          <Logo className={styles.logo} />
        </a>
        <nav className={styles.menu} aria-label='랜딩 메뉴'>
          {ITEMS.map((item) => (
            <a key={item.label} className={styles.item} href={item.href}>
              {item.label}
            </a>
          ))}
          <a className={styles.cta} href={DOWNLOAD.href}>
            {DOWNLOAD.label}
          </a>
        </nav>
        <div className={styles.mobile}>
          <a className={styles.cta} href={DOWNLOAD.href} onClick={close}>
            앱
          </a>
          <button className={styles.burger} type='button' aria-label='메뉴' aria-expanded={open} aria-controls='landing-menu' onClick={() => setOpen((v) => !v)}>
            <svg viewBox='0 0 24 24' aria-hidden>
              <path d={open ? 'M6 6l12 12M18 6L6 18' : 'M4 7h16M4 12h16M4 17h16'} />
            </svg>
          </button>
        </div>
      </div>
      {open && (
        <nav id='landing-menu' className={styles.sheet} aria-label='랜딩 메뉴'>
          {[...ITEMS, DOWNLOAD].map((item) => (
            <a key={item.label} href={item.href} onClick={close}>
              {item.label}
            </a>
          ))}
        </nav>
      )}
    </header>
  )
}
