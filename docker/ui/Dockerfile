FROM openjdk:8

# Set environment variables
ENV PLAY_HOME /opt/play
ENV PATH $PLAY_HOME:$PATH
ENV PLAY_OPTS="-Xms512m -Xmx1024m -XX:MaxPermSize=256m"

# Install Play Framework - use a more reliable download source
RUN mkdir -p $PLAY_HOME
WORKDIR $PLAY_HOME
RUN apt-get update && \
    apt-get install -y wget unzip && \
    wget -q https://downloads.typesafe.com/typesafe-activator/1.3.12/typesafe-activator-1.3.12-minimal.zip && \
    unzip typesafe-activator-1.3.12-minimal.zip && \
    rm typesafe-activator-1.3.12-minimal.zip && \
    ln -s $PLAY_HOME/activator-1.3.12-minimal/activator /usr/local/bin/activator

# Create app directory
RUN mkdir -p /app
WORKDIR /app

# Copy the UI application
COPY ./ui /app

# Copy configuration files
COPY ./application.conf /app/conf/
COPY ./nemakiware_ui.properties /app/conf/

# Expose the default Play port
EXPOSE 9000

# Start Play application
CMD ["activator", "run"]
