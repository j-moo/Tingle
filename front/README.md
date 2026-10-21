# 프런트엔드

웹과 모바일 앱을 독립된 프로젝트로 관리합니다.

```text
front/
├── web/                 React + TypeScript + Vite
└── app/                 Kotlin Multiplatform + Compose Multiplatform
    ├── androidApp/      Android 실행 앱
    ├── iosApp/          iOS 실행 앱 (Xcode)
    └── shared/          Android·iOS 공통 Kotlin UI와 로직
```

## 웹

```sh
cd front/web
pnpm install --frozen-lockfile
pnpm dev
```

검증: `pnpm lint`, `pnpm build`.
웹 배포의 빌드 컨텍스트는 `front/web`, 산출물은 `front/web/dist`입니다.

## 모바일

Android Studio에서 `front/app`을 엽니다. 실행 및 개발 환경 설정은
[app/README.md](app/README.md)를 참고하세요.
