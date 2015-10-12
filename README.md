Jasper JSP
============

A version of apache jasper with most tomcat dependencies removed so that JSP can be used in other servlet containers.

This project uses the artifacts of apache jasper, but builds them into jars that exclude a lot of the unnecessary tomcat classes. It also explicitly excludes the org.apache.jasper.servlet.JasperInitializer ServletContainerInitializer META-INF/service definition so that the jetty project can extend the class to supply its own ServletContainerInitializer service to initialize the jsp engine.

This project makes no change to the licensing or copyright applied to the apache project.

