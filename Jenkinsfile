// S15P31E106 CI — GitLab MR·push 마다 실행 (Jenkins job: tingle, 설정 infra/jenkins/casc/jenkins.yaml)
// 지금은 연결 확인 단계: 코드를 받고 결과를 GitLab 에 표시한다. 파트별 검사·배포 단계는 다음 MR 에서 추가한다 (인프라 계획 6절).
pipeline {
  agent any
  options {
    gitLabConnection('lab.ssafy.com')
    timestamps()
    disableConcurrentBuilds()
    buildDiscarder(logRotator(numToKeepStr: '30'))
  }
  stages {
    stage('checkout-info') {
      steps {
        gitlabCommitStatus(name: 'jenkins') {
          sh '''
            echo "event=${gitlabActionType:-manual} source=${gitlabSourceBranch:-?} target=${gitlabTargetBranch:--}"
            git log -1 --oneline
          '''
        }
      }
    }
  }
}
