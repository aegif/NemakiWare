FROM java:8

ENV ACTIVATOR_VERSION 1.3.8

# Download and install Activator
RUN wget --output-document /opt/typesafe-activator-$ACTIVATOR_VERSION.zip http://downloads.typesafe.com/typesafe-activator/$ACTIVATOR_VERSION/typesafe-activator-$ACTIVATOR_VERSION.zip
RUN unzip /opt/typesafe-activator-$ACTIVATOR_VERSION.zip -d /opt
RUN rm -f /opt/typesafe-activator-$ACTIVATOR_VERSION.zip
RUN mv /opt/activator-dist-$ACTIVATOR_VERSION /opt/activator
  
# Create a working directory to run your app from
# RUN mkdir /home/app/ui
WORKDIR /home/app/ui

# Expose port 8888 for Activator UI
EXPOSE 8888
# Expose port 9000 for the app under /home/app
EXPOSE 9000
 
# Default Entry Point
# ENTRYPOINT ["/opt/activator/activator", "-Dhttp.address=0.0.0.0"]

# Default Command
# CMD ["ui"]