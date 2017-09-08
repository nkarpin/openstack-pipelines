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
 *   EXTRA_REPO                        Repository with additional packages
 *   EXTRA_REPO_PIN                    Pin string for extra repo - eg "origin hostname.local"
 *   EXTRA_REPO_PRIORITY               Repo priority
 *   GERRIT_PROJECT                    Project being tested by gerrit trigger
 *   REVIEW_NAMESPACE                  Path to Gerrit review artifacts
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
def artifactoryServer = Artifactory.server('mcp-ci')
def artifactoryUrl = artifactoryServer.getUrl()

node('python') {
    try {
        if (GERRIT_PROJECT) {
            project = "${GERRIT_PROJECT}".tokenize('/')[2]
            pkgReviewNameSpace = REVIEW_NAMESPACE ?: "binary-dev-local/pkg-review/${GERRIT_CHANGE_NUMBER}"
            //currently artifactory CR repositories  aren't signed - related bug PROD-14585
            extra_repo = "deb [ arch=amd64 trusted=yes ] ${artifactoryUrl}/${pkgReviewNameSpace} /"
            testrail = false
        } else {
            //TODO: in case of not Gerrit triggered build - run previous build cleanup
            project = OPENSTACK_COMPONENT
            extra_repo = EXTRA_REPO
            testrail = true
        }

        // Choose tests set to run
        if (TEST_TEMPEST_PATTERN) {
            test_tempest_pattern = TEST_TEMPEST_PATTERN
        } else {
            pattern_file = "${env.JENKINS_HOME}/workspace/${env.JOB_NAME}@script/project_tests.yaml"
            common.infoMsg("Reading test patterns from ${pattern_file}")
            pattern_map = readYaml file: "${pattern_file}"

            // by default try to read patterns from file
            if (pattern_map.containsKey(project)) {
               test_tempest_pattern = pattern_map[project]
            } else {
                common.infoMsg("Project ${project} not found in test patterns file, only smoke tests will be launched")
            }
        }

        salt_overrides_list = SALT_OVERRIDES.tokenize('\n')

        // Setting extra repo
        if (extra_repo) {
            // by default pin to fqdn of extra repo host
            extra_repo_pin = EXTRA_REPO_PIN ?: "origin ${extra_repo.tokenize('/')[1]}"
            extra_repo_priority = EXTRA_REPO_PRIORITY ?: '1200'
            extra_repo_params = ["linux_system_repo: ${extra_repo}",
                                 "linux_system_repo_priority: ${extra_repo_priority}",
                                 "linux_system_repo_pin: ${extra_repo_pin}",]
            for (item in extra_repo_params) {
               salt_overrides_list.add(item)
            }
        }

        if (salt_overrides_list) {
            salt_overrides = salt_overrides_list.join('\n')
            common.infoMsg("Next salt model parameters will be overriden:\n${salt_overrides}")
        }

        stack_deploy_job = "deploy-${STACK_TYPE}-${TEST_MODEL}-systest"

        // Deploy MCP environment
        stage('Trigger deploy job') {
            deployBuild = build(job: stack_deploy_job, parameters: [
                [$class: 'StringParameterValue', name: 'OPENSTACK_API_PROJECT', value: OPENSTACK_API_PROJECT],
                [$class: 'StringParameterValue', name: 'HEAT_STACK_ZONE', value: HEAT_STACK_ZONE],
                [$class: 'StringParameterValue', name: 'STACK_INSTALL', value: STACK_INSTALL],
                [$class: 'StringParameterValue', name: 'STACK_TEST', value: ''],
                [$class: 'StringParameterValue', name: 'STACK_TYPE', value: STACK_TYPE],
                [$class: 'BooleanParameterValue', name: 'STACK_DELETE', value: false],
                [$class: 'TextParameterValue', name: 'SALT_OVERRIDES', value: salt_overrides],
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
                [$class: 'BooleanParameterValue', name: 'FAIL_ON_TESTS', value: true],
            ])
        }

        // Perform component specific tests
        if (test_tempest_pattern) {
            stage("Run ${project} tests") {
                build(job: STACK_TEST_JOB, parameters: [
                    [$class: 'StringParameterValue', name: 'SALT_MASTER_URL', value: SALT_MASTER_URL],
                    [$class: 'StringParameterValue', name: 'TEST_TEMPEST_TARGET', value: TEST_TEMPEST_TARGET],
                    [$class: 'StringParameterValue', name: 'TEST_TEMPEST_PATTERN', value: test_tempest_pattern],
                    [$class: 'StringParameterValue', name: 'TEST_MILESTONE', value: TEST_MILESTONE],
                    [$class: 'StringParameterValue', name: 'TEST_MODEL', value: TEST_MODEL],
                    [$class: 'StringParameterValue', name: 'OPENSTACK_VERSION', value: OPENSTACK_VERSION],
                    [$class: 'BooleanParameterValue', name: 'TESTRAIL', value: testrail.toBoolean()],
                    [$class: 'StringParameterValue', name: 'OPENSTACK_COMPONENT', value: project],
                    [$class: 'StringParameterValue', name: 'TEST_PASS_THRESHOLD', value: TEST_PASS_THRESHOLD],
                    [$class: 'BooleanParameterValue', name: 'FAIL_ON_TESTS', value: true],
                ])
            }
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
                    [$class: 'StringParameterValue', name: 'OPENSTACK_API_VERSION', value: OPENSTACK_API_VERSION],
                ])
            }
        }
    }
}
