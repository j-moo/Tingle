# Tingle Jenkins — 공식 LTS 이미지에 docker CLI·compose 와 플러그인, 설정 코드(JCasC)를 넣는다.
# 빌드 도구(JDK·Node·Python·Android SDK)는 Jenkinsfile 에서 단계별 docker 이미지로 돌린다.
FROM jenkins/jenkins:lts-jdk21

USER root
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl \
 && install -m 0755 -d /etc/apt/keyrings \
 && curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc \
 && chmod a+r /etc/apt/keyrings/docker.asc \
 && . /etc/os-release \
 && echo "deb [signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/debian ${VERSION_CODENAME} stable" > /etc/apt/sources.list.d/docker.list \
 && apt-get update \
 && apt-get install -y --no-install-recommends docker-ce-cli docker-compose-plugin docker-buildx-plugin \
 && rm -rf /var/lib/apt/lists/*
USER jenkins

COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt

COPY casc /usr/share/jenkins/casc
ENV CASC_JENKINS_CONFIG=/usr/share/jenkins/casc \
    JAVA_OPTS=-Djenkins.install.runSetupWizard=false
