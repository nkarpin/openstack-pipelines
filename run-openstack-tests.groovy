/**
 *
 * Pipeline for tests execution on predeployed Openstack.
 * Pipeline stages:
 *  - Launch of tests on deployed environment. Currently
 *    supports only Tempest tests, support of Stepler
 *    will be added in future.
 *  - Archiving of tests results to Jenkins master
 *  - Processing results stage - triggers build of job
 *    responsible for results check and upload to testrail
 *
 * Expected parameters:
 *   SALT_MASTER_URL              URL of Salt master
 *   SALT_MASTER_CREDENTIALS      Credentials to the Salt API
 *   TEST_TEMPEST_IMAGE           Docker image to run tempest
 *   TEST_DOCKER_INSTALL          Install docker
 *   TEST_TEMPEST_TARGET          Salt target to run tempest on
 *   TEST_TEMPEST_PATTERN         Tempest tests pattern
 *   TESTRAIL                     Whether upload results to testrail or not
 *   TEST_MILESTONE               Product version for tests
 *   TEST_MODEL                   Salt model used in environment
 *   OPENSTACK_COMPONENT          Name of openstack component being tested
 *   OPENSTACK_VERSION            Version of Openstack being tested
 *   PROC_RESULTS_JOB             Name of job for test results processing
 *   FAIL_ON_TESTS                Whether to fail build on tests failures or not
 *   TEST_PASS_THRESHOLD          Persent of passed tests to consider build successful
 *
 */

common = new com.mirantis.mk.Common()
git = new com.mirantis.mk.Git()
salt = new com.mirantis.mk.Salt()
test = new com.mirantis.mk.Test()

// Define global variables
def saltMaster

node ("${SLAVE_NODE}") {
    try {

        stage ('Connect to salt master') {
            // Connect to Salt master
            saltMaster = salt.connection(SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
        }

        def log_dir = "/home/rally/rally_reports/${OPENSTACK_COMPONENT}"
        def reports_dir = "/root/rally_reports/${OPENSTACK_COMPONENT}"
        def date = sh(script: 'date +%Y-%m-%d', returnStdout: true).trim()

        if (common.checkContains('TEST_DOCKER_INSTALL', 'true')) {
            test.install_docker(saltMaster, TEST_TEMPEST_TARGET)
        }

        // TODO: implement stepler testing from this pipeline
        stage('Run OpenStack tests') {
            test.runTempestTests(saltMaster, TEST_TEMPEST_IMAGE, TEST_TEMPEST_TARGET, TEST_TEMPEST_PATTERN, log_dir)
        }

        stage('Archive rally artifacts') {
            test.archiveRallyArtifacts(saltMaster, TEST_TEMPEST_TARGET, reports_dir)
        }

        stage('Processing results') {
            build(job: PROC_RESULTS_JOB, parameters: [
                [$class: 'StringParameterValue', name: 'TARGET_JOB', value: "${env.JOB_NAME}"],
                [$class: 'StringParameterValue', name: 'TARGET_BUILD_NUMBER', value: "${env.BUILD_NUMBER}"],
                [$class: 'BooleanParameterValue', name: 'TESTRAIL', value: TESTRAIL.toBoolean()],
                [$class: 'StringParameterValue', name: 'TEST_MILESTONE', value: TEST_MILESTONE],
                [$class: 'StringParameterValue', name: 'TEST_MODEL', value: TEST_MODEL],
                [$class: 'StringParameterValue', name: 'OPENSTACK_VERSION', value: OPENSTACK_VERSION],
                [$class: 'StringParameterValue', name: 'TEST_DATE', value: date],
                [$class: 'StringParameterValue', name: 'TEST_PASS_THRESHOLD', value: TEST_PASS_THRESHOLD],
                [$class: 'BooleanParameterValue', name: 'FAIL_ON_TESTS', value: FAIL_ON_TESTS.toBoolean()]
            ])
        }

    } catch (Exception e) {
        currentBuild.result = 'FAILURE'
        throw e
    }
}
