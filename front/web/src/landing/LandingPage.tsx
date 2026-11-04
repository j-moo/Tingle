import { useEffect, type ComponentType } from 'react'
import Footer from './components/Footer.tsx'
import Nav from './components/Nav.tsx'
import styles from './landing.module.css'
import { blockTouchScroll } from './sectionNav.ts'

// 섹션은 sections/<이름>/index.tsx 하나씩. 여기를 고치지 않고 폴더만 추가하면 order 순서대로 붙는다
// (섹션 MR 끼리 같은 파일을 건드리지 않게 — 인트로 10 · 이용 방법 20 · 브랜드 필름 30 · 일상 영상 40 · 다운로드 50)
type Section = { default: ComponentType, order: number }
const sections = Object.entries(import.meta.glob<Section>('./sections/*/index.tsx', { eager: true }))
  .map(([path, mod]) => ({ key: path, ...mod }))
  .sort((a, b) => a.order - b.order)

export default function LandingPage() {
  useEffect(blockTouchScroll, [])                                            // 세로 화면: 손가락 끌기 스크롤 막음(누르기로 이동, sectionNav.ts)

  return (
    <>
      <Nav />
      <main id='top' className={styles.main}>
        {sections.map(({ key, default: Section }) => (
          <Section key={key} />
        ))}
      </main>
      <Footer />
    </>
  )
}
