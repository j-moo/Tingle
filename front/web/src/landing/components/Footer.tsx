import Logo from './Logo.tsx'
import styles from './Footer.module.css'

// 약관 · 동의 문서는 public/ 정적 페이지. /dev/ 배포에서도 맞게 BASE_URL 기준으로 건다
const base = import.meta.env.BASE_URL
const LINKS = [
  { label: '이용약관', href: `${base}terms/` },
  { label: '개인정보 처리방침', href: `${base}privacy-policy/`, strong: true },   // privacy/ 는 수집·이용 동의 문서
  { label: 'AI 위험 감지 동의', href: `${base}ai-risk/` },
]

export default function Footer() {
  return (
    <footer className={styles.footer}>
      <div className={styles.row}>
        <Logo className={styles.logo} />
        <nav className={styles.links} aria-label='약관'>
          {LINKS.map((l) => (
            <a key={l.href} className={l.strong ? styles.strong : undefined} href={l.href}>
              {l.label}
            </a>
          ))}
        </nav>
      </div>
      <p className={styles.credit}>SSAFY 15기 자율 프로젝트 E106 · Suddenly I See</p>
    </footer>
  )
}
