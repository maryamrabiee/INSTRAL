#!/bin/bash

set -u
set -e
set -x

version=`grep _version main/phylonet/coalescent/CommandLine.java|grep String|sed -e "s/.*= .//g" -e "s/.;//g"|tr -d '\r'`
echo Version $version

cd main

rm -f phylonet/coalescent/*.class phylonet/util/BitSet*.class phylonet/tree/model/sti/*.class phylonet/tree/io/NewickWriter.class

javac -J-Xmx20m -g -source 1.7 -target 1.7 -classpath ../lib/main.jar:../lib/colt.jar:../lib/JSAP-2.1.jar:../lib/jocl-2.0.0.jar phylonet/util/BitSet*.java phylonet/coalescent/*.java phylonet/tree/model/sti/*.java phylonet/tree/io/NewickWriter.java
jar -J-Xmx20m cvfm ../instral.$version.jar ../manifest.text phylonet/util/BitSet* phylonet/coalescent/*.* phylonet/tree/model/sti/*.* phylonet/tree/io/NewickWriter.*

cd ..

chmod +x instral.$version.jar
sed -e "s/__instral.jar__/instral.$version.jar/g" -e "s/__instral.zip__/Instral.$version.zip/g" README.template.md > README.md
sed -e "s/__instral.jar__/instral.$version.jar/g" -e "s/__instral.zip__/Instral.$version.zip/g" astral-tutorial-template.md > astral-tutorial.md
rm -fr Instral/*
mkdir -p  Instral
cd Instral
ln -s ../lib .
ln -s ../README.md .
ln -s ../instral.$version.jar .
ln -s ../main/test_data .
ln -s ../astral-tutorial.pdf .
ln -s ../thesis-astral.pdf .
cd ..
rm -f Instral.$version.zip
zip -r Instral.$version.zip Instral 

set +x
echo "
Build finished successfully. You can distribute Instral.$version.zip or simply run instral.$version.jar. 
  Note that if you are moving instral.$version.jar to some other location, you need to also move the lib directory."
