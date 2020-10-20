#!/usr/bin/env groovy

@Library('apm@current') _

pipeline {
  agent none
  environment {
    BASE_DIR = 'src/github.com/elastic/beats'
    PIPELINE_LOG_LEVEL = "INFO"
    BEATS_TESTER_JOB = 'Beats/beats-tester-mbp/master'
  }
  options {
    timeout(time: 1, unit: 'HOURS')
    buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '20', daysToKeepStr: '30'))
    timestamps()
    ansiColor('xterm')
    disableResume()
    durabilityHint('PERFORMANCE_OPTIMIZED')
    disableConcurrentBuilds()
  }
  triggers {
    issueCommentTrigger('(?i)^\\/beats-tester$')
    upstream("Beats/packaging/${env.JOB_BASE_NAME}")
  }
  stages {
    stage('Filter build') {
      agent { label 'ubuntu && immutable' }
      when {
        beforeAgent true
        anyOf {
          triggeredBy cause: "IssueCommentCause"
          expression {
            def ret = isUserTrigger() || isUpstreamTrigger()
            if(!ret){
              currentBuild.result = 'NOT_BUILT'
              currentBuild.description = "The build has been skipped"
              currentBuild.displayName = "#${BUILD_NUMBER}-(Skipped)"
              echo("the build has been skipped due the trigger is a branch scan and the allow ones are manual, GitHub comment, and upstream job")
            }
            return ret
          }
        }
      }
      stages {
        stage('Checkout') {
          options { skipDefaultCheckout() }
          steps {
            deleteDir()
            gitCheckout(basedir: "${BASE_DIR}")
            setEnvVar('VERSION', sh(script: "grep ':stack-version:' ${BASE_DIR}/libbeat/docs/version.asciidoc | cut -d' ' -f2", returnStdout: true).trim())
          }
        }
        stage('Build master') {
          options { skipDefaultCheckout() }
          when { branch 'master' }
          steps {
            // TODO: to use the git commit that triggered the upstream build
            runBeatsTesterJob(version: "${env.VERSION}-SNAPSHOT")
          }
        }
        stage('Build *.x branch') {
          options { skipDefaultCheckout() }
          when { branch '*.x' }
          steps {
            // TODO: to use the git commit that triggered the upstream build
            runBeatsTesterJob(version: "${env.VERSION}-SNAPSHOT")
          }
        }
        stage('Build PullRequest') {
          options { skipDefaultCheckout() }
          when { changeRequest() }
          steps {
            runBeatsTesterJob(version: "${env.VERSION}-SNAPSHOT",
                              apm: "https://storage.googleapis.com/apm-ci-artifacts/jobs/pull-requests/pr-${env.CHANGE_ID}",
                              beats: "https://storage.googleapis.com/beats-ci-artifacts/pull-requests/pr-${env.CHANGE_ID}")
          }
        }
        stage('Build release branch') {
          options { skipDefaultCheckout() }
          when {
            not {
              allOf {
                branch comparator: 'REGEXP', pattern: '(master|.*x)'
                changeRequest()
              }
            }
           }
          steps {
            // TODO: to use the git commit that triggered the upstream build
            runBeatsTesterJob(version: "${env.VERSION}-SNAPSHOT")
          }
        }
      }
    }
  }
}

def runBeatsTesterJob(Map args = [:]) {
  if (args.apm && args.beats) {
    build(job: env.BEATS_TESTER_JOB, propagate: false, wait: false,
          parameters: [
            string(name: 'APM_URL_BASE', value: args.apm),
            string(name: 'BEATS_URL_BASE', value: args.beats),
            string(name: 'VERSION', value: args.version)
          ])
  } else {
    build(job: env.BEATS_TESTER_JOB, propagate: false, wait: false, parameters: [ string(name: 'VERSION', value: args.version) ])
  }
}