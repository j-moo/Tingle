import { useState } from 'react'
import Logo from './Logo.tsx'
import styles from './Nav.module.css'

// 흰 반투명 고정 네비. 인트로 재생 중(<html data-intro="playing">)에는 위로 숨었다가 끝나면 내려온다(Nav.module.css)
// 키링 섹션은 아직 없어서 맨 위로 보낸다
const ITEMS = [
  { label: '브랜드 필름', href: '#brand-film' },
  { label: '키링', href: '#top' },
]
const DOWNLOAD = { label: '어플 다운로드', href: '#download' }

export default function Nav() {
  const [open, setOpen] = useState(false)
  const close = () => setOpen(false)

  return (
    <header className={styles.nav}>
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
