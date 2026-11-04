import { useEffect, useRef, useState, type CSSProperties } from 'react'
import styles from './LetsDoIt.module.css'

// "Let's Do it!!" — 화면에 고정된 채 세로 스크롤만큼 3단계가 옆으로 지나가고, 다 보면 고정이 풀려 아래로 내려간다
// 섹션 높이 = (단계 수 × --hold + 1) 화면(LetsDoIt.module.css), 안쪽은 sticky. 스크롤 위치로 현재 단계를 정해 --i 로 넘기면 CSS 가 탄성 곡선으로 줄을 옮긴다
// 패널마다 멈춤: 입력은 건드리지 않고, 각 패널이 --hold 화면만큼 스크롤하는 동안 제자리에 머물다 경계를 넘는 순간 슉 넘어감
// 동작 줄이기 · 낮은 화면이면 고정 없이 세로로 나열(CSS)
export const order = 20

type Step = { step: string, title: string, body: string, image?: string }
const STEPS: Step[] = [
  { step: 'STEP 1', title: '키링과 함께 일상으로', body: '키링을 등록하고 평소처럼 지니고 다녀요.' },
  { step: 'STEP 2', title: '스치면 조용히 알려요', body: '잘 맞는 사람과 가까워지면 키링이 짧게 알려 줘요.' },
  { step: 'STEP 3', title: '서로 수락하면 대화', body: '앱에서 제안을 확인하고 두 사람 모두 원할 때 대화가 열려요.' },
]
const LAST = STEPS.length - 1

export default function LetsDoIt() {
  const section = useRef<HTMLElement>(null)
  const [active, setActive] = useState(0)

  useEffect(() => {
    const el = section.current
    if (!el) return
    let frame = 0
    const update = () => {
      frame = 0
      const r = el.getBoundingClientRect()
      const gap = parseFloat(getComputedStyle(el).paddingTop)               // 위 여백(--rise-gap)은 지나간 뒤부터 고정
      const travel = r.height - gap - innerHeight                          // 고정된 채 스크롤되는 거리
      const progress = travel > 0 ? Math.min(1, Math.max(0, (-r.top - gap) / travel)) : 0
      setActive(Math.min(LAST, Math.floor(progress * STEPS.length)))       // 고정 구간을 단계 수로 똑같이 나눠 각 패널이 --hold 화면씩 머묾
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

  // 진행 막대를 누르면 그 단계 구간의 가운데로 부드럽게 스크롤(시작 지점으로 가면 앞으로 갈 때 감속 끝에야 경계를 넘어 늦게 바뀜)
  const goTo = (i: number) => {
    const el = section.current
    if (!el) return
    const r = el.getBoundingClientRect()
    const gap = parseFloat(getComputedStyle(el).paddingTop)
    const travel = r.height - gap - innerHeight
    scrollTo({ top: r.top + scrollY + gap + travel * ((i + 0.5) / STEPS.length) })
  }

  return (
    <section id='how-it-works' ref={section} className={styles.section} style={{ '--steps': STEPS.length, '--i': active } as CSSProperties} aria-labelledby='how-it-works-title'>
      <div className={styles.sticky}>
        <h2 id='how-it-works-title' className={styles.label}>Let's Do it!!</h2>
        <ol className={styles.track}>
          {STEPS.map((s, i) => (
            <li key={s.step} className={styles.panel} aria-current={i === active ? 'step' : undefined}>
              {s.image ? <img className={styles.visual} src={s.image} alt='' /> : <div className={styles.visual} aria-hidden />}
              <div className={styles.text}>
                <p className={styles.step}>{s.step}</p>
                <h3 className={styles.title}>{s.title}</h3>
                <p className={styles.body}>{s.body}</p>
              </div>
            </li>
          ))}
        </ol>
        <div className={styles.bars}>
          {STEPS.map((s, i) => (
            <button key={s.step} type='button' data-label={s.step} className={i === active ? styles.on : undefined} aria-label={`${s.step} 보기`} aria-current={i === active ? 'step' : undefined} onClick={() => goTo(i)} />
          ))}
        </div>
      </div>
    </section>
  )
}
