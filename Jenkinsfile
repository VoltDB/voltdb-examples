pipeline {
    agent {
        label 'gcloud-build--rocky-linux-8--x64'
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '10'))
        timeout(time: 2, unit: 'HOURS')
        timestamps()
        disableConcurrentBuilds()
    }

    environment {
        JAVA_HOME = '/opt/corretto_java17'
        PATH = "${JAVA_HOME}/bin:${env.PATH}"
        VOLTDB_IMAGE = "${params.VOLTDB_IMAGE ?: 'voltdb/voltdb-enterprise:15.1.0'}"
    }

    parameters {
        string(
            name: 'VOLTDB_IMAGE',
            defaultValue: 'voltdb/voltdb-enterprise:15.1.0',
            description: 'VoltDB Docker image to use for tests and benchmarks'
        )
        booleanParam(
            name: 'RUN_BENCHMARKS',
            defaultValue: true,
            description: 'Run benchmark tests (takes longer)'
        )
        booleanParam(
            name: 'SKIP_INTEGRATION_TESTS',
            defaultValue: false,
            description: 'Skip integration tests'
        )
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Build') {
            steps {
                sh 'mvn clean compile test-compile -DskipTests'
            }
        }

        stage('Unit Tests') {
            steps {
                sh 'mvn test'
            }
            post {
                always {
                    junit testResults: '**/target/surefire-reports/*.xml', allowEmptyResults: true
                }
            }
        }

        stage('Package') {
            steps {
                sh 'mvn package -DskipTests'
            }
            post {
                success {
                    archiveArtifacts artifacts: '**/target/*.jar', fingerprint: true, allowEmptyArchive: true
                }
            }
        }

        stage('Integration Tests') {
            when {
                expression { return !params.SKIP_INTEGRATION_TESTS }
            }
            steps {
                withCredentials([
                    usernamePassword(credentialsId: 'dockerhub', usernameVariable: 'DOCKER_CREDS_USR', passwordVariable: 'DOCKER_CREDS_PSW'),
                    string(credentialsId: 'VOLTDB_LICENSE1', variable: 'VOLTDB_LICENSE_CONTENT')
                ]) {
                    sh '''
                        # Login to Docker Hub
                        echo "${DOCKER_CREDS_PSW}" | docker login -u "${DOCKER_CREDS_USR}" --password-stdin

                        # Ensure Docker is available
                        docker info

                        # Pull VoltDB image
                        echo "Using VoltDB image: ${VOLTDB_IMAGE}"
                        docker pull ${VOLTDB_IMAGE}

                        # Write license to file (decode base64)
                        echo "${VOLTDB_LICENSE_CONTENT}" | base64 -d > license.xml
                    '''

                    // Run integration tests with license
                    sh '''
                        export PATH="${JAVA_HOME}/bin:${PATH}"
                        export VOLTDB_LICENSE="${WORKSPACE}/license.xml"
                        mvn verify -DskipUTs
                    '''
                }
            }
            post {
                always {
                    junit testResults: '**/target/failsafe-reports/*.xml', allowEmptyResults: true
                    sh 'rm -f license.xml || true'
                    sh 'docker logout || true'
                }
            }
        }

        stage('Benchmarks') {
            when {
                expression { return params.RUN_BENCHMARKS }
            }
            steps {
                withCredentials([
                    usernamePassword(credentialsId: 'dockerhub', usernameVariable: 'DOCKER_CREDS_USR', passwordVariable: 'DOCKER_CREDS_PSW'),
                    string(credentialsId: 'VOLTDB_LICENSE1', variable: 'VOLTDB_LICENSE_CONTENT')
                ]) {
                    sh '''
                        # Login to Docker Hub
                        echo "${DOCKER_CREDS_PSW}" | docker login -u "${DOCKER_CREDS_USR}" --password-stdin

                        # Pull VoltDB image
                        echo "Using VoltDB image: ${VOLTDB_IMAGE}"
                        docker pull ${VOLTDB_IMAGE}

                        # Write license to file (decode base64)
                        echo "${VOLTDB_LICENSE_CONTENT}" | base64 -d > license.xml
                    '''

                    // Run benchmarks sequentially
                    sh '''
                        export PATH="${JAVA_HOME}/bin:${PATH}"
                        export VOLTDB_LICENSE="${WORKSPACE}/license.xml"

                        echo "Using Java: $(java -version 2>&1 | head -1)"
                        echo "Using VoltDB image: ${VOLTDB_IMAGE}"

                        echo "Running voter benchmark..."
                        mvn verify -pl voter -Pbenchmark -DskipTests

                        echo "Running voltkv benchmark..."
                        mvn verify -pl voltkv -Pbenchmark -DskipTests

                        echo "Running nbbo benchmark..."
                        mvn verify -pl nbbo -Pbenchmark -DskipTests

                        echo "Running positionkeeper benchmark..."
                        mvn verify -pl positionkeeper -Pbenchmark -DskipTests

                        echo "Running simple benchmark..."
                        mvn verify -pl simple -Pbenchmark -DskipTests
                    '''
                }
            }
            post {
                always {
                    sh 'rm -f license.xml || true'
                    sh 'docker logout || true'
                }
            }
        }
    }

    post {
        always {
            // Clean up Docker containers
            sh '''
                docker ps -q --filter "ancestor=${VOLTDB_IMAGE}" | xargs -r docker stop || true
                docker container prune -f || true
            '''
            cleanWs()
        }
        success {
            echo 'Build completed successfully!'
        }
        failure {
            echo 'Build failed!'
        }
    }
}
