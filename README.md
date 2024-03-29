Jasper JSP
============

A version of Apache Jasper with most tomcat dependencies removed so that JSP can be used in other servlet containers.

This project uses the artifacts of Apache Jasper, but builds them into jars that exclude a lot of the
unnecessary Apache Tomcat specific classes. 

It also explicitly excludes the `ServletContainerInitializer` named `org.apache.jasper.servlet.JasperInitializer`
(and associated `META-INF/service/` definition) so that the Eclipse Jetty project can extend
the class to supply its own `ServletContainerInitializer` service to initialize the jsp engine.

This project makes no change to the licensing or copyright applied to the apache project.


Branches
============

| Branch                                                                            | Jakarta EE | Apache Jasper Version | Apache EL Version |
|-----------------------------------------------------------------------------------|------------|-----------------------|-------------------|
| [`apache-11.0.x`](https://github.com/jetty-project/jasper-jsp/tree/apache-11.0.x) | EE11       | 11.0.x                | 11.0.x            |
| [`apache-10.1.x`](https://github.com/jetty-project/jasper-jsp/tree/apache-10.1.x) | EE10       | 10.1.x                | 10.1.x            |
| [`apache-10.0.x`](https://github.com/jetty-project/jasper-jsp/tree/apache-10.0.x) | EE9        | 10.0.x                | 10.0.x            |
| [`apache-9`](https://github.com/jetty-project/jasper-jsp/tree/apache-9)           | EE8        | 9.x                   | 9.x               | 
