FROM openjdk:8

# Set environment variables
ENV PLAY_HOME /opt/play
ENV PATH $PLAY_HOME:$PATH
ENV PLAY_OPTS="-Xms512m -Xmx1024m -XX:MaxPermSize=256m"

# Install Play Framework
RUN mkdir -p $PLAY_HOME
WORKDIR $PLAY_HOME
RUN curl -O https://downloads.typesafe.com/play/2.2.6/play-2.2.6.zip && \
    unzip play-2.2.6.zip && \
    rm play-2.2.6.zip && \
    ln -s $PLAY_HOME/play-2.2.6/play /usr/local/bin/play

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
CMD ["play", "run"]
