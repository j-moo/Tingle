// S15P31E106 CI — GitLab MR·push 마다 실행 (Jenkins job: tingle, 설정 infra/jenkins/casc/jenkins.yaml)
// 바뀐 파트만 검사한다. 도구는 Jenkins 에 깔지 않고 파트마다 docker 이미지로 돌린다 (작업 폴더는 --volumes-from 으로 공유).
// MR 은 검사로 끝나고 결과가 GitLab MR 에 파트별로 표시된다. develop 배포 단계는 다음 MR 에서 추가한다 (인프라 계획 6절).

def PARTS = [back: 'back/', ai: 'ai-server/', web: 'front/web/', app: 'front/app/', iot: 'iot/']
// develop 머지 때 다시 띄우는 dev 서비스 (infra/docker-compose.yml). back-dev · ai-dev 가 생기면 여기에 더한다.
def DEV_SERVICES = 'web-dev'
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
              inDocker('eclipse-temurin:21-jdk', 'back', '-v tingle-ci-gradle-back:/root/.gradle --network host -e TESTCONTAINERS_HOST_OVERRIDE=localhost', 'sh gradlew build --no-daemon')
            }
          }
        }
        stage('ai') {
          when { expression { 'ai' in changed } }
          steps {
            gitlabCommitStatus(name: 'ai') {
              inDocker('python:3.12-slim', 'ai-server', '-v tingle-ci-pip:/root/.cache/pip', '''
pip install -q --root-user-action=ignore -r requirements.txt
python -m pytest -q
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
              inDocker('eclipse-temurin:21-jdk', 'front/app', '-v tingle-ci-gradle-app:/root/.gradle -v tingle-ci-android:/opt/android-sdk -e ANDROID_HOME=/opt/android-sdk', '''
if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq unzip >/dev/null
  curl -fsSLo /tmp/tools.zip https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
  unzip -q /tmp/tools.zip -d /tmp/tools
  mkdir -p "$ANDROID_HOME/cmdline-tools" && mv /tmp/tools/cmdline-tools "$ANDROID_HOME/cmdline-tools/latest"
fi
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null || true
sh gradlew check :androidApp:assembleDebug --no-daemon -Dorg.gradle.jvmargs=-Xmx3g -Pkotlin.compiler.execution.strategy=in-process
''')
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
      // develop push 때만: Jenkins Credentials 의 앱 비밀값을 서버 /srv/tingle/app.env 로 쓴다 (compose 가 --env-file 로 읽는다).
      // 비밀값을 추가하면 Credentials 에 넣고 여기 withCredentials 와 echo 줄을 늘린다. 값에 $ 가 있으면 compose 가 변수로 읽으니 $$ 로 넣는다.
      when { expression { env.gitlabActionType == 'PUSH' && env.gitlabSourceBranch == 'develop' } }
      steps {
        withCredentials([usernamePassword(credentialsId: 'tingle-db', usernameVariable: 'POSTGRES_USER', passwordVariable: 'POSTGRES_PASSWORD')]) {
          sh '''
            set +x
            umask 077
            {
              echo "# Jenkins 가 관리 — 직접 수정 금지. develop 빌드마다 Jenkins Credentials 로 다시 쓴다 (Jenkinsfile app-env 단계)"
              echo "POSTGRES_USER=$POSTGRES_USER"
              echo "POSTGRES_PASSWORD=$POSTGRES_PASSWORD"
            } > /srv/tingle/app.env.tmp
            mv /srv/tingle/app.env.tmp /srv/tingle/app.env
            echo "app.env 갱신: $(grep -c = /srv/tingle/app.env)개 값"
          '''
        }
      }
    }
    stage('deploy-dev') {
      // develop push 때만: dev 서비스를 이 커밋으로 다시 빌드해 띄운다. 운영 서비스와 nginx · db · jenkins 는 건드리지 않는다 (--no-deps).
      // 코드는 /srv/tingle/dev 에 풀어 둔다 (운영 배포본 /srv/tingle/repo 와 분리). 서버와 Jenkins 가 /srv/tingle 을 같은 경로로 보므로 compose 의 상대 경로가 그대로 맞는다.
      when { expression { env.gitlabActionType == 'PUSH' && env.gitlabSourceBranch == 'develop' } }
      steps {
        gitlabCommitStatus(name: 'deploy-dev') {
          sh """
            rm -rf /srv/tingle/dev.new && mkdir /srv/tingle/dev.new
            git archive HEAD | tar x -C /srv/tingle/dev.new
            git rev-parse --short HEAD > /srv/tingle/dev.new/.deployed-commit
            rm -rf /srv/tingle/dev.old
            if [ -d /srv/tingle/dev ]; then mv /srv/tingle/dev /srv/tingle/dev.old; fi
            mv /srv/tingle/dev.new /srv/tingle/dev && rm -rf /srv/tingle/dev.old
            docker compose --env-file /srv/tingle/.env --env-file /srv/tingle/app.env -f /srv/tingle/dev/infra/docker-compose.yml up -d --build --no-deps ${DEV_SERVICES}
            docker image prune -f
          """
          // 떠서 응답하는지 확인 (Jenkins 는 compose 내부망에 있어 서비스 이름으로 부른다)
          sh 'for i in $(seq 1 15); do curl -fsS -o /dev/null http://web-dev/ && exit 0; sleep 2; done; echo "web-dev 응답 없음"; exit 1'
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
