// S15P31E106 CI — GitLab MR·push 마다 실행 (Jenkins job: tingle, 설정 infra/jenkins/casc/jenkins.yaml)
// 바뀐 파트만 검사한다. 도구는 Jenkins 에 깔지 않고 파트마다 docker 이미지로 돌린다 (작업 폴더는 --volumes-from 으로 공유).
// MR 은 검사로 끝나고 결과가 GitLab MR 에 파트별로 표시된다. develop · master 에 push(머지)되면 검사 뒤 각 환경에 배포한다 (인프라 계획 6절).

def PARTS = [back: 'back/', ai: 'ai-server/', web: 'front/web/', app: 'front/app/', iot: 'iot/']
// AI 테스트가 읽는 참조 원본이 바뀌어도 회귀 검사를 실행한다.
def AI_TEST_INPUTS = ['ai-server/reference/survey-20261021/mockup/dist/', 'ai-server/reference/survey-20261021/questionnaire-SIS-Q-1.1-100.json']
// 브랜치별 배포: 코드를 dir 에 풀고 services(infra/docker-compose.yml 서비스 이름 → 응답 확인 주소)만 다시 띄운다.
// bluegreen 은 infra/deploy-bluegreen.sh 로 무중단 전환한다 (운영 back · ai). ai 를 먼저 바꾼다 — 새 ai 가 옛 back 요청도 받아야 한다 (인프라 계획 7절).
// back 확인은 Actuator — DB 연결까지 정상이면 200, 아니면 503. ai 는 /health.
def DEPLOY = [
  develop: [dir: '/srv/tingle/dev', services: ['web-dev': 'http://web-dev/', 'back-dev': 'http://back-dev:8080/actuator/health', 'ai-dev': 'http://ai-dev:8000/health']],
  master: [dir: '/srv/tingle/repo', services: ['web': 'http://web/'], bluegreen: ['ai', 'back'], backup: true],
]
def changed = []

// image 컨테이너에서 dir 을 기준으로 script 를 실행한다. 컨테이너는 root 로 돌기 때문에 끝나면 작업 폴더 소유권을 Jenkins 사용자에게 돌려준다.
// 스크립트는 환경변수로 넘긴다 (stdin 으로 넘기면 apt 처럼 입력을 읽는 명령이 남은 스크립트를 먹는다).
// 캐시(gradle·pnpm·pip·Android SDK)는 tingle-ci-* 이름의 docker 볼륨에 남는다. gradle 캐시는 파트마다 따로 둔다 (컨테이너끼리는 잠금이 안 통한다).
def inDocker(String image, String dir, String opts, String script) {
  withEnv(["CI_SCRIPT=${script}"]) {
    sh """docker run --rm --volumes-from "\$(hostname)" -w "\$WORKSPACE/${dir}" -e OWNER="\$(id -u):\$(id -g)" -e CI_SCRIPT ${opts} ${image} \\
      sh -euc 'trap "chown -R \$OWNER ." EXIT; eval "\$CI_SCRIPT"'"""
  }
}

pipeline {
  agent any
  options {
    gitLabConnection('lab.ssafy.com')
    timestamps()
    disableConcurrentBuilds()
    buildDiscarder(logRotator(numToKeepStr: '30'))
  }
  stages {
    stage('changes') {
      steps {
        updateGitlabCommitStatus name: 'jenkins', state: 'running'
        script {
          // MR 은 대상 브랜치와, push 는 직전 커밋과 비교한다. 새 브랜치 push 처럼 직전 커밋이 없으면 develop 과 비교한다.
          def files = sh(returnStdout: true, script: '''
            if [ "${gitlabActionType:-}" = MERGE ]; then base="origin/$gitlabTargetBranch"
            elif git cat-file -e "${gitlabBefore:-0}^{commit}" 2>/dev/null; then base="$gitlabBefore"
            else base=origin/develop; fi
            echo "event=${gitlabActionType:-manual} source=${gitlabSourceBranch:-?} base=$base" >&2
            git diff --name-only "$base...HEAD"
          ''').trim().readLines()
          // Jenkinsfile 이 바뀌면 검사 방법이 바뀐 것이므로 전부 돌린다.
          changed = files.contains('Jenkinsfile') ? PARTS.keySet() as List : PARTS.findAll { k, dir -> files.any { it.startsWith(dir) } }.keySet() as List
          if (!('ai' in changed) && files.any { file -> AI_TEST_INPUTS.any { path -> path.endsWith('/') ? file.startsWith(path) : file == path } }) {
            changed.add('ai')
          }
          echo "검사할 파트: ${changed ?: '없음'}"
        }
      }
    }
    stage('checks') {
      parallel {
        stage('back') {
          when { expression { 'back' in changed } }
          steps {
            gitlabCommitStatus(name: 'back') {
              // build = 컴파일 + checkstyle(네이버 규칙) + 테스트. 테스트 DB 는 Testcontainers 가 서버 docker 로 띄운다.
              // ufw 가 컨테이너 → 호스트 포트를 막으므로 호스트 네트워크로 돌리고 Testcontainers 가 localhost 로 붙게 한다.
              // 대량 데이터 부하 테스트(@Tag("load"))는 MR마다 돌리지 않고, develop·master 머지 중 back 이 바뀐 때만 함께 돌린다.
              script {
                def tasks = env.gitlabActionType == 'PUSH' && DEPLOY[env.gitlabSourceBranch] ? 'build loadTest' : 'build'
                inDocker('eclipse-temurin:21-jdk', 'back', '-v tingle-ci-gradle-back:/root/.gradle --network host -e TESTCONTAINERS_HOST_OVERRIDE=localhost', "sh gradlew ${tasks} --no-daemon")
              }
            }
          }
        }
        stage('ai') {
          when { expression { 'ai' in changed } }
          steps {
            gitlabCommitStatus(name: 'ai') {
              // pytest, tests/node 회귀, JS/Python parity를 공통 실행기로 검사한다. 기존 Node 설치를 유지한다.
              inDocker('python:3.12-slim', 'ai-server', '-v tingle-ci-pip:/root/.cache/pip', '''
apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends nodejs >/dev/null
pip install -q --root-user-action=ignore -r requirements.txt -r app/risk_detection/requirements-dev.txt
python scripts/run_tests.py
''')
            }
          }
        }
        stage('web') {
          when { expression { 'web' in changed } }
          steps {
            gitlabCommitStatus(name: 'web') {
              inDocker('node:24-alpine', 'front/web', '-v tingle-ci-pnpm:/root/.local/share/pnpm', '''
# 저장소 위치를 안 정하면 pnpm 이 마운트 최상위(/var/jenkins_home/.pnpm-store)에 root 소유로 만든다
npm install -g --silent pnpm@11
pnpm install --frozen-lockfile --store-dir /root/.local/share/pnpm/store
pnpm lint
pnpm build
''')
            }
          }
        }
        stage('app') {
          when { expression { 'app' in changed } }
          steps {
            gitlabCommitStatus(name: 'app') {
              // Android SDK 는 처음 한 번 볼륨에 받고, 필요한 플랫폼·빌드 도구는 AGP 가 알아서 내려받는다.
              // 메모리: gradle.properties 기본값(4G + Kotlin 데몬 3G)은 서버에 크므로 Kotlin 을 gradle 안에서 돌리고 3G 로 줄인다.
              // develop · master 머지는 공개할 APK 를 팀 서명 키(Credentials tingle-release-key · tingle-release-password)로 찍는다 — master 는 release, develop 은 debug.
              // MR builds use the local Android debug signing key.
              script {
                def apkDir = [develop: '/srv/tingle/app/dev', master: '/srv/tingle/app'][env.gitlabSourceBranch]
                def publish = env.gitlabActionType == 'PUSH' && apkDir
                def release = publish && env.gitlabSourceBranch == 'master'
                def build = {
                  inDocker('eclipse-temurin:21-jdk', 'front/app', '-v tingle-ci-gradle-app:/root/.gradle -v tingle-ci-android:/opt/android-sdk -e ANDROID_HOME=/opt/android-sdk -e APK_TASK -e TINGLE_KEYSTORE -e TINGLE_KEYSTORE_PASSWORD -e GOOGLE_WEB_CLIENT_ID -e KAKAO_NATIVE_APP_KEY -e KAKAO_NATIVE_APP_KEY_DEV -e NAVER_CLIENT_ID', '''
if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq unzip >/dev/null
  curl -fsSLo /tmp/tools.zip https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
  unzip -q /tmp/tools.zip -d /tmp/tools
  mkdir -p "$ANDROID_HOME/cmdline-tools" && mv /tmp/tools/cmdline-tools "$ANDROID_HOME/cmdline-tools/latest"
fi
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null || true
sh gradlew check :androidApp:$APK_TASK --no-daemon -Dorg.gradle.jvmargs=-Xmx3g -Pkotlin.compiler.execution.strategy=in-process
''')
                }
                if (publish) {
                  withEnv(["APK_TASK=${release ? 'assembleRelease' : 'assembleDebug'}"]) {
                    // 소셜 로그인 키는 tingle-app-env 에서 앱이 쓰는 4개만 골라 넘긴다(네이버 비밀값은 APK 에 넣지 않는다 — OIDC PKCE) (DB · JWT 같은 서버 비밀값은 빌드에 넣지 않는다). build.gradle.kts 가 환경변수로 읽는다.
                    withCredentials([file(credentialsId: 'tingle-release-key', variable: 'TINGLE_KEYSTORE'), string(credentialsId: 'tingle-release-password', variable: 'TINGLE_KEYSTORE_PASSWORD'), file(credentialsId: 'tingle-app-env', variable: 'APP_ENV')]) {
                      def social = readFile(env.APP_ENV).readLines().findAll { it ==~ /(GOOGLE_WEB_CLIENT_ID|KAKAO_NATIVE_APP_KEY|KAKAO_NATIVE_APP_KEY_DEV|NAVER_CLIENT_ID)=.+/ }
                      withEnv(social) { build() }
                    }
                  }
                } else {
                  withEnv(['APK_TASK=assembleDebug']) { build() }
                }
                // 공개: develop → https://도메인/dev/app/latest.apk, master → /app/latest.apk (nginx /dev/app/ → /srv/tingle/app/dev, /app/ → /srv/tingle/app). 앱이 바뀐 커밋에서만 갱신된다.
                def apk = release ? 'release/androidApp-release.apk' : 'debug/androidApp-debug.apk'
                if (publish) {
                  sh """
                    mkdir -p ${apkDir}
                    cp front/app/androidApp/build/outputs/apk/${apk} ${apkDir}/latest.apk.tmp
                    mv ${apkDir}/latest.apk.tmp ${apkDir}/latest.apk
                    echo "APK 공개 (${env.gitlabSourceBranch}, ${release ? 'release' : 'debug'}): \$(git rev-parse --short HEAD) (\$(du -h ${apkDir}/latest.apk | cut -f1))"
                  """
                }
              }
            }
          }
        }
        stage('iot') {
          when { expression { 'iot' in changed } }
          steps {
            gitlabCommitStatus(name: 'iot') {
              // 펌웨어 코드가 생기면 arduino-cli compile (보드 seeed_xiao_nrf52840) 로 바꾼다.
              sh 'find iot -name "*.ino" | grep -q . && echo "iot: 펌웨어 검사 단계가 아직 없음 — Jenkinsfile 에 arduino-cli 를 추가해야 함" || echo "iot: 검사할 펌웨어 코드 없음"'
            }
          }
        }
      }
    }
    stage('app-env') {
      // 배포 브랜치 push 때만: Jenkins Credentials tingle-app-env(Secret file, app.env 전체)를 서버 /srv/tingle/app.env 로 쓴다 (compose 가 --env-file 로 읽는다).
      // 값을 바꾸거나 더할 때는 파일을 고쳐 Credentials 에 다시 올린다 (공유 사본: 자율 프로젝트/infra/app.env). 값에 $ 가 있으면 compose 가 변수로 읽으니 $$ 로 넣는다.
      when { expression { env.gitlabActionType == 'PUSH' && DEPLOY[env.gitlabSourceBranch] } }
      steps {
        withCredentials([file(credentialsId: 'tingle-app-env', variable: 'APP_ENV')]) {
          sh '''
            set +x
            umask 077
            { cat "$APP_ENV"; echo; } | tr -d '\\r' > /srv/tingle/app.env.tmp # 끝 줄바꿈 보장(FCM 값을 뒤에 덧붙인다) · 윈도우 줄끝 제거
            mv /srv/tingle/app.env.tmp /srv/tingle/app.env
            mkdir -p /srv/tingle/secrets # back 이 읽기 전용으로 마운트한다. 없으면 docker 가 root 소유로 만들어 Jenkins 가 못 쓴다
            echo "app.env 갱신: $(grep -c = /srv/tingle/app.env)개 값"
          '''
        }
        // 푸시(FCM, -141): Credentials 에 tingle-fcm(Secret file, Firebase 서비스 계정 JSON)이 있으면 키 파일을 쓰고 푸시를 켠다.
        // 없으면 꺼진 채로 둔다 (back 은 PUSH_ENABLED=false 면 키를 읽지 않는다). 프로젝트 ID 는 JSON 의 project_id 를 쓴다.
        script {
          try {
            withCredentials([file(credentialsId: 'tingle-fcm', variable: 'FCM_JSON')]) {
              sh '''
                set +x
                umask 077
                cp "$FCM_JSON" /srv/tingle/secrets/fcm.json.tmp
                mv /srv/tingle/secrets/fcm.json.tmp /srv/tingle/secrets/fcm.json
                project=$(sed -n 's/.*"project_id": *"\\([^"]*\\)".*/\\1/p' /srv/tingle/secrets/fcm.json)
                printf 'PUSH_ENABLED=true\\nFIREBASE_PROJECT_ID=%s\\n' "$project" >> /srv/tingle/app.env
                echo "FCM 키 반영 (Firebase 프로젝트 $project) — 푸시 켬"
              '''
            }
          } catch (e) {
            if (!(e.message ?: '').contains('tingle-fcm')) { throw e }
            echo 'FCM 키 없음 (Credentials tingle-fcm) — 푸시 꺼짐'
          }
        }
      }
    }
    stage('deploy') {
      // 이 커밋으로 해당 환경 서비스만 다시 빌드해 띄운다. 그 밖의 서비스와 nginx · db · jenkins 는 건드리지 않는다 (--no-deps, 인프라는 수동 배포).
      // 서버와 Jenkins 가 /srv/tingle 을 같은 경로로 보므로 compose 의 상대 경로가 그대로 맞는다. 운영(master)은 먼저 DB 를 /backup 에 백업한다.
      when { expression { env.gitlabActionType == 'PUSH' && DEPLOY[env.gitlabSourceBranch] } }
      steps {
        script {
          def target = DEPLOY[env.gitlabSourceBranch]
          gitlabCommitStatus(name: "deploy-${env.gitlabSourceBranch}") {
            def devWithdrawalCompose = '/srv/tingle/dev-runtime/withdrawal.compose.yml'
            def composeOverride = ''
            if (env.gitlabSourceBranch == 'develop') {
              // 개발 백엔드에만 서버에 보관된 기존 탈퇴 설정과 서명 키를 전달한다.
              sh "test -r '${devWithdrawalCompose}' || { echo '개발 탈퇴 Compose 설정을 읽을 수 없습니다'; exit 1; }"
              composeOverride = "-f '${devWithdrawalCompose}'"
            }
            if (target.backup) {
              // 두 DB 백업 계열 모두 최대 7일. 복원 후 재삭제 검증 전에는 서비스 개방 금지.
              sh '''
                umask 077
                docker run --rm --volumes-from "$(hostname)" -w "$WORKSPACE" python:3.12-slim python infra/backup-retention.py --directory /backup --apply
                f=/backup/db-tingle-$(date -u +%Y%m%d-%H%M%S)-$(git rev-parse --short HEAD).dump
                docker exec tingle-db-1 sh -c 'pg_dump -U "$POSTGRES_USER" -Fc tingle' > "$f.tmp"
                mv "$f.tmp" "$f"
                echo "DB 백업: $f ($(du -h "$f" | cut -f1))"
              '''
            }
            def compose = "docker compose --env-file /srv/tingle/.env --env-file /srv/tingle/app.env -f ${target.dir}/infra/docker-compose.yml ${composeOverride}"
            sh """
              # 폴더를 통째로 바꾸지 않고 안의 파일만 바꾼다. 폴더째 바꾸면 그 안을 마운트한 nginx · grafana 가 지워진 옛 폴더를 계속 본다 (2026-10-27).
              # 커밋에 없는 파일(저장소에서 지운 것)은 지운다. 한글 경로가 따옴표로 바뀌지 않게 core.quotePath=false.
              mkdir -p ${target.dir}
              git archive HEAD | tar x -C ${target.dir}
              git -c core.quotePath=false ls-tree -r --name-only HEAD | sort > \$WORKSPACE/.deploy-files
              (cd ${target.dir} && find . -type f ! -name .deployed-commit | sed 's#^\\./##' | sort | comm -23 - \$WORKSPACE/.deploy-files | xargs -r -d '\\n' rm -f --)
              git rev-parse --short HEAD > ${target.dir}/.deployed-commit
              ${compose} up -d --build --no-deps ${target.services.keySet().join(' ')}
              docker image prune -f
            """
            // 떠서 응답하는지 확인 (Jenkins 는 compose 내부망에 있어 서비스 이름으로 부른다). 실패하면 원인 확인용으로 로그 끝부분만 남긴다 (환경변수는 비밀값이라 찍지 않는다)
            target.services.each { name, url ->
              sh "for i in \$(seq 1 60); do curl -fsS -o /dev/null ${url} && exit 0; sleep 2; done; echo '${name} 응답 없음 — 로그 마지막 100줄:'; ${compose} logs --no-color --tail 100 ${name} || true; exit 1"
            }
            // 무중단 전환 대상: 새 색 기동 → 상태 확인 → nginx 전환 → 옛 색 정지 (실패하면 운영은 옛 색 그대로)
            target.bluegreen?.each { name ->
              sh "sh ${target.dir}/infra/deploy-bluegreen.sh ${name}"
            }
          }
        }
      }
    }
  }
  post {
    success { updateGitlabCommitStatus name: 'jenkins', state: 'success' }
    failure { updateGitlabCommitStatus name: 'jenkins', state: 'failed' }
    aborted { updateGitlabCommitStatus name: 'jenkins', state: 'canceled' }
  }
}
