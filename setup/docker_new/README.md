# NemakiWare Official Docker Implementation
NemakiWare can be built and run on Docker without having to install any dependencies on your local machine.

## Build

The build folder contains the docker-compose file that can be used to build all of your `war` files for running on Tomcat. Please refer to the readme within the build directory for further instruction.

## Development

The development folder contains the docker-compose file that can be used as a development environment for NemakiWare. Please refer to the readme within the development directory for further instruction.

## Other Packages for Running

There are some projects you can use to run NemakiWare without downloading the entire NemakiWare codebase:

* `https://github.com/aegif/NemakiWare-run` - Run NemakiWare (requires war files to be built)
* `https://github.com/aegif/NemakiWare-builder` - Build the war files (can be used in a local tomcat or in NemakiWare-run)
* `https://github.com/aegif/NemakiWare-couchdb` - Docker container for your couchdb with the minimum tables required included