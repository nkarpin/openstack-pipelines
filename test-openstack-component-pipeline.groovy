/**
 *
 * Wrapper pipeline for automated tests of Openstack components, deployed by MCP.
 * Pipeline stages:
 *  - Deployment of MCP environment with Openstack
 *  - Executing Smoke tests - set of tests to check basic functionality
 *  - Executing component tests - set of tests specific to component being tested,
 *    (or set of all tests).
 *
 * Flow parameters:
 *   GERRIT_CREDENTIALS                ID of gerrit credentials
 *   GERRIT_CHECK                      Is this build is triggered by gerrit
 *   HEAT_STACK_ZONE                   VM availability zone
 *   OPENSTACK_COMPONENT               Openstack component to test
 *   OPENSTACK_VERSION                 Version of Openstack being tested
 *   OPENSTACK_API_URL                 OpenStack API address
 *   OPENSTACK_API_CREDENTIALS         Credentials to the OpenStack API
 *   OPENSTACK_API_PROJECT             OpenStack project to connect to
 *   OPENSTACK_API_PROJECT_DOMAIN      OpenStack project domain to connect to
 *   OPENSTACK_API_PROJECT_ID          OpenStack project ID to connect to
 *   OPENSTACK_API_USER_DOMAIN         OpenStack user domain
 *   OPENSTACK_API_CLIENT              Versions of OpenStack python clients
 *   OPENSTACK_API_VERSION             Version of the OpenStack API (2/3)
 *   SALT_OVERRIDES                    Override reclass model parameters
 *   STACK_DELETE                      Whether to cleanup created stack
 *   STACK_TEST_JOB                    Job for launching tests
 *   STACK_TYPE                        Environment type (heat, physical)
 *   STACK_INSTALL                     Which components of the stack to install
 *   TEST_TEMPEST_TARGET               Salt target for tempest tests
 *   TEST_TEMPEST_PATTERN              Tempest tests pattern
 *   TEST_MILESTONE                    MCP version
 *   TEST_MODEL                        Reclass model of environment
 *   TEST_PASS_THRESHOLD               Persent of passed tests to consider build successful
 *
 **/
def common = new com.mirantis.mk.Common()
def gerrit = new com.mirantis.mk.Gerrit()

node('python') {
    try {

        if (GERRIT_CHECK == true) {
            //TODO: implement injection of repository with component's package into build
            cred = common.getCredentials(GERRIT_CREDENTIALS, 'key')
            gerritChange = gerrit.getGerritChange(cred.username, GERRIT_HOST, GERRIT_CHANGE_NUMBER, GERRIT_CREDENTIALS, true)
            testrail = false
        } else {
            //TODO: in case of not Gerrit triggered build - run previous build cleanup
            testrail = true
        }

        stack_deploy_job = "deploy-${STACK_TYPE}-${TEST_MODEL}"

        // Deploy MCP environment
        stage('Trigger deploy job') {
            deployBuild = build(job: stack_deploy_job, parameters: [
                [$class: 'StringParameterValue', name: 'OPENSTACK_API_PROJECT', value: OPENSTACK_API_PROJECT],
                [$class: 'StringParameterValue', name: 'HEAT_STACK_ZONE', value: HEAT_STACK_ZONE],
                [$class: 'StringParameterValue', name: 'STACK_INSTALL', value: STACK_INSTALL],
                [$class: 'StringParameterValue', name: 'STACK_TEST', value: ''],
                [$class: 'StringParameterValue', name: 'STACK_TYPE', value: STACK_TYPE],
                [$class: 'BooleanParameterValue', name: 'STACK_DELETE', value: false],
                [$class: 'TextParameterValue', name: 'SALT_OVERRIDES', value: SALT_OVERRIDES]
            ])
        }

        // get SALT_MASTER_URL
        deployBuildParams = deployBuild.description.tokenize( ' ' )
        SALT_MASTER_URL = "http://${deployBuildParams[1]}:6969"
        STACK_NAME = "${deployBuildParams[0]}"
        echo "Salt API is accessible via ${SALT_MASTER_URL}"

        // Perform smoke tests to fail early
        stage('Run Smoke tests') {
            build(job: STACK_TEST_JOB, parameters: [
                [$class: 'StringParameterValue', name: 'SALT_MASTER_URL', value: SALT_MASTER_URL],
                [$class: 'StringParameterValue', name: 'TEST_TEMPEST_TARGET', value: TEST_TEMPEST_TARGET],
                [$class: 'StringParameterValue', name: 'TEST_TEMPEST_PATTERN', value: 'set=smoke'],
                [$class: 'BooleanParameterValue', name: 'TESTRAIL', value: false],
                [$class: 'StringParameterValue', name: 'OPENSTACK_COMPONENT', value: 'smoke'],
                [$class: 'StringParameterValue', name: 'TEST_PASS_THRESHOLD', value: '0'],
                [$class: 'BooleanParameterValue', name: 'FAIL_ON_TESTS', value: true]
            ])
        }

        // Perform component specific tests
        stage("Run ${OPENSTACK_COMPONENT} tests") {
            build(job: STACK_TEST_JOB, parameters: [
                [$class: 'StringParameterValue', name: 'SALT_MASTER_URL', value: SALT_MASTER_URL],
                [$class: 'StringParameterValue', name: 'TEST_TEMPEST_TARGET', value: TEST_TEMPEST_TARGET],
                [$class: 'StringParameterValue', name: 'TEST_TEMPEST_PATTERN', value: TEST_TEMPEST_PATTERN],
                [$class: 'StringParameterValue', name: 'TEST_MILESTONE', value: TEST_MILESTONE],
                [$class: 'StringParameterValue', name: 'TEST_MODEL', value: TEST_MODEL],
                [$class: 'StringParameterValue', name: 'OPENSTACK_VERSION', value: OPENSTACK_VERSION],
                [$class: 'BooleanParameterValue', name: 'TESTRAIL', value: testrail.toBoolean()],
                [$class: 'StringParameterValue', name: 'OPENSTACK_COMPONENT', value: OPENSTACK_COMPONENT],
                [$class: 'StringParameterValue', name: 'TEST_PASS_THRESHOLD', value: TEST_PASS_THRESHOLD],
                [$class: 'BooleanParameterValue', name: 'FAIL_ON_TESTS', value: true]
            ])
        }
    } catch (Exception e) {
        currentBuild.result = 'FAILURE'
        throw e
    } finally {

        //
        // Clean
        //
        if (common.validInputParam('STACK_DELETE') && STACK_DELETE.toBoolean() == true) {
            stage('Trigger cleanup job') {
                common.errorMsg('Stack cleanup job triggered')
                build(job: STACK_CLEANUP_JOB, parameters: [
                    [$class: 'StringParameterValue', name: 'STACK_NAME', value: STACK_NAME],
                    [$class: 'StringParameterValue', name: 'STACK_TYPE', value: STACK_TYPE],
                    [$class: 'StringParameterValue', name: 'OPENSTACK_API_URL', value: OPENSTACK_API_URL],
                    [$class: 'StringParameterValue', name: 'OPENSTACK_API_CREDENTIALS', value: OPENSTACK_API_CREDENTIALS],
                    [$class: 'StringParameterValue', name: 'OPENSTACK_API_PROJECT', value: OPENSTACK_API_PROJECT],
                    [$class: 'StringParameterValue', name: 'OPENSTACK_API_PROJECT_DOMAIN', value: OPENSTACK_API_PROJECT_DOMAIN],
                    [$class: 'StringParameterValue', name: 'OPENSTACK_API_PROJECT_ID', value: OPENSTACK_API_PROJECT_ID],
                    [$class: 'StringParameterValue', name: 'OPENSTACK_API_USER_DOMAIN', value: OPENSTACK_API_USER_DOMAIN],
                    [$class: 'StringParameterValue', name: 'OPENSTACK_API_CLIENT', value: OPENSTACK_API_CLIENT],
                    [$class: 'StringParameterValue', name: 'OPENSTACK_API_VERSION', value: OPENSTACK_API_VERSION]
                ])
            }
        }
    }
}
