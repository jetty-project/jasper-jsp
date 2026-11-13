#!groovy

pipeline {
  agent none
  options {
    disableConcurrentBuilds()
    durabilityHint('PERFORMANCE_OPTIMIZED')
    buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '5'))
    timeout(time: 120, unit: 'MINUTES')
  }
  stages {
    stage( "Parallel Stage" ) {
      parallel {
        stage( "Build / Test - JDK8" ) {
          agent { node { label 'linux-light' } }
          options { timeout( time: 120, unit: 'MINUTES' ) }
          steps {
            mavenBuild( "jdk8", "clean install" )
            // Collect up the jacoco execution results
            jacoco inclusionPattern: '**/org/eclipse/jetty/**/*.class',
                   exclusionPattern: '',
                   execPattern: '**/target/jacoco.exec',
                   classPattern: '**/target/classes',
                   sourcePattern: '**/src/main/java'
            recordIssues id: "jdk8", name: "Static Analysis jdk8", aggregatingResults: true, enabledForFailure: true, tools: [mavenConsole(), java()]
            script {
              if (env.BRANCH_NAME == 'apache-11.0.x' ||
                  env.BRANCH_NAME == 'apache-10.0.x' ||
                  env.BRANCH_NAME == 'apache-10.1.x' ||
                  env.BRANCH_NAME == 'apache-9') {
                mavenBuild( "jdk8", "deploy" )
              }
            }

          }
        }
        stage( "Build / Test - JDK11" ) {
          agent { node { label 'linux-light' } }
          options { timeout( time: 120, unit: 'MINUTES' ) }
          steps {
            mavenBuild( "jdk11", "clean install" )
          }
        }
      }
    }
  }
}

/**
 * To other developers, if you are using this method above, please use the following syntax.
 *
 * mavenBuild("<jdk>", "<profiles> <goals> <plugins> <properties>"
 *
 * @param jdk the jdk tool name (in jenkins) to use for this build
 * @param cmdline the command line in "<profiles> <goals> <properties>"`format.
 * @return the Jenkinsfile step representing a maven build
 */
def mavenBuild(jdk, cmdline) {
  def localRepo = "${env.JENKINS_HOME}/${env.EXECUTOR_NUMBER}"
  withEnv(["JAVA_HOME=${ tool "$jdk" }",
           "PATH+MAVEN=${ tool "$jdk" }/bin:${ tool 'maven3' }/bin",
           "MAVEN_OPTS=-Xms2g -Xmx2g -Djava.awt.headless=true"]) {
    configFileProvider([configFile(fileId: 'oss-settings.xml', variable: 'GLOBAL_MVN_SETTINGS')]) {
      try {
        sh "mvn -s $GLOBAL_MVN_SETTINGS -Dmaven.repo.local=$localRepo -V -B -DfailIfNoTests=false -Dmaven.test.failure.ignore=true -T2 -Djetty.testtracker.log=true -e $cmdline"
      } finally {
        junit testResults: '**/target/surefire-reports/TEST-*.xml', allowEmptyResults: true
      }
    }
  }
}

// vim: et:ts=2:sw=2:ft=groovy
