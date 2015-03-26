#!/bin/sh

##Installing general tools
sudo yum -y update
sudo yum -y groupinstall "Development Tools"
sudo yum -y install libicu-devel curl-devel ncurses-devel libtool libxslt fop java-1.6.0-openjdk java-1.6.0-openjdk-devel unixODBC unixODBC-devel openssl-devel

## Preparing working directory
mkdir couchdb_install
DIR=$(cd $(dirname $0); pwd)/couchdb_install
echo $DIR

##Installing Erlang
cd $DIR
wget http://www.erlang.org/download/otp_src_17.4.tar.gz
tar -zxvf otp_src_17.4.tar.gz
cd $DIR/otp_src_17.4
./configure && make
sudo make install


##Install the SpiderMonkey JS Engine
cd $DIR
wget http://ftp.mozilla.org/pub/mozilla.org/js/js185-1.0.0.tar.gz
tar -zxvf js185-1.0.0.tar.gz 
cd $DIR/js-1.8.5/js/src
./configure && make
sudo make install

##Installing CouchDB
cd $DIR
wget http://apache.osuosl.org/couchdb/source/1.6.1/apache-couchdb-1.6.1.tar.gz
tar -zxvf apache-couchdb-1.6.1.tar.gz
cd apache-couchdb-1.6.1
./configure && make
sudo make install

##Setting up CouchDB
sudo adduser --no-create-home couchdb
sudo chown -R couchdb:couchdb /usr/local/var/lib/couchdb /usr/local/var/log/couchdb /usr/local/var/run/couchdb
###Insert path so that SpiderMonkey JavaScript engine works
sudo sed -i".original" -e '25i\export LD_LIBRARY_PATH=/usr/local/lib' /usr/local/etc/rc.d/couchdb 
sudo ln -sf /usr/local/etc/rc.d/couchdb /etc/init.d/couchdb
sudo chkconfig --add couchdb
sudo chkconfig couchdb on
sudo service couchdb start

##Deleting working files
rm -rf $DIR

##Complete
echo 'CouchDB installtion completed!'