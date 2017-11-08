def gerrit = new com.mirantis.mk.Gerrit()
def git = new com.mirantis.mk.Git()
def python = new com.mirantis.mk.Python()
def common = new com.mirantis.mk.Common()

// TODO: move to pipeline-library
def runVirtualenvCommandStatus(path, cmd) {
    def common = new com.mirantis.mk.Common()
    def res = [:]
    def stderr = sh(script: 'mktemp', returnStdout: true)
    def stdout = sh(script: 'mktemp', returnStdout: true)

    try {
        def virtualenv_cmd = ". ${path}/bin/activate > /dev/null; ${cmd} 1>${stdout} 2>${stderr}"
        common.infoMsg("[Python ${path}] Run command ${cmd}")
        def status = sh(script: virtualenv_cmd, returnStatus: true)
        res['stderr'] = sh(script: "cat ${stderr}", returnStdout: true)
        res['stdout'] = sh(script: "cat ${stdout}", returnStdout: true)
        res['status'] = status
    } finally {
        sh(script: "rm ${stderr}", returnStdout: true)
        sh(script: "rm ${stdout}", returnStdout: true)
    }
    return res
}

def runBanditTests(venv, target, excludes='', reportPath='', reportFormat='csv', severity=0, confidence=0) {
    // Bandit doesn't fail if target path doesn't exist
    if (!fileExists(target)){
        error("Target path ${target} doesn't exist, nothing to scan!")
    }
    def banditArgs = ["-r ${target}"]
    if (severity > 0) {
        banditArgs.add('-' + 'l' * severity)
    }
    if (confidence > 0) {
        banditArgs.add('-' + 'i' * confidence)
    }
    if (excludes){
        banditArgs.add("-x ${excludes}")
    }
    if (reportPath){
        banditArgs.addAll(["-o ${reportPath}", "-f ${reportFormat}"])
    }
    def res = runVirtualenvCommandStatus(venv, "bandit ${banditArgs.join(' ')}")

    return res
}

// there is no native ini parser in groovy
def getIniParamValue(path, section, param){
    def res = sh(script: 'python -c "' +
        'import ConfigParser,sys;' +
        'config = ConfigParser.ConfigParser();' +
        "config.read(['${path}']);" +
        "val = config.get('${section}', '${param}');" +
        'sys.stdout.write(val.strip() + \'\\n\')"',
        returnStdout: true,).tokenize('\n')
    return res
}

node('python'){

    def build_result = 'FAILURE'
    if (!FAIL_ON_TESTS.toBoolean()){
        build_result = 'SUCCESS'
    }
    def project_url = GERRIT_PROJECT_URL ?: "${GERRIT_SCHEME}://${GERRIT_USER}@${GERRIT_HOST}:${GERRIT_PORT}/${GERRIT_PROJECT}"
    // get simple project name from GERRIT_PROJECT_URL
    // (e.g ssh://oscc-ci@review.fuel-infra.org:29418/openstack/nova - nova)
    def project_name = gerrit._getGerritParamsFromUrl(project_url)[4].tokenize('/').last()
    def project_path = "${env.WORKSPACE}/${project_name}"
    def artifacts_dir = '_artifacts'
    def report_path = "${artifacts_dir}/report-${project_name}.${REPORT_FORMAT}"
    def venv = "${env.WORKSPACE}/bandit_${project_name}"

    sh("rm -rf ${artifacts_dir} && mkdir -p ${artifacts_dir}")

    stage('Preparing virtual env for bandit'){
        python.setupVirtualenv(venv, 'python2', ['bandit'], null, true)
    }

    dir(project_path) {
        deleteDir()
        stage('Checkout'){
            if (common.validInputParam('GERRIT_REFSPEC')){
                gerrit.gerritPatchsetCheckout(project_url, GERRIT_REFSPEC, GERRIT_BRANCH, CREDENTIALS_ID)
            } else {
                git.checkoutGitRepository(project_path, project_url, GERRIT_BRANCH, CREDENTIALS_ID)
            }
        }

        stage("Running bandit scan for ${project_name}"){

            def res

            if (UPSTREAM.toBoolean()) {
                // Currently upstream implementation of bandit doesn't generate report
                def bandit_cmd = getIniParamValue('tox.ini', 'testenv:bandit', 'commands')[0].trim() +
                    " -o ${env.WORKSPACE}/${report_path} -f ${REPORT_FORMAT}"
                res = runVirtualenvCommandStatus(venv, "${bandit_cmd}")
            } else {
                // Not all projects have bandit in tox.ini
                def code_dir = getIniParamValue('setup.cfg', 'files', 'packages')[0]
                res = runBanditTests(venv, "${code_dir}", 'tests', "${env.WORKSPACE}/${report_path}",
                    REPORT_FORMAT, SEVERITY.toInteger(), CONFIDENCE.toInteger())
            }

            if (res['status'] == 1){
                common.errorMsg('''
                    -------------------------------------------------------------
                    !!! Security violations found, please check bandit report !!!
                    -------------------------------------------------------------
                    '''
                )
                currentBuild.result = build_result
            } else if (res['status'] > 1){
                common.errorMsg("Bandit tests failed:\n ${res[stderr]}")
                currentBuild.result = 'FAILURE'
            }
        }
    }

    stage("Archieving bandit scan results for ${project_name}"){
        archiveArtifacts artifacts: report_path
    }
}
