#!/usr/bin/env bash
# Single CI entry point: GitLab, Jenkins, GitHub Actions, the container image of the build and a developer's shell all
# call this script and nothing else; .gitlab-ci.yml, Jenkinsfile and .github/workflows/ci.yml are thin wrappers. Needs
# bash, git and, for verify, a JDK 25 and either a filled ~/.m2 with the platform artifacts or MCP_MAVEN_REPOSITORY_URL
# (+ _USER and _PASSWORD on the main branch) pointing at the registry that holds them.
#
#   ci/run.sh                 verify (default)
#   ci/run.sh verify          hygiene checks, then mvnw verify (unit tests and the tests of the whole server against
#                             TestGateway and a stub backend); a build that ran no test fails. For a real system the
#                             registration manifest (mcp-app/target/mcp-manifest.json) must exist afterwards
#   ci/run.sh hygiene         only the text checks of the sources (non-ASCII quotes and spaces, tool traces)
#   ci/run.sh image           docker build of the jar that verify made; main branch only, needs a Docker daemon
#
# CI_TEST_MODULES (default: mcp-application mcp-integration mcp-tools mcp-app) lists the modules that must have run tests, CI_APP_MIN_TESTS the
# floor for mcp-app, CI_IMAGE_TAG_PREFIX (default m5) the prefix of the image tag mcp-server-<system>:<prefix>-<sha7>.
# Any other argument is an error (exit 2). The tests need no container runtime, so a merge request pipeline mounts no
# Docker socket and holds no secret: without the credentials of the registry the build reads it anonymously.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

# Maven settings file for the registry: with credentials only when the pipeline holds them (main), anonymous otherwise.
maven_settings() {
  if [[ -z "${MCP_MAVEN_REPOSITORY_URL:-}" ]]; then
    return 0
  fi
  if [[ -n "${MCP_MAVEN_REPOSITORY_USER:-}" && -n "${MCP_MAVEN_REPOSITORY_PASSWORD:-}" ]]; then
    echo "$root/.ci/settings.xml"
  else
    echo "$root/.ci/settings-anonymous.xml"
  fi
}

mvnw() {
  local settings=()
  if [[ -n "$(maven_settings)" ]]; then
    settings=(-s "$(maven_settings)")
  fi
  ./mvnw -B ${settings[@]+"${settings[@]}"} "$@"
}

tracked_sources() {
  git ls-files -z --cached --others --exclude-standard -- '*.java' '*.md' '*.xml' '*.yml' '*.yaml' '*.properties' '*.json' '*.sh' 'Jenkinsfile' 'Dockerfile' ':!:ci/run.sh'
}

# The two checks of the house style: characters that give away generated text, and phrases left by tools. One perl process
# reads every file by its literal name (<<>> and not the two-argument open of <>, which would run a name such as "x |") and
# prints what it finds; it always exits 0, so a non-zero status of git, xargs or perl is a failure of the check itself and
# never "nothing found".
hygiene() {
  local found status
  found="$(tracked_sources | xargs -0 perl -CSD -e '
    while (<<>>) {
      print "$ARGV:$.: non-ASCII quote, space or ellipsis: $_" if /[\x{00A0}\x{200B}\x{202F}\x{FEFF}\x{2018}\x{2019}\x{201C}\x{201D}\x{2026}]/;
      print "$ARGV:$.: tool trace or placeholder: $_" if /as an ai|generated with|co-authored-by: claude|lorem ipsum|your-api-key|exampl[e]\.com/i;
    } continue { close ARGV if eof }
    exit 0' --; printf 'status=%s\n' "${PIPESTATUS[0]}:${PIPESTATUS[1]}")"
  status="${found##*status=}"
  found="${found%status=*}"
  if [[ "$status" != "0:0" ]]; then
    echo "ci/run.sh: the hygiene check itself failed (status $status)" >&2
    return 1
  fi
  if [[ -n "$found" ]]; then
    printf '%s' "$found" >&2
    echo "ci/run.sh: hygiene findings (above)" >&2
    return 1
  fi
}

# A build that ran no test proves nothing (a skipped module, a broken surefire setup, every test skipped), so each module
# of CI_TEST_MODULES (default: the modules of this repository that have tests)
# must have run tests that were not skipped, and mcp-app at least CI_APP_MIN_TESTS of them (a floor, so that a test class
# that quietly stopped being found shows).
module_executed() {
  local module="$1" tests=0 skipped=0 t k file
  while IFS= read -r file; do
    t="$(sed -n 's/.*<testsuite [^>]*[ ]tests="\([0-9]*\)".*/\1/p' "$file" | head -n 1)"
    k="$(sed -n 's/.*<testsuite [^>]*[ ]skipped="\([0-9]*\)".*/\1/p' "$file" | head -n 1)"
    tests=$((tests + ${t:-0}))
    skipped=$((skipped + ${k:-0}))
  done < <(find "$module/target/surefire-reports" -name 'TEST-*.xml' 2>/dev/null)
  echo $((tests - skipped))
}

require_tests_ran() {
  local module executed floor
  for module in ${CI_TEST_MODULES:-mcp-application mcp-integration mcp-tools mcp-app}; do
    executed="$(module_executed "$module")"
    floor=1
    if [[ "$module" == "mcp-app" ]]; then
      floor="${CI_APP_MIN_TESTS:-20}"
    fi
    if [[ "$executed" -lt "$floor" || "$executed" -eq 0 ]]; then
      echo "ci/run.sh: $module ran $executed tests that were not skipped; at least $floor are required" >&2
      exit 1
    fi
    echo "ci/run.sh: $module: $executed tests ran"
  done
}

# A real system builds its manifest in the package phase (new-server.sh turns that on); the template and trial servers
# are outside the SystemId of the contracts and build none.
require_manifest() {
  if grep -q '<manifest.export.skip>false<' mcp-app/pom.xml && [[ ! -s mcp-app/target/mcp-manifest.json ]]; then
    echo "ci/run.sh: mcp-app/target/mcp-manifest.json was not written" >&2
    exit 1
  fi
}

verify() {
  if [[ -z "${JAVA_HOME:-}" ]] && ! command -v java >/dev/null; then
    echo "ci/run.sh: no JDK; set JAVA_HOME to a JDK 25 or use the maven:3.9.12-eclipse-temurin-25 image" >&2
    exit 1
  fi
  hygiene
  mvnw clean verify
  require_tests_ran
  require_manifest
}

image() {
  local branch="${CI_COMMIT_BRANCH:-${GITHUB_REF_NAME:-${BRANCH_NAME:-$(git rev-parse --abbrev-ref HEAD)}}}"
  if [[ "$branch" != "main" ]]; then
    echo "ci/run.sh image: only the main branch builds the image (branch: $branch)" >&2
    exit 1
  fi
  if [[ ! -f mcp-app/target/mcp-opensearch.jar ]]; then
    echo "ci/run.sh image: mcp-app/target/mcp-opensearch.jar is missing; run ci/run.sh verify first" >&2
    exit 1
  fi
  local tag="mcp-server-opensearch:${CI_IMAGE_TAG_PREFIX:-m5}-$(git rev-parse --short=7 HEAD)"
  docker build -t "$tag" .
  echo "ci/run.sh: built $tag"
}

if [[ $# -gt 1 ]]; then
  echo "usage: ci/run.sh [verify|hygiene|image]" >&2
  exit 2
fi
case "${1-verify}" in
  verify) verify ;;
  hygiene) hygiene ;;
  image) image ;;
  *) echo "usage: ci/run.sh [verify|hygiene|image]" >&2; exit 2 ;;
esac
