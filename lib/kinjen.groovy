/**
 * Return the branch name that this branch will automatically merge into if any. Return null if branch is not an auotmerge branch.
 */
static extract_auto_merge_target( script )
{
  if ( script.env.BRANCH_NAME ==~ /^AM_.*/ )
  {
    return 'master'
  }
  else if ( script.env.BRANCH_NAME ==~ /^AM-([^_]+)_.*$/ )
  {
    return script.env.BRANCH_NAME.replaceFirst( /^AM-([^_]+)_.*$/, '$1' )
  }
  else
  {
    return ''
  }
}

/**
 * The standard prepare stage that cleans up repository and downloads/installs java/ruby dependencies.
 */
static prepare_stage( script, Map options = [:] )
{
  script.stage( 'Prepare' ) {
    script.sh 'git reset --hard'
    def clean_repository = options.clean == null ? true : options.clean
    if ( clean_repository )
    {
      script.sh 'git clean -ffdx'
    }
    def versions_envs = options.versions_envs == null ? true : options.versions_envs
    if ( versions_envs )
    {
      script.env.BUILD_NUMBER = "${script.env.BUILD_NUMBER}"
      script.env.PRODUCT_VERSION =
        script.sh( script: 'echo $BUILD_NUMBER-`git rev-parse --short HEAD`', returnStdout: true ).trim()
    }
    def include_ruby = options.ruby == null ? true : options.ruby
    if ( include_ruby )
    {
      script.sh 'echo "gem: --no-ri --no-rdoc" > ~/.gemrc'
      script.retry( 8 ) { script.sh 'bundle install; rbenv rehash' }
      def include_buildr = options.buildr == null ? true : options.buildr
      if ( include_buildr )
      {
        script.retry( 8 ) { script.sh 'bundle exec buildr artifacts' }
      }
    }
  }
}

/**
 * The commit stage that runs buildr pre-commit task 'ci:commit' and collects reports.
 */
static commit_stage( script, project_key, Map options = [:] )
{
  script.stage( 'Commit' ) {

    script.sh 'xvfb-run -a bundle exec buildr ci:commit'
    def analysis = false
    def include_checkstyle = options.checkstyle == null ? false : options.checkstyle
    if ( include_checkstyle )
    {
      analysis = true
      script.step( [$class          : 'hudson.plugins.checkstyle.CheckStylePublisher',
                    pattern         : "reports/${project_key}/checkstyle/checkstyle.xml",
                    unstableTotalAll: '1',
                    failedTotalAll  : '1'] )
      script.publishHTML( target: [allowMissing         : false,
                                   alwaysLinkToLastBuild: false,
                                   keepAll              : true,
                                   reportDir            : "reports/${project_key}/checkstyle",
                                   reportFiles          : 'checkstyle.html',
                                   reportName           : 'Checkstyle issues'] )
    }
    def include_findbugs = options.findbugs == null ? false : options.findbugs
    if ( include_findbugs )
    {
      analysis = true
      script.step( [$class             : 'FindBugsPublisher',
                    pattern            : "reports/${project_key}/findbugs/findbugs.xml",
                    unstableTotalAll   : '1',
                    failedTotalAll     : '1',
                    isRankActivated    : true,
                    canComputeNew      : true,
                    shouldDetectModules: false,
                    useDeltaValues     : false,
                    canRunOnFailed     : false,
                    thresholdLimit     : 'low'] )
      script.publishHTML( target: [allowMissing         : false,
                                   alwaysLinkToLastBuild: false,
                                   keepAll              : true,
                                   reportDir            : "reports/${project_key}/findbugs",
                                   reportFiles          : 'findbugs.html',
                                   reportName           : 'Findbugs issues'] )
    }
    def include_pmd = options.pmd == null ? false : options.pmd
    if ( include_pmd )
    {
      analysis = true
      script.step( [$class          : 'PmdPublisher',
                    pattern         : "reports/${project_key}/pmd/pmd.xml",
                    unstableTotalAll: '1',
                    failedTotalAll  : '1'] )
      script.publishHTML( target: [allowMissing         : false,
                                   alwaysLinkToLastBuild: false,
                                   keepAll              : true,
                                   reportDir            : "reports/${project_key}/pmd",
                                   reportFiles          : 'pmd.html',
                                   reportName           : 'PMD Issues'] )
    }
    def include_jdepend = options.jdepend == null ? false : options.jdepend
    if ( include_jdepend )
    {
      script.publishHTML( target: [allowMissing         : false,
                                   alwaysLinkToLastBuild: false,
                                   keepAll              : true,
                                   reportDir            : "reports/${project_key}/jdepend",
                                   reportFiles          : 'jdepend.html',
                                   reportName           : 'JDepend Report'] )
    }
    if ( analysis )
    {
      script.step( [$class: 'AnalysisPublisher', unstableTotalAll: '1', failedTotalAll: '1'] )
    }

    if ( script.currentBuild.result != 'SUCCESS' )
    {
      script.error( 'Build failed commit stage' )
    }
  }
}

/**
 * The package stage that runs buildr task 'ci:package' and collects reports.
 */
static package_stage( script, Map options = [:] )
{
  script.stage( 'Package' ) {
    script.sh 'xvfb-run -a bundle exec buildr ci:package'
    def include_junit = options.junit == null ? false : options.junit
    if ( include_junit )
    {
      script.step( [$class: 'JUnitResultArchiver', testResults: 'reports/**/TEST-*.xml'] )
    }
    def include_testng = options.testng == null ? false : options.testng
    if ( include_testng )
    {
      script.step( [$class                   : 'hudson.plugins.testng.Publisher',
                    reportFilenamePattern    : 'reports/*/testng/testng-results.xml',
                    failureOnFailedTestConfig: true,
                    unstableFails            : 0,
                    unstableSkips            : 0] )
    }
  }
}

/**
 * The pg package stage that runs buildr task 'ci:package_no_test' building for postgres artifacts.
 * No tests are run under the naive assumption that the sql server variant tests required functionality.
 */
static pg_package_stage( script )
{
  script.stage( 'Pg Package' ) {
    script.sh 'xvfb-run -a bundle exec buildr clean; export DB_TYPE=pg; xvfb-run -a bundle exec buildr ci:package_no_test'
  }
}

/**
 * The basic database import task.
 */
static import_stage( script )
{
  script.stage( 'DB Import' ) {
    script.sh 'xvfb-run -a bundle exec buildr ci:import'
  }
}

/**
 * A database import task testing an import variant.
 */
static import_variant_stage( script, variant )
{
  script.stage( "DB ${variant} Import" ) {
    script.sh "xvfb-run -a bundle exec buildr ci:import:${variant}"
  }
}

/**
 * A task that triggers the zimming out of dependencies to downstream projects.
 */
static zim_stage( script, dependencies )
{
  script.stage( 'Zim' ) {
    script.build job: 'zim/upgrade_dependency',
                 parameters: [script.string( name: 'DEPENDENCIES', value: dependencies ),
                              script.string( name: 'VERSION', value: "${script.env.PRODUCT_VERSION}" )],
                 wait: false
  }
}

static publish_stage( script, upload_prefix = '' )
{
  script.stage( 'Publish' ) {
    script.sh """export DOWNLOAD_REPO=${script.env.UPLOAD_REPO}
export UPLOAD_REPO=${script.env."EXTERNAL_${upload_prefix}UPLOAD_REPO"}
export UPLOAD_USER=${script.env."EXTERNAL_${upload_prefix}UPLOAD_USER"}
export UPLOAD_PASSWORD=${script.env."EXTERNAL_${upload_prefix}UPLOAD_PASSWORD"}
export PUBLISH_VERSION=${script.PUBLISH_VERSION}
bundle exec buildr ci:publish"""
  }
}

static deploy_stage( script, project_key )
{
  script.stage( 'Deploy' ) {
    script.build job: "${project_key}/deploy-to-development",
                 parameters: [script.string( name: 'PRODUCT_ENVIRONMENT', value: 'development' ),
                              script.string( name: 'PRODUCT_NAME', value: project_key ),
                              script.string( name: 'PRODUCT_VERSION', value: "${script.env.PRODUCT_VERSION}" )],
                 wait: false
  }
}

static guard_build( script, Map options = [:], actions )
{
  def notify_github = options.notify_github == null ? true : options.notify_github
  def build_context = options.build_context == null ? 'jenkins' : options.build_context
  def email = options.email == null ? true : options.email
  def err = null

  try
  {
    script.currentBuild.result = 'SUCCESS'
    if ( notify_github )
    {
      script.step( [$class: 'GitHubCommitStatusSetter', contextSource: [$class: 'ManuallyEnteredCommitContextSource', context: build_context], statusResultSource: [$class: 'ConditionalStatusResultSource', results: [[$class: 'AnyBuildResult', message: 'Building in jenkins', state: 'PENDING']]]])
    }

    actions()
  }
  catch ( exception )
  {
    script.currentBuild.result = "FAILURE"
    err = exception
  }
  finally
  {
    if ( notify_github )
    {
      if ( script.currentBuild.result == 'SUCCESS' )
      {
        script.step( [$class: 'GitHubCommitStatusSetter', contextSource: [$class: 'ManuallyEnteredCommitContextSource', context: build_context], statusResultSource: [$class: 'ConditionalStatusResultSource', results: [[$class: 'AnyBuildResult', message: 'Successfully built', state: 'SUCCESS']]]])
      }
      else
      {
        script.step( [$class: 'GitHubCommitStatusSetter', contextSource: [$class: 'ManuallyEnteredCommitContextSource', context: build_context], statusResultSource: [$class: 'ConditionalStatusResultSource', results: [[$class: 'AnyBuildResult', message: 'Failed to build', state: 'FAILURE']]]])
      }
    }

    if ( email )
    {
      send_notifications( script )
    }
    if ( err )
    {
      throw err
    }
  }
}

/**
 * Run the closure in a docker container with specified image and named appropriately.
 */
static run_in_container( script, image_name, actions )
{
  def name = "${script.env.JOB_NAME.replaceAll( /[\\\\/-]/, '_' ).replaceAll( '%2F', '_' )}_${script.env.BUILD_NUMBER}"
  script.docker.image( image_name ).inside( "--name '${name}'", actions )
}

static setup_git_config( script )
{
  script.sh( "git config --global user.email \"${script.env.BUILD_NOTIFICATION_EMAIL}\"" )
  script.sh( 'git config --global user.name "Build Tool"' )
}

static prepare_auto_merge( script, target_branch )
{
  script.env.LOCAL_GIT_COMMIT = script.sh( script: 'git rev-parse HEAD', returnStdout: true ).trim()
  script.env.LOCAL_MASTER_GIT_COMMIT =
    script.sh( script: "git show-ref --hash refs/remotes/origin/${target_branch}", returnStdout: true ).trim()
  script.echo "Automerge branch ${script.env.BRANCH_NAME} detected. Merging ${target_branch}."
  setup_git_config( script )
  script.sh( "git merge origin/${target_branch}" )
}

static complete_auto_merge( script, target_branch )
{
  setup_git_credentials( script )
  script.sh( "git fetch --prune" )
  script.env.LATEST_REMOTE_MASTER_GIT_COMMIT =
    script.sh( script: "git show-ref --hash refs/remotes/origin/${target_branch}", returnStdout: true ).trim()
  script.env.LATEST_REMOTE_GIT_COMMIT =
    script.sh( script: "git show-ref --hash refs/remotes/origin/${script.env.BRANCH_NAME}", returnStdout: true ).trim()
  if ( script.env.LOCAL_MASTER_GIT_COMMIT != script.env.LATEST_REMOTE_MASTER_GIT_COMMIT )
  {
    if ( script.env.LOCAL_GIT_COMMIT == script.env.LATEST_REMOTE_GIT_COMMIT )
    {
      script.echo( "Merging changes from ${target_branch} to kick off another build cycle." )
      script.sh( "git merge origin/${target_branch}" )
      script.echo( 'Changes merged.' )
      script.sh( "git push origin HEAD:${script.env.BRANCH_NAME}" )
      script.sh( "git checkout ${script.env.LOCAL_GIT_COMMIT}" )
    }
  }
  else
  {
    script.echo "Pushing automerge branch ${script.env.BRANCH_NAME}."
    if ( script.env.LOCAL_GIT_COMMIT == script.env.LATEST_REMOTE_GIT_COMMIT )
    {
      // Add sleep so github will have enough time to register the commit status
      script.sleep 10
      script.sh( "git push origin HEAD:${target_branch}" )
      script.sh( "git push origin :${script.env.BRANCH_NAME}" )
    }
  }
}

static setup_git_credentials( script, Map options = [:] )
{
  def username = options.username == null ? 'stock-hudson' : options.username

  script.withCredentials( [[$class          : 'UsernamePasswordMultiBinding',
                            credentialsId   : username,
                            usernameVariable: 'GIT_USERNAME',
                            passwordVariable: 'GIT_PASSWORD']] ) {
    script.sh "echo \"machine github.com login ${script.GIT_USERNAME} password ${script.GIT_PASSWORD}\" > ~/.netrc"
  }
}

static send_notifications( script )
{
  if ( script.currentBuild.result == 'SUCCESS' &&
       script.currentBuild.rawBuild.previousBuild != null &&
       script.currentBuild.rawBuild.previousBuild.result.toString() != 'SUCCESS' )
  {
    script.echo "Emailing SUCCESS notification to ${script.env.BUILD_NOTIFICATION_EMAIL}"

    script.emailext body: "<p>Check console output at <a href=\"${script.env.BUILD_URL}\">${script.env.BUILD_URL}</a> to view the results.</p>",
                    mimeType: 'text/html',
                    replyTo: "${script.env.BUILD_NOTIFICATION_EMAIL}",
                    subject: "\ud83d\udc4d ${script.env.JOB_NAME.replaceAll( '%2F', '/' )} - #${script.env.BUILD_NUMBER} - SUCCESS",
                    to: "${script.env.BUILD_NOTIFICATION_EMAIL}"
  }

  if ( script.currentBuild.result != 'SUCCESS' )
  {
    def emailBody = """
<title>${script.env.JOB_NAME.replaceAll( '%2F', '/' )} - #${script.env.BUILD_NUMBER} - ${script.currentBuild.result}</title>
<BODY>
    <div style="font:normal normal 100% Georgia, Serif; background: #ffffff; border: dotted 1px #666; margin: 2px; content: 2px; padding: 2px;">
      <table style="width: 100%">
        <tr style="background-color:#f0f0f0;">
          <th colspan=2 valign="center"><b style="font-size: 200%;">BUILD ${script.currentBuild.result}</b></th>
        </tr>
        <tr>
          <th align="right"><b>Build URL</b></th>
          <td>
            <a href="${script.env.BUILD_URL}">${script.env.BUILD_URL}</a>
          </td>
        </tr>
        <tr>
          <th align="right"><b>Job</b></th>
          <td>${script.env.JOB_NAME.replaceAll( '%2F', '/' )}</td>
        </tr>
        <tr>
          <td align="right"><b>Build Number</b></td>
          <td>${script.env.BUILD_NUMBER}</td>
        </tr>
        <tr>
          <td align="right"><b>Branch</b></td>
          <td>${script.env.BRANCH_NAME}</td>
        </tr>
 """
    if ( null != script.env.CHANGE_ID )
    {
      emailBody += """
       <tr>
          <td align="right"><b>Change</b></td>
          <td><a href="${script.env.CHANGE_URL}">${script.env.CHANGE_ID} - ${script.env.CHANGE_TITLE}</a></td>
        </tr>
"""
    }

    emailBody += """
      </table>
    </div>

    <div style="background: lightyellow; border: dotted 1px #666; margin: 2px; content: 2px; padding: 2px;">
"""
    for ( String line : script.currentBuild.rawBuild.getLog( 200 ) )
    {
      emailBody += "${line}<br/>"
    }
    emailBody += """
    </div>
</BODY>
"""
    script.echo "Emailing FAILED notification to ${script.env.BUILD_NOTIFICATION_EMAIL}"
    script.emailext body: emailBody,
                    mimeType: 'text/html',
                    replyTo: "${script.env.BUILD_NOTIFICATION_EMAIL}",
                    subject: "\ud83d\udca3 ${script.env.JOB_NAME.replaceAll( '%2F', '/' )} - #${script.env.BUILD_NUMBER} - FAILED",
                    to: "${script.env.BUILD_NOTIFICATION_EMAIL}"
  }
}

return this
