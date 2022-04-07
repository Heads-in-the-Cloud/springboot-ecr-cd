def get_pom_value(String key) {
    return sh(
        script: "./mvnw help:evaluate -Dexpression=${key} -q -DforceStdout",
        returnStdout: true
    )
}

def call() {
    pipeline {
        agent any

        environment {
            PROJECT_ID = 'AB-utopia'

            POM_ARTIFACT_ID = get_pom_value('project.artifactId')
            POM_VERSION = get_pom_value('project.version')
            IMAGE_LABEL = "${PROJECT_ID.toLowerCase()}-$POM_ARTIFACT_ID"
            GIT_COMMIT_HASH = sh(
                script: 'git rev-parse HEAD',
                returnStdout: true
            ).trim()
            EKS_CLUSTER_NAME = "$PROJECT_ID"
            SONARQUBE_ID = tool(name: 'SonarQubeScanner-4.6.2')

            JCR_ENDPOINT = "https://ab-artifactory.hitwc.link/artifactory"
            JCR_LOGIN    = credentials("AB_artifactory_login")
        }

        stages {
            stage('Package JAR with Maven') {
                steps {
                    sh './mvnw clean package'
                }
            }

            stage('Code Quality Check') {
                steps {
                    withCredentials([
                        string(credentialsId: "SonarQube Token", variable: 'SONAR_TOKEN')
                    ]) {
                        // SonarQube Analysis
                        sh '''
                            ${SONARQUBE_ID}/bin/sonar-scanner \
                                -Dsonar.login=$SONAR_TOKEN \
                                -Dsonar.projectKey=$PROJECT_ID-$POM_ARTIFACT_ID \
                                -Dsonar.host.url=http://jenkins2.hitwc.link:9000 \
                                -Dsonar.sources=./src/main/java/com/smoothstack/utopia \
                                -Dsonar.java.binaries=./target/classes/com/smoothstack/utopia
                        '''
                    }
                }
            }

            stage('Build Docker image') {
                steps {
                    script {
                        sh 'docker context use default'
                        image = docker.build(env.IMAGE_LABEL)
                    }
                }
            }

            stage('Push to registries') {
                steps {
                    withCredentials([[
                        $class: 'AmazonWebServicesCredentialsBinding',
                        credentialsId: 'jenkins',
                        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                    ]]) {
                        script {
                            def aws_region = sh(
                                script:'aws configure get region',
                                returnStdout: true
                            ).trim()
                            def aws_account_id = sh(
                                script:'aws sts get-caller-identity --query "Account" --output text',
                                returnStdout: true
                            ).trim()
                            env.ECR_URL = "${aws_account_id}.dkr.ecr.${aws_region}.amazonaws.com/$IMAGE_LABEL"

                            docker.withRegistry("https://${env.ECR_URL}", "ecr:$aws_region:jenkins") {
                                image.push('latest')
                                image.push(env.POM_VERSION)
                                image.push(env.GIT_COMMIT_HASH)
                            }

                            env.JCR_URL = "${JCR_ENDPOINT}/$IMAGE_LABEL"


                            sh 'echo ${JCR_LOGIN_PSW} | docker login ${JCR_ENDPOINT} --username ${JCR_LOGIN_USR} --password-stdin'
                            image.push('latest')
                            image.push(env.POM_VERSION)
                            image.push(env.GIT_COMMIT_HASH)
                        }
                    }
                }

                post {
                    cleanup {
                        script {
                            // Remove ECR-tagged images
                            sh 'docker rmi $ECR_URL:latest'
                            sh 'docker rmi $ECR_URL:$POM_VERSION'
                            sh 'docker rmi $ECR_URL:$GIT_COMMIT_HASH'
                            // Remove JCR-tagged images
                            sh 'docker rmi $JCR_URL:latest'
                            sh 'docker rmi $JCR_URL:$POM_VERSION'
                            sh 'docker rmi $JCR_URL:$GIT_COMMIT_HASH'
                            // Remove base image
                            sh 'docker rmi $IMAGE_LABEL'
                        }
                    }
                }
            }

            stage("Deploy to EKS") {
                steps {
                    withCredentials([[
                        $class: 'AmazonWebServicesCredentialsBinding',
                        credentialsId: 'jenkins',
                        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                    ]]) {
                        script {
                            def aws_region = sh(
                                script:'aws configure get region',
                                returnStdout: true
                            ).trim()
                            sh "aws eks --region ${aws_region} update-kubeconfig --name $EKS_CLUSTER_NAME"
                            sh 'kubectl set image deployments/$POM_ARTIFACT_ID $POM_ARTIFACT_ID=$ECR_URL:$GIT_COMMIT_HASH'
                        }
                    }
                }
            }
        }
    }
}

// TODO: additional testing needed
// For returning the build status for the associated commit back to GitHub
void setBuildStatus(String message, String state) {
  repoUrl = sh(
    script: "git config --get remote.origin.url",
    returnStdout: true
  ).trim()

  step([
      $class: "GitHubCommitStatusSetter",
      reposSource: [$class: "ManuallyEnteredRepositorySource", url: repoUrl],
      commitShaSource: [$class: "ManuallyEnteredShaSource", sha: getCommitSha()],
      errorHandlers: [[$class: "ChangingBuildStatusErrorHandler", result: "UNSTABLE"]],
      statusResultSource: [ $class: "ConditionalStatusResultSource", results: [[$class: "AnyBuildResult", message: message, state: state]] ]
  ]);
}
