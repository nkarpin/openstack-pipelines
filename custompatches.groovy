#!/groovy
/**
 *
 * Find custom patches and upload them to review.
 *
 * Expected parameters:
 *   GERRIT_URI                 Link to the project on gerrit
 *   GERRIT_CREDENTIALS         Name of creadentials to use when connecting to gerrit
 *   TARGET_GERRIT_URI          Link to the target on gerrit, if not set GERRIT_URI is picked
 *   OLD_BRANCH                 Old branch on GERRIT_URI to take patches from, tupically previous release
 *   NEW_BRANCH                 New branch to compare with and push patches to, typically current release
 *   DRY_RUN                    Do not upload custom patches on review, just log them
 *
 */

common = new com.mirantis.mk.Common()
git = new com.mirantis.mk.Git()

// TODO: move to pipeline library
/**
 * Returns information about commit in the format of LinkedHashMap where keys are Change-Ids,
 * and values are commit Ids.
 *
 * @param repo            Directory with repo
 * @param start           commit_id to start looking from
 * @param end             commit_id/branch to looking to
 */
def getCommitsInfo(repo, start, end){
    // LinkedHashMap this linked list defines the iteration ordering, which is normally
    // the order in which keys were inserted into the map (insertion-order).
    def res = [:]
    def commits_info
    def matcher
    dir(repo){
        commits_info = sh(script: "git log --no-merges --reverse ${start}..${end}", returnStdout: true)
    }
    matcher = commits_info =~ /(?m)^commit ([a-f0-9]{40})$|^    Change-Id: (I[a-f0-9]{40})$/

    while (matcher.find()) {
        if (matcher.group(1) != null) {
            commit_id = matcher.group(1)
        } else if (matcher.group(2) != null ) {
            change_id = matcher.group(2)
            res.put(change_id, commit_id)
        }
    }

    return res
}

// TODO: move to pipeline library
/**
 * Returns information about commit in the format of LinkedHashMap where keys are commit IDs,
 * and values are commit Change-Ids.
 *
 * @param repo            Directory with repo
 * @param oldBranch      branch compare from
 * @param newBranch      branch compare with
 */
def getCustomPatches(repo, oldBranch, newBranch){
    def common_ancestor

    dir(repo){
        common_ancestor = sh(script: "git merge-base ${oldBranch} ${newBranch}", returnStdout: true).trim()
    }

    def old_commits = getCommitsInfo(repo, common_ancestor, oldBranch)
    def new_commits = getCommitsInfo(repo, common_ancestor, newBranch)

    new_commits.keySet().removeAll(old_commits.keySet())

    return new_commits
}


// TODO: move to pipeline library
/**
 * Returns information about specified commit
 *
 * @param repo            Directory with repo
 * @param ref             Commit_id to return information about
 */
def getCommitInfo(repo, ref = 'HEAD'){
    def res
    dir(repo){
        res = sh(script: "git show --oneline ${ref}", returnStdout: true).split('\n')[0].trim()
    }

    return res
}

// TODO: move to pipeline library
/**
 * Adds remote to project
 *
 * @param repo            Directory with repo
 * @param remoteName     Name of the remote to add
 * @param remoteUri      IRI of the remote to add
 */
def addGitRemote(repo, remoteName, remoteUri, credentialsId=null){
    def remote_update_cmd = 'git remote update'
    dir(repo){
        if (0 != sh(script: "git remote -v | grep ${remoteName}", returnStatus: true)){
            sh(script: "git remote add ${remoteName} ${remoteUri}", returnStdout: true)
        }
        if (credentialsId == null){
            sh(script: remote_update_cmd)
        } else {
            def ssh = new com.mirantis.mk.Ssh()
            ssh.prepareSshAgentKey(credentialsId)
            ssh.runSshAgentCommand(remote_update_cmd)
        }
    }
}

// TODO: move to pipeline library
def runSshAgentCommandStatus(cmd) {
    // if file exists, then we started ssh-agent
    def res = [:]
    def res_cmd
    def stderr = sh(script: 'mktemp', returnStdout: true)
    def stdout = sh(script: 'mktemp', returnStdout: true)

    try{
        if (fileExists("$HOME/.ssh/ssh-agent.sh")) {
            res_cmd = ". ~/.ssh/ssh-agent.sh && ${cmd} 1>${stdout} 2>${stderr}"
        } else {
        // we didn't start ssh-agent in prepareSshAgentKey() because some ssh-agent
        // is running. Let's re-use already running agent and re-construct
        //   * SSH_AUTH_SOCK
        //   * SSH_AGENT_PID
            res_cmd = """
            export SSH_AUTH_SOCK=`find /tmp/ -type s -name agent.\\* 2> /dev/null |  grep '/tmp/ssh-.*/agent.*' | head -n 1`
            export SSH_AGENT_PID=`echo \${SSH_AUTH_SOCK} | cut -d. -f2`
            ${cmd} 2>${stderr} 1>${stdout}"""
        }

        def status = sh(script: res_cmd, returnStatus: true)
        res['stderr'] = sh(script: "cat ${stderr}", returnStdout: true)
        res['stdout'] = sh(script: "cat ${stdout}", returnStdout: true)
        res['status'] = status
    } finally {
        sh(script: "rm ${stderr}", returnStdout: true)
        sh(script: "rm ${stdout}", returnStdout: true)
    }

    return res

}

// TODO: move to pipeline library
/**
 * Propose patch to review
 *
 * @param commit          Commit ID
 * @param branch          Name of the branch to propose change to
 * @param topic           Name of topic to use
 * @param credentialsId   Jenkins credentials to use
 */
def uploadPatchToReview(repo, commit, branch, topic=null, credentialsId=null){
    def common = new com.mirantis.mk.Common()
    common.infoMsg("Uploading patch ${commit} to review...")
    def pusharg = "${commit}:refs/for/${branch}"
    if (topic != null){
        pusharg += "%topic=${topic}"
    }
    def push_cmd = "git push target ${pusharg}"
    dir(repo){
        if (credentialsId == null){
                sh(script: push_cmd)
            } else {
                def ssh = new com.mirantis.mk.Ssh()
                ssh.prepareSshAgentKey(credentialsId)
                //ssh.runSshAgentCommand(push_cmd)
                def out = runSshAgentCommandStatus(push_cmd)
                if (out['status'] != 0){
                    if ((out['stderr'] =~ /(?m).*no new changes.*/).asBoolean()){
                        common.infoMsg("No new changes in ${commit}, skipping...")
                    } else if ((out['stderr'] ==~ /(?m).*change \d+ closed.*/).asBoolean()){
                        common.infoMsg("Change ${commit} is closed in Gerrit, skipping it...")
                    }
                }
            }
    }
}

node('python') {
    def repo = sh(script: "basename ${GERRIT_URI}", returnStdout: true).split('.git')[0].trim()

    if (!common.validInputParam('TARGET_GERRIT_URI')) {
        TARGET_GERRIT_URI = GERRIT_URI
    }
    stage ('Checkouting repository...'){
        git.checkoutGitRepository(repo, GERRIT_URI, 'master', GERRIT_CREDENTIALS)
        addGitRemote(repo, 'target', TARGET_GERRIT_URI, GERRIT_CREDENTIALS)
    }

    stage ('Processing custom patches'){
        def custom_patches = getCustomPatches(repo, "origin/${OLD_BRANCH}", "target/${NEW_BRANCH}")
        def custom_commits_info=[]

        for (k in custom_patches.keySet()){
            def v = custom_patches[k]

            if (common.validInputParam('DRY_RUN') && ! DRY_RUN.toBoolean()){
                common.infoMsg("Uploading patch ${v} to review")
                dir(repo){
                    sh(script: "git checkout ${v}")
                    sh(script: 'git commit --amend --no-edit')
                }
                def topic = "custom/patches/${newBranch}"
                uploadPatchToReview(repo, v, NEW_BRANCH, topic, GERRIT_CREDENTIALS)
            }
            custom_commits_info.add(getCommitInfo(repo, v))
        }

        if (custom_commits_info) {
            common.infoMsg("The custom patches between ${OLD_BRANCH} and ${NEW_BRANCH} are:")
            common.infoMsg(custom_commits_info.join('\n'))
        } else {
            common.infoMsg("No custom patches between ${OLD_BRANCH} and ${NEW_BRANCH} found.")
        }
    }
}
