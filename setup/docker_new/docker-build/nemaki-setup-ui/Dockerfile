FROM mozilla/sbt
RUN apt-get update
RUN apt-get install --no-install-recommends -y maven

ADD setup.sh /home/scripts/setup.sh
RUN chmod 700 /home/scripts/setup.sh