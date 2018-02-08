# NemakiWare Docker file

A Dockerfile that produces a Docker Image for NemakiWare

## CouchDB version

This docker file currently hosts CouchDB 2.1.1

## Tomcat version

This docker file currently hosts Tomcat 8.5.5

### Build the image

To create the image `nemakiware`, execute the following command on the `docker` folder:

```
$ docker build -t nemakiware .
```

### Run the image

To run the image and bind to host port 5984:

```
$ docker run -d --name nemakiware-server -p 5984:5984 -p 8080:8080 nemakiware
```

The first time you run your container, it will take some time (1 to 2 minutes) to populate the database with some init scripts. Tomcat will not start until this process is complete. Subsequent runs will start much quicker.