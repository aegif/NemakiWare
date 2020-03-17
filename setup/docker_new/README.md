# Nemakiware for Docker (Build / Run)

You can use Docker to build and run nemakiware without having to worry about setting up your own java environment.

## Prerequsites

In order to run Nemakiware for Docker, you must have the following installed:

- Docker (https://docs.docker.com/install/)
- Docker compose (https://docs.docker.com/compose/install/)

## Build

Building is done within its own docker container. To build, run the following command within the `docker-build` directory:

- `docker-compose up`

This will take several minutes to complete and will build the latest war files for you.

## Run

