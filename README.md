jetty-jasper
============

A fork of apache jasper with most tomcat dependencies removed

To update from the apache repository:

 git fetch apache_tomcat
 git subtree merge -P apache-el/src/main apache_tomcat/trunk --squash
 git rm $( git status | egrep 'deleted by us:' | cut -d: -f2 )
 git commit
 git subtree merge -P apache-jsp/src/main apache_tomcat/trunk --squash
 git rm $( git status | egrep 'deleted by us:' | cut -d: -f2 )
 git commit

