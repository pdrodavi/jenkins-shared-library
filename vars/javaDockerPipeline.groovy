def call(Map config = [:]) {

    pipeline {
        agent any

        tools {
            jdk 'jdk17'
            maven 'maven3'
        }

        environment {
            DOCKER_NAMESPACE = 'pdrodavi'
            IMAGE_TAG = "${BUILD_NUMBER}"
            DOCKER_CREDENTIALS_ID = config.dockerCreds ?: 'dockerhub-creds'
            SONAR_ORG = 'pdrodavi'
            SONAR_HOST_URL = 'https://sonarcloud.io'
            GITHUB_TOKEN_ID = config.githubToken ?: 'github-token'
        }

        stages {

            stage('Inputs') {
                steps {
                    script {
                        def userInput = input(
                            message: 'Configuração do Pipeline',
                            ok: 'Executar',
                            parameters: [
                                string(name: 'GIT_REPO', description: 'URL do repositório Git'),
                                string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Branch')
                            ]
                        )

                        env.GIT_REPO = userInput.GIT_REPO
                        env.GIT_BRANCH = userInput.GIT_BRANCH
                    }
                }
            }

            stage('Checkout') {
                steps {
                    git branch: env.GIT_BRANCH, url: env.GIT_REPO
                }
            }

            stage('Build Maven') {
                steps {
                    sh 'mvn clean verify -DskipTests'
                }
            }

            stage('Definir aplicação') {
                steps {
                    script {
                        env.APP_NAME = sh(
                            script: "mvn help:evaluate -Dexpression=project.artifactId -q -DforceStdout",
                            returnStdout: true
                        ).trim()

                        env.DOCKER_IMAGE = "${DOCKER_NAMESPACE}/${APP_NAME}"
                    }
                }
            }

            stage('SonarCloud Scan') {
                steps {
                    withSonarQubeEnv('SonarCloud') {
                        withCredentials([string(credentialsId: 'sonarcloud-token', variable: 'SONAR_TOKEN')]) {
                            sh """
                              mvn sonar:sonar \
                                -Dsonar.projectKey=${SONAR_ORG}_${APP_NAME} \
                                -Dsonar.organization=${SONAR_ORG} \
                                -Dsonar.host.url=${SONAR_HOST_URL} \
                                -Dsonar.login=$SONAR_TOKEN
                            """
                        }
                    }
                }
            }

            stage('Quality Gate') {
                steps {
                    timeout(time: 5, unit: 'MINUTES') {
                        waitForQualityGate abortPipeline: true
                    }
                }
            }

            stage('Docker Build') {
                steps {
                    sh """
                      docker build \
                        -t ${DOCKER_IMAGE}:${IMAGE_TAG} \
                        -t ${DOCKER_IMAGE}:latest \
                        .
                    """
                }
            }

            stage('Docker Push') {
                steps {
                    withCredentials([
                        usernamePassword(
                            credentialsId: DOCKER_CREDENTIALS_ID,
                            usernameVariable: 'DOCKER_USER',
                            passwordVariable: 'DOCKER_PASS'
                        )
                    ]) {
                        sh '''
                          echo "$DOCKER_PASS" | docker login -u "$DOCKER_USER" --password-stdin
                          docker push ${DOCKER_IMAGE}:${IMAGE_TAG}
                          docker push ${DOCKER_IMAGE}:latest
                        '''
                    }
                }
            }
        }

        post {
            success {
                echo "Pipeline executado com sucesso"
            }
            failure {
                echo "Pipeline falhou"
            }
            always {
                sh 'docker logout || true'
            }
        }
    }
}
