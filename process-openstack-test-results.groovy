/**
 *
 * Pipeline for test results processing.
 * Pipeline stages:
 *  - Downloading of tests results from specified job.
 *  - Upload results to testrail (Optional).
 *  - Analysing results and making a decision about build success.
 *
 * Expected parameters:
 *   FAIL_ON_TESTS                Whether to fail build on tests failures or not
 *   OPENSTACK_VERSION            Version of Openstack being tested
 *   TARGET_JOB                   Name of the testing job
 *   TARGET_BUILD_NUMBER          Number of the testing build
 *   TEST_REPORTER_IMAGE          Docker image for testrail reporter
 *   TESTRAIL                     Whether upload results to testrail or not
 *   TEST_MILESTONE               Product version for tests
 *   TEST_MODEL                   Salt model used in environment
 *   TEST_SUITE                   Testrail test suite
 *   TEST_PLAN                    Testrail test plan
 *   TEST_GROUP                   Testrail test group
 *   TESTRAIL_QA_CREDENTIALS      Credentials for upload to testrail
 *   TEST_DATE                    Date of test run
 *   TEST_PASS_THRESHOLD          Persent of passed tests to consider build successful
 *
 */

common = new com.mirantis.mk.Common()
test = new com.mirantis.mk.Test()

node('docker') {
    try {
        testOutputDir = sh(script: 'mktemp -d', returnStdout: true).trim()

        //TODO: Implement support for stepler run artifacts
        stage('Get tests artifacts') {
            selector = [$class: 'SpecificBuildSelector', buildNumber: "${TARGET_BUILD_NUMBER}"]

            step ([$class: 'CopyArtifact',
                   projectName: TARGET_JOB,
                   selector: selector,
                   filter: '_artifacts/rally_reports.tar',
                   target: testOutputDir,
                   flatten: true,])

            dir(testOutputDir) {
                sh('tar -xf rally_reports.tar')
            }
        }

        report = sh(script: "find ${testOutputDir} -name *.xml", returnStdout: true).trim()

        if (TESTRAIL.toBoolean() == true) {
            stage('Upload tests results to Testrail'){

                def plan = TEST_PLAN ?: "${TEST_MILESTONE}-OSCORE-${TEST_DATE}"
                def group = TEST_GROUP ?: "${TEST_MODEL}-${OPENSTACK_VERSION}-nightly"

                //ensures that we have up to date image on jenkins slave
                sh("docker pull ${TEST_REPORTER_IMAGE}")

                test.uploadResultsTestrail(report, TEST_REPORTER_IMAGE, group, TESTRAIL_QA_CREDENTIALS,
                    plan, TEST_MILESTONE, TEST_SUITE)
            }
        }

        //TODO: use xunit publisher plugin to publish results
        stage('Check tests results'){
            fileContents = new File(report)
            parsed = new XmlParser().parse(fileContents)
            res = parsed['testsuite'][0].attributes()

            failed = res.failures.toInteger()
            tests = res.tests.toInteger()
            skipped = res.skipped.toInteger()
            passed = tests - failed - skipped

            test_info = """
                        Failed:  ${res.failures}
                        Errors:  ${res.errors}
                        Skipped: ${res.skipped}
                        Tests:   ${res.tests}
                        """
            println test_info

            pr = (passed / tests) * 100

            if (pr < TEST_PASS_THRESHOLD.toInteger() && FAIL_ON_TESTS){
                error("${failed} TEMPEST TESTS HAVE FAILED")
            }
        }
    } catch (Exception e) {
        currentBuild.result = 'FAILURE'
        throw e
    } finally {
        dir(testOutputDir) {
          deleteDir()
        }
    }
}
