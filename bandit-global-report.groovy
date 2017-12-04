def common = new com.mirantis.mk.Common()

project_list = ['nova', 'cinder', 'keystone', 'keystonemiddleware', 'keystoneauth',
                'glance', 'glance_store', 'neutron', 'heat', 'barbican', 'designate',
                'octavia', 'castellan', 'python-keystoneclient', 'python-openstackclient',
                'oslo.utils', 'oslo.config', 'oslo.log', 'oslo.service', 'oslo.messaging',
                'python-novaclient', 'python-neutronclient', 'horizon', 'python-glanceclient',
                'python-cinderclient', 'python-heatclient', 'python-ironicclient',
                'python-barbicanclient', 'python-designateclient', 'python-monascaclient'
                ]

def project_bandit_test = [:]
def builds = [:]
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
                [$class: 'StringParameterValue', name: 'GERRIT_USER', value: GERRIT_USER],
                [$class: 'StringParameterValue', name: 'CREDENTIALS_ID', value: CREDENTIALS_ID],
                [$class: 'BooleanParameterValue', name: 'FAIL_ON_TESTS', value: false],
                [$class: 'StringParameterValue', name: 'GERRIT_PROJECT_URL', value: "${GERRIT_URL}/${project_name}"],
            ]
        }
    }
}

stage('Running parallel Bandit tests') {
    parallel project_bandit_test
}

node('python') {
    def reports_dir = "${env.WORKSPACE}/reports/"
    def artifacts_dir = '_artifacts/'
    def output = 'reports.tar'
    def version = GERRIT_BRANCH.replaceAll('/','_')
    sh("rm -rf ${reports_dir} && mkdir -p ${reports_dir}")

    stage('Getting test reports') {
        for (k in builds.keySet()){
            def number = builds[k].number.toString()
            def selector = [$class: 'SpecificBuildSelector', buildNumber: "${number}"]
            if(builds[k].result == 'SUCCESS') {
                step ([$class: 'CopyArtifact',
                       projectName: "oscore-bandit-${TYPE}-${project_name}",
                       selector: selector,
                       filter: "_artifacts/report-${k}.${REPORT_FORMAT}",
                       target: "${reports_dir}",
                       flatten: true,])
            }
        }
    }

    if (REPORT_FORMAT == 'csv'){
        stage('Creating global report') {
            def global_list = []
            dir("${reports_dir}") {
                def report_list = sh(script: 'ls *.csv', returnStdout: true).tokenize('\n')
                for (f in report_list){
                    def title = f-'report-'-'.csv'
                    def r = readFile(f)
                    if (r.tokenize('\n').size() == 1) {
                        global_list.add(title+'\n'+'no issues found\n')
                    } else {
                        global_list.add(title+'\n'+r)
                    }
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