/**
 * The total number of integration test groups.
 */
TOTAL_COUNT = 0

/**
 * Integration testing number of tests per group.
 */
GROUP_SIZE = 2

/**
 * the tidb archive is packaged differently on pr than on the branch build,
 * pr build is ./bin/tidb-server
 * branch build is bin/tidb-server
 */
TIDB_ARCHIVE_PATH_PR = "./bin/tidb-server"
TIDB_ARCHIVE_PATH_BRANCH = "bin/tidb-server"

/**
 * Partition the array.
 * @param array
 * @param size
 * @return Array partitions.
 */
static def partition(array, size) {
    def partitions = []
    int partitionCount = array.size() / size

    partitionCount.times { partitionNumber ->
        int start = partitionNumber * size
        int end = start + size - 1
        partitions << array[start..end]
    }

    if (array.size() % size) partitions << array[partitionCount * size..-1]
    return partitions
}

/**
 * Prepare the binary file for testing.
 */
def prepare_binaries() {
    stage('Prepare Binaries') {
        def prepares = [:]

        prepares["build binaries"] = {
            node("${GO_TEST_SLAVE}") {
                container("golang") {
                    println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                    def ws = pwd()
                    deleteDir()
                    unstash 'ticdc'

                    dir("go/src/github.com/pingcap/tiflow") {
                        sh """
                            GOPATH=\$GOPATH:${ws}/go PATH=\$GOPATH/bin:${ws}/go/bin:\$PATH make cdc
                            GOPATH=\$GOPATH:${ws}/go PATH=\$GOPATH/bin:${ws}/go/bin:\$PATH make integration_test_build
                            GOPATH=\$GOPATH:${ws}/go PATH=\$GOPATH/bin:${ws}/go/bin:\$PATH make kafka_consumer
                            GOPATH=\$GOPATH:${ws}/go PATH=\$GOPATH/bin:${ws}/go/bin:\$PATH make check_failpoint_ctl
                            tar czvf ticdc_bin.tar.gz bin/*
                            curl -F test/cdc/ci/ticdc_bin_${env.BUILD_NUMBER}.tar.gz=@ticdc_bin.tar.gz http://fileserver.pingcap.net/upload
                        """
                    }
                    dir("go/src/github.com/pingcap/tiflow/tests/integration_tests") {
                        def cases_name = sh(
                                script: 'find . -maxdepth 2 -mindepth 2 -name \'run.sh\' | awk -F/ \'{print $2}\'',
                                returnStdout: true
                        ).trim().split().join(" ")
                        sh "echo ${cases_name} > CASES"
                    }
                    stash includes: "go/src/github.com/pingcap/tiflow/tests/integration_tests/CASES", name: "cases_name", useDefaultExcludes: false
                }
            }
        }

        parallel prepares
    }
}

/**
 * Start running tests.
 * @param sink_type Type of Sink, optional value: mysql/kafaka.
 * @param node_label
 */
def tests(sink_type, node_label) {
    def all_task_result = []
    try {
        stage("Tests") {
            def test_cases = [:]
            // Set to fail fast.
            test_cases.failFast = true

            // Start running integration tests.
            def run_integration_test = { step_name, case_names ->
                node(node_label) {
                    container("golang") {
                        def ws = pwd()
                        deleteDir()
                        println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                        println "work space path:\n${ws}"
                        println "this step will run tests: ${case_names}"
                        unstash 'ticdc'
                        dir("go/src/github.com/pingcap/tiflow") {
                            download_binaries()
                            try {
                                sh """
                                    s3cmd --version
                                    rm -rf /tmp/tidb_cdc_test
                                    mkdir -p /tmp/tidb_cdc_test
                                    echo "${env.KAFKA_VERSION}" > /tmp/tidb_cdc_test/KAFKA_VERSION
                                    GOPATH=\$GOPATH:${ws}/go PATH=\$GOPATH/bin:${ws}/go/bin:\$PATH make integration_test_${sink_type} CASE="${case_names}"
                                    rm -rf cov_dir
                                    mkdir -p cov_dir
                                    ls /tmp/tidb_cdc_test
                                    cp /tmp/tidb_cdc_test/cov*out cov_dir || touch cov_dir/dummy_file_${step_name}
                                """
                                // cyclic tests do not run on kafka sink, so there is no cov* file.
                                sh """
                                tail /tmp/tidb_cdc_test/cov* || true
                                """
                            } catch (Exception e) {
                                def log_tar_name = case_names.replaceAll("\\s","-")
                                sh """
                                echo "archive logs"
                                ls /tmp/tidb_cdc_test/
                                tar -cvzf log-${log_tar_name}.tar.gz \$(find /tmp/tidb_cdc_test/ -type f -name "*.log")    
                                ls -alh  log-${log_tar_name}.tar.gz   
                                """

                                archiveArtifacts artifacts: "log-${log_tar_name}.tar.gz", caseSensitive: false
                                throw e;
                            }

                        }
                        stash includes: "go/src/github.com/pingcap/tiflow/cov_dir/**", name: "integration_test_${step_name}", useDefaultExcludes: false
                    }
                }
            }

            // Gets the name of each case.
            unstash 'cases_name'
            def cases_name = sh(
                    script: 'cat go/src/github.com/pingcap/tiflow/tests/integration_tests/CASES',
                    returnStdout: true
            ).trim().split()

            // Run integration tests in groups.
            def step_cases = []
            def cases_namesList = partition(cases_name, GROUP_SIZE)
            TOTAL_COUNT = cases_namesList.size()
            cases_namesList.each { case_names ->
                step_cases.add(case_names)
            }
            step_cases.eachWithIndex { case_names, index ->
                def step_name = "step_${index}"
                test_cases["integration test ${step_name}"] = {
                    try {
                        run_integration_test(step_name, case_names.join(" "))
                        all_task_result << ["name": case_names.join(" "), "status": "success", "error": ""]
                    } catch (err) {
                        all_task_result << ["name": case_names.join(" "), "status": "failed", "error": err.message]
                        throw err
                    }  
                }
            }

            parallel test_cases
        }
    } catch (err) {
        println "Error: ${err}"
        throw err
    } finally {
        if (all_task_result) {
            def json = groovy.json.JsonOutput.toJson(all_task_result)
            def ci_pipeline_name = ""
            if (sink_type == "kafka") {
                ci_pipeline_name = "cdc_ghpr_kafka_integration_test"
            } else if (sink_type == "mysql") {
                ci_pipeline_name = "cdc_ghpr_integration_test"
            }
            writeJSON file: 'ciResult.json', json: json, pretty: 4
            sh "cat ciResult.json"
            archiveArtifacts artifacts: 'ciResult.json', fingerprint: true
            sh """
            curl -F cicd/ci-pipeline-artifacts/result-${ci_pipeline_name}_${BUILD_NUMBER}.json=@ciResult.json ${FILE_SERVER_URL}/upload
            """
        }         
    }
}

/**
 * Download the integration test-related binaries.
 */
def download_binaries() {
    def TIDB_BRANCH = params.getOrDefault("release_test__tidb_commit", ghprbTargetBranch)
    def TIKV_BRANCH = params.getOrDefault("release_test__tikv_commit", ghprbTargetBranch)
    def PD_BRANCH = params.getOrDefault("release_test__pd_commit", ghprbTargetBranch)
    def TIFLASH_BRANCH = params.getOrDefault("release_test__release_branch", ghprbTargetBranch)
    def TIFLASH_COMMIT = params.getOrDefault("release_test__tiflash_commit", null)

    // parse tidb branch
    def m1 = ghprbCommentBody =~ /tidb\s*=\s*([^\s\\]+)(\s|\\|$)/
    def tidb_archive_path = TIDB_ARCHIVE_PATH_BRANCH
    if (m1) {
        TIDB_BRANCH = "${m1[0][1]}"
        tidb_archive_path = TIDB_ARCHIVE_PATH_PR
    }
    m1 = null
    println "TIDB_BRANCH=${TIDB_BRANCH}"

    // parse tikv branch
    def m2 = ghprbCommentBody =~ /tikv\s*=\s*([^\s\\]+)(\s|\\|$)/
    if (m2) {
        TIKV_BRANCH = "${m2[0][1]}"
    }
    m2 = null
    println "TIKV_BRANCH=${TIKV_BRANCH}"

    // parse pd branch
    def m3 = ghprbCommentBody =~ /pd\s*=\s*([^\s\\]+)(\s|\\|$)/
    if (m3) {
        PD_BRANCH = "${m3[0][1]}"
    }
    m3 = null
    println "PD_BRANCH=${PD_BRANCH}"

    // parse tiflash branch
    def m4 = ghprbCommentBody =~ /tiflash\s*=\s*([^\s\\]+)(\s|\\|$)/
    if (m4) {
        TIFLASH_BRANCH = "${m4[0][1]}"
    }
    m4 = null
    println "TIFLASH_BRANCH=${TIFLASH_BRANCH}"

    println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
    def tidb_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/tidb/${TIDB_BRANCH}/sha1").trim()
    def tikv_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/tikv/${TIKV_BRANCH}/sha1").trim()
    def pd_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1").trim()
    def tiflash_sha1 = TIFLASH_COMMIT
    if (TIFLASH_COMMIT == null) {
        tiflash_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/tiflash/${TIFLASH_BRANCH}/sha1").trim()
    }
    def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server.tar.gz"
    def tikv_url = "${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz"
    def pd_url = "${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz"
    def tiflash_url = "${FILE_SERVER_URL}/download/builds/pingcap/tiflash/${TIFLASH_BRANCH}/${tiflash_sha1}/centos7/tiflash.tar.gz"

    // If it is triggered upstream, the upstream link is used.
    def from = params.getOrDefault("triggered_by_upstream_pr_ci", "")
    switch (from) {
        case "tikv":
            def tikv_download_link = params.upstream_pr_ci_override_tikv_download_link
            println "Use the upstream download link, upstream_pr_ci_override_tikv_download_link=${tikv_download_link}"
            tikv_url = tikv_download_link
            break;
        case "tidb":
            def tidb_download_link = params.upstream_pr_ci_override_tidb_download_link
            println "Use the upstream download link, upstream_pr_ci_override_tidb_download_link=${tidb_download_link}"
            tidb_url = tidb_download_link
            // Because the tidb archive is packaged differently on pr than on the branch build,
            // we have to use a different unzip path.
            tidb_archive_path = "./bin/tidb-server"
            break;
    }

    sh """
        mkdir -p third_bin
        mkdir -p tmp
        mkdir -p bin
        tidb_url="${tidb_url}"
        tidb_archive_path="${tidb_archive_path}"
        tikv_url="${tikv_url}"
        pd_url="${pd_url}"
        tiflash_url="${tiflash_url}"
        minio_url="${FILE_SERVER_URL}/download/minio.tar.gz"
        curl \${tidb_url} | tar xz -C ./tmp \${tidb_archive_path}
        curl \${pd_url} | tar xz -C ./tmp bin/*
        curl \${tikv_url} | tar xz -C ./tmp bin/tikv-server
        curl \${minio_url} | tar xz -C ./tmp/bin minio
        mv tmp/bin/* third_bin
        curl \${tiflash_url} | tar xz -C third_bin
        mv third_bin/tiflash third_bin/_tiflash
        mv third_bin/_tiflash/* third_bin
        curl ${FILE_SERVER_URL}/download/builds/pingcap/go-ycsb/test-br/go-ycsb -o third_bin/go-ycsb
        curl -L http://fileserver.pingcap.net/download/builds/pingcap/cdc/etcd-v3.4.7-linux-amd64.tar.gz | tar xz -C ./tmp
        mv tmp/etcd-v3.4.7-linux-amd64/etcdctl third_bin
        curl http://fileserver.pingcap.net/download/builds/pingcap/cdc/new_sync_diff_inspector.tar.gz | tar xz -C ./third_bin
        curl -L ${FILE_SERVER_URL}/download/builds/pingcap/test/jq-1.6/jq-linux64 -o jq
        mv jq third_bin
        chmod a+x third_bin/*
        rm -rf tmp
        curl -L http://fileserver.pingcap.net/download/test/cdc/ci/ticdc_bin_${env.BUILD_NUMBER}.tar.gz | tar xvz -C .
        mv ./third_bin/* ./bin
        rm -rf third_bin
    """
}


/**
 * Collect and calculate test coverage.
 */
def coverage() {
    stage('Coverage') {
        node("lightweight_pod") {
            def ws = pwd()
            deleteDir()
            unstash 'ticdc'

            // unstash all integration tests.
            def step_names = []
            for (int i = 1; i < TOTAL_COUNT; i++) {
                step_names.add("integration_test_step_${i}")
            }
            step_names.each { item ->
                unstash item
            }

            dir("go/src/github.com/pingcap/tiflow") {
                container("golang") {
                    archiveArtifacts artifacts: 'cov_dir/*', fingerprint: true
                    withCredentials([string(credentialsId: 'coveralls-token-ticdc', variable: 'COVERALLS_TOKEN')]) {
                        timeout(30) {
                            sh '''
                            rm -rf /tmp/tidb_cdc_test
                            mkdir -p /tmp/tidb_cdc_test
                            cp cov_dir/* /tmp/tidb_cdc_test
                            set +x
                            BUILD_NUMBER=${BUILD_NUMBER} CODECOV_TOKEN="${CODECOV_TOKEN}" COVERALLS_TOKEN="${COVERALLS_TOKEN}" GOPATH=${ws}/go:\$GOPATH PATH=${ws}/go/bin:/go/bin:\$PATH JenkinsCI=1 make integration_test_coverage || true
                            set -x
                            '''
                        }
                    }
                }
            }
        }
    }
}

return this