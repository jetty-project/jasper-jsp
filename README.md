Jasper JSP
============

A version of apache jasper with most tomcat dependencies removed so that JSP can be used in other servlet containers.

This project does a git subtree mount of http://github.com/apache/tomcat and removes most parts of tomcat except el, jasper and some utlity classes.      The only changes made to the code are to remove dependencies on tomcat container specific elements (eg Logging and InstanceManager).

While the apache upstream project is well maintained, it is not expected that any patches or other divergence from that codebase will take place here.

This project makes no change to the licensing or copyright applied to the apache project.

To update from the apache repository:

    git fetch apache_tomcat
    git subtree merge -P apache-el/src/main apache_tomcat/trunk --squash
    git rm $( git status | egrep 'deleted by us:' | cut -d: -f2 )
    git commit
    git subtree merge -P apache-jsp/src/main apache_tomcat/trunk --squash
    git rm $( git status | egrep 'deleted by us:' | cut -d: -f2 )
    git commit

