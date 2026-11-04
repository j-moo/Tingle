export type Platform = 'mac' | 'windows' | 'android' | 'ios' | 'other'

// 접속한 기기의 운영체제(브라우저 아님 — 아이폰도 사파리, Mac 에서도 크롬을 쓴다)
// iPadOS 사파리는 기본이 "데스크톱 사이트 요청"이라 Mac 으로 보임 → 터치 지원으로 구분
export function detectPlatform(nav: Pick<Navigator, 'userAgent' | 'maxTouchPoints'> = navigator): Platform {
  const ua = nav.userAgent
  if (/android/i.test(ua)) return 'android'
  if (/iphone|ipad|ipod/i.test(ua)) return 'ios'
  if (/macintosh|mac os x/i.test(ua)) return nav.maxTouchPoints > 1 ? 'ios' : 'mac'
  if (/windows/i.test(ua)) return 'windows'
  return 'other'
}
