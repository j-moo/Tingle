const steps = [
  {
    title: '키링과 함께 일상으로',
    body: '키링을 등록하고 평소처럼 지니고 다녀요.',
    icon: <path d="M12 12h0M8.5 8.5a5 5 0 0 0 0 7M15.5 8.5a5 5 0 0 1 0 7M5.6 5.6a9 9 0 0 0 0 12.8M18.4 5.6a9 9 0 0 1 0 12.8" />,
  },
  {
    title: '스치면 조용히 알려요',
    body: '잘 맞는 사람과 가까워지면 키링이 짧게 알려 줘요.',
    icon: <path d="M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z" />,
  },
  {
    title: '서로 수락하면 대화',
    body: '앱에서 제안을 확인하고 두 사람 모두 원할 때 대화가 열려요.',
    icon: <path d="M12 4a8 8 0 0 0-6.9 12L4 20l4.1-1.1A8 8 0 1 0 12 4z" />,
  },
]

export default function App() {
  return (
    <main className="page">
      <header className="top">
        <span className="wordmark">Tingle.</span>
        <span className="top__ko">팅글</span>
      </header>

      <section className="hero">
        <p className="eyebrow">A LITTLE TINGLE</p>
        <h1>
          스친 순간,
          <br />
          새로운 시작.
        </h1>
        <p>스쳐 간 인연을 확인해 보세요.</p>
      </section>

      <ul className="steps">
        {steps.map((s) => (
          <li className="step" key={s.title}>
            <span className="step__icon" aria-hidden>
              <svg viewBox="0 0 24 24">{s.icon}</svg>
            </span>
            <div>
              <strong>{s.title}</strong>
              <span>{s.body}</span>
            </div>
          </li>
        ))}
      </ul>

      <section className="notice" aria-label="서비스 안내">
        <p className="notice__label">곧 만나요</p>
        <h2>
          Tingle 앱을
          <br />
          준비하고 있어요.
        </h2>
        <p className="notice__label">Android 앱을 먼저 선보일 예정이에요.</p>
        <span className="notice__button" aria-disabled="true">
          Android 앱 준비 중
        </span>
      </section>

      <footer className="footer">SSAFY 15기 자율 프로젝트 E106 · Suddenly I See</footer>
    </main>
  )
}
