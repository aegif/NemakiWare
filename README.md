======================
NemakiWare
======================

What is NemakiWare?
------

NemakiWare is a open source enterprise content management(ECM) system. It is:
* **Compliant with CMIS standard**
    * Integration or replacement with other existing/future CMIS compliant software can be done easily by the power of standard. 


* **All-in-One package(Server, Client, Full-text search engine, archive feature etc.)** 
    * CMIS defines just the core of ECM. You need some peripheral components/feature, and here it is. 
    * Not only the server but the client and the search engine are (going to be) connected by CMIS interface.


* **NoSQL CouchDB backend** 
    * Simple document based NoSQL database.
    * Easy to manage and replicate the database.


"Nemaki" derives from Japanese word "寝巻き" which means night clothes and you can relax and enjoy your enterprise time as you lie on a couch in your room!


Prerequisite for installation
------
* Java 1.6, Ruby 1.9.3p362, Rails 3.2.11
* CouchDB 1.0.6
* Platform: OSX 10.8.3 or CentOS 6 (Although Windows is not tested, NemakiWare is basically platform-agnostic).
* Package management system: 
    * Maven 3.x required
    * yum(CentOS), Homebrew/Mac Port(Mac) as you like

Installation
------
* Clone the repository  
```sh
$ git@github.com:NemakiWare/NemakiWare.git
```
You get the folder `<NemakiWare_Home>` in the location you cloned, which includes three subfolders nemakiware, nemakisolr, nemakishare.

* Install CouchDB  
Mac(See [Wiki](http://wiki.apache.org/couchdb/Installing_on_OSX))  
```sh
$ sudo port install couchdb
```
CentOS  
```sh
$ sudo yum install couchdb
```

* Setup CouchDB (If it's not started, start it before setup)  
```sh
$ cd <NemakiWare_Home>/nemakiware/setup
$ sh setup.sh
```
    
* Install the server and kick it off  
```sh
$ cd <NemakiWare_Home>/nemakiware
$ mvn jetty:run  
```
It will take a little time to download the dependent packages. 

* Install the search engine(Solr) and kick it off  
```sh
$ cd <NemakiWare_Home>/nemakisolr
$ mvn jetty:run
```

* Install the client and overwrite ActiveCMIS gem
```sh
$ cd <NemakiWare_Home>/nemakishare
$ bundle install  
```
You have installed [ActiveCMIS](https://github.com/xaop/activecmis) gem.  
NemakiWare needs some customizes of ActiveCMIS library but it's not yet pull requested,  
so you have to overwrite them. This process will be unnecessary after they are took in to ActiveCMIS.  
To overwrite ActiveCMIS,
```sh
$ gem which active_cmis
```
You get like 
```sh
...gems/ruby-1.9.3-p362/gems/active_cmis-0.3.2/lib/active_cmis.rb  
```
In this case, "active_cmis-0.3.2" is the gem folder.  
Then, copy to the "lib" folder
```sh
$ cp -r <NemakiWare_Home>/nemakishare/activecmis_lib <path/to/active_cmis gem/>
```

* Now, open the login  window  
    http://127.0.0.1:3000/nodes/  
    * ID:admin
    * Password admin


That's all!


Usage
----------
TBW  

* Navigation
* Show/Edit/Manage permission/Delete/Search
* Site
* Admin
    * User/Group management
    * Solr management
    * Archive


Configuration
----------
TBW

Development
----------
TBW  

To import projects of the server(nemakiware) and the search engine(nemakisolr) to Eclipse,
```sh
$ mvn eclipse:eclipse
```

 
License
----------
NemakiWare is free software: you can redistribute it and/or modify  
it under the terms of the GNU General Public License as published by  
the Free Software Foundation, either version 3 of the License, or  
(at your option) any later version.  

NemakiWare is distributed in the hope that it will be useful,  
but WITHOUT ANY WARRANTY; without even the implied warranty of  
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the  
GNU General Public License for more details.  

You should have received a copy of the GNU General Public License  
along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.  
