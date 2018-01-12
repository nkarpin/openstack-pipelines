def common = new com.mirantis.mk.Common()

project_list = ['barbican', 'castellan', 'cinder', 'designate', 'glance', 'glance_store',
                'heat', 'horizon', 'ironic', 'keystone', 'keystoneauth', 'keystonemiddleware',
                'neutron', 'neutron-fwaas', 'neutron-lbaas', 'neutron-lib', 'neutron-vpnaas',
                'nova', 'octavia', 'oslo.cache', 'oslo.concurrency', 'oslo.config', 'oslo.context',
                'oslo.db', 'oslo.i18n', 'oslo.log', 'oslo.messaging', 'oslo.middleware', 'oslo.policy',
                'oslo.privsep', 'oslo.rootwrap', 'oslo.serialization', 'oslo.service', 'oslo.utils',
                'oslo.versionedobjects', 'python-barbicanclient', 'python-ceilometerclient',
                'python-cinderclient', 'python-designateclient', 'python-glanceclient', 'python-heatclient',
                'python-ironicclient', 'python-keystoneclient', 'python-monascaclient', 'python-neutronclient',
                'python-novaclient', 'python-octaviaclient', 'python-openstackclient', 'python-swiftclient']

def run_parallel_scans(project_list=[], builds=[:]) {
    def project_bandit_test = [:]
    for (int i = 0; i < project_list.size(); i++) {
        def project_name = project_list[i]
        project_bandit_test["Bandit test: ${project_name}"] = {
            node('python'){
                //url = "ssh://oscc-ci@review.fuel-infra.org:29418/openstack/${project_name}"
                builds["${project_name}"] = build job: "oscore-bandit-${TYPE}-${project_name}", propagate: false, parameters: [
                    [$class: 'StringParameterValue', name: 'SEVERITY', value: SEVERITY],
                    [$class: 'StringParameterValue', name: 'CONFIDENCE', value: CONFIDENCE],
                    [$class: 'StringParameterValue', name: 'REPORT_FORMAT', value: REPORT_FORMAT],
                    [$class: 'StringParameterValue', name: 'GERRIT_BRANCH', value: GERRIT_BRANCH],
                    [$class: 'StringParameterValue', name: 'CREDENTIALS_ID', value: CREDENTIALS_ID],
                    [$class: 'BooleanParameterValue', name: 'FAIL_ON_TESTS', value: false],
                    [$class: 'BooleanParameterValue', name: 'UPLOAD_REPORT', value: UPLOAD_REPORT.toBoolean()],
                    [$class: 'StringParameterValue', name: 'GERRIT_PROJECT_URL', value: "${GERRIT_URL}${project_name}"],
                ]
            }
        }
    }
    parallel project_bandit_test
}

def concurrency = REPORT_CONCURRENCY.toInteger()
def builds = [:]

stage('Running parallel Bandit tests') {

    int num = project_list.size()
    int mod = num % concurrency
    def start
    def end
    for (j = 0; j <= num - concurrency; j = j + concurrency) {
         start = j
         end = j + concurrency - 1
         println "${start},${end}"
         println project_list[start..end]
         run_parallel_scans(project_list[start..end], builds)
    }

    if (mod > 0) {
        start = num - mod
        end = num - 1
        println "${start},${end}"
        println project_list[start..end]
        run_parallel_scans(project_list[start..end], builds)
    }

}

node('python') {
    def reports_dir = "${env.WORKSPACE}/reports/"
    def artifacts_dir = '_artifacts/'
    def version = GERRIT_BRANCH.replaceAll('/','_')
    def output = "reports_${version}.tar"
    sh("rm -rf ${reports_dir} && mkdir -p ${reports_dir}")

    stage('Getting test reports') {
        for (k in builds.keySet()){
            def number = builds[k].number.toString()
            def selector = [$class: 'SpecificBuildSelector', buildNumber: "${number}"]
            if(builds[k].result == 'SUCCESS') {
                step ([$class: 'CopyArtifact',
                       projectName: "oscore-bandit-${TYPE}-${k}",
                       selector: selector,
                       filter: "_artifacts/report-${k}.*",
                       target: "${reports_dir}",
                       flatten: true,])
            }
        }
    }

    if (REPORT_FORMAT == 'json'){
        stage('Creating global report') {
            def global_list = []
            dir("${reports_dir}") {
                def report_list = sh(script: 'ls *.csv', returnStdout: true).tokenize('\n')
                for (f in report_list){
                    def title = f-'report-'-'.csv'
                    def r = readFile(f)
                    global_list.add(title+'\n'+r+'\n')
                }
            }
            writeFile file: "${reports_dir}/bandit_report_${version}.csv", text: global_list.join('\n')
        }
    }

    stage("Archieving reports"){
        sh("rm -rf ${artifacts_dir} && mkdir -p ${artifacts_dir}")
        sh("tar -cf ${artifacts_dir}${output} -C ${reports_dir} .")
        archiveArtifacts artifacts: "${artifacts_dir}${output}"
    }
}