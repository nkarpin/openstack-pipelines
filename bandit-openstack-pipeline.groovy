def gerrit = new com.mirantis.mk.Gerrit()
def git = new com.mirantis.mk.Git()
def python = new com.mirantis.mk.Python()
def common = new com.mirantis.mk.Common()

// TODO: move to pipeline-library
def runShCommandStatus(cmd){
    def common = new com.mirantis.mk.Common()
    def res = [:]
    def stderr = sh(script: 'mktemp', returnStdout: true).trim()
    def stdout = sh(script: 'mktemp', returnStdout: true).trim()

    try {
        common.infoMsg("Run command ${cmd}")
        def status = sh(script: "${cmd} 1>${stdout} 2>${stderr}", returnStatus: true)
        res['stderr'] = sh(script: "cat ${stderr}", returnStdout: true)
        res['stdout'] = sh(script: "cat ${stdout}", returnStdout: true)
        res['status'] = status
    } finally {
        sh(script: "rm ${stderr}", returnStdout: true)
        sh(script: "rm ${stdout}", returnStdout: true)
    }

    return res
}

def runVirtualenvCommandStatus(path, cmd) {
    return runShCommandStatus(". ${path}/bin/activate > /dev/null; ${cmd}")
}

def runBanditTests(venv, target, excludes='', reportPath='', reportFormat='csv', severity=0, confidence=0) {
    // Bandit doesn't fail if target path doesn't exist
    def target_str
    if (target instanceof List){
        for (t in target){
            if (!fileExists(t)){
                error("Target path ${t} doesn't exist, nothing to scan!")
            }
        }
        target_str = target.join(' ')
    } else if (target instanceof String){
        target_str = target
    } else {
        error("Target is instance of ${target.getClass()}, should be List or String")
    }

    def banditArgs = ["-r ${target_str}"]
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

    return runVirtualenvCommandStatus(venv, "bandit ${banditArgs.join(' ')}")
}

// there is no native ini parser in groovy
def getIniParamValue(path, section, param){

    def parserFile = "${env.WORKSPACE}/iniFileParser.py"
    def parserScript = """
import ConfigParser
import sys

path = sys.argv[1]
section = sys.argv[2]
param = sys.argv[3]

config = ConfigParser.ConfigParser()
config.read([path])

val = config.get(section, param)
sys.stdout.write(val.strip()+'\\n')
"""

    writeFile file: parserFile, text: parserScript
    return runShCommandStatus("python ${parserFile} ${path} ${section} ${param}")
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
    def excluded = /.*tempest_plugin.*/

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

        stage("Bandit scan"){

            def res

            if (UPSTREAM.toBoolean()) {
                // Trying to get bandit command from upstream tox.ini
                def ini_res = getIniParamValue('tox.ini', 'testenv:bandit', 'commands')
                def ini_err = ini_res['stderr']
                if (ini_res['status'] == 0){
                    common.infoMsg("Running upstream Bandit tests for ${project_name}")
                    // Currently upstream implementation of bandit doesn't generate report
                    res = runVirtualenvCommandStatus(venv, "${ini_res['stdout'].trim()} -o ${env.WORKSPACE}/${report_path} -f ${REPORT_FORMAT}")
                } else if (ini_res['stderr'].contains('ConfigParser.NoSectionError')){
                    currentBuild.result = 'NOT_BUILT'
                    error("Bandit tests for ${project_name} aren't implemented in upstream yet\n${ini_err}")
                } else {
                    error("Failed to get bandit command\n${ini_err}")
                }

            } else {
                // Get list of directories with python code
                def code_dirs = getIniParamValue('setup.cfg', 'files', 'packages')['stdout'].tokenize('\n')
                // Remove directories which shouldn't be scanned (tempest plugins etc.)
                for (d in code_dirs) {
                    if (d ==~ excluded){
                        code_dirs -= d
                    }
                }
                common.infoMsg("Running downstream Bandit tests for ${project_name}")
                res = runBanditTests(venv, code_dirs, 'tests', "${env.WORKSPACE}/${report_path}",
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
