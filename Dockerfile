FROM mozilla/sbt
RUN apt-get update
RUN apt-get install --no-install-recommends -y maven

COPY ui /root/.sbt/app/ui
COPY common /root/.sbt/app/common
COPY action /root/.sbt/app/action

RUN ls -la /root/.sbt/app
RUN mvn -f /root/.sbt/app/common/pom.xml install
RUN mvn -f /root/.sbt/app/action/pom.xml install