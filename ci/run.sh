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
# Any other argument is an error (exit 2). The tests need no container runtime, so a merge request pipeline mounts no
# Docker socket and holds no secret: without the credentials of the registry the build reads it anonymously.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

mvnw() {
  local settings=()
  if [[ -n "${MCP_MAVEN_REPOSITORY_URL:-}" ]]; then
    if [[ -n "${MCP_MAVEN_REPOSITORY_USER:-}" && -n "${MCP_MAVEN_REPOSITORY_PASSWORD:-}" ]]; then
      settings=(-s .ci/settings.xml)
    else
      settings=(-s .ci/settings-anonymous.xml)
    fi
  fi
  ./mvnw -B ${settings[@]+"${settings[@]}"} "$@"
}

tracked_sources() {
  git ls-files -z --cached --others --exclude-standard -- '*.java' '*.md' '*.xml' '*.yml' '*.yaml' '*.properties' '*.json' '*.sh' 'Jenkinsfile' 'Dockerfile' ':!:ci/run.sh'
}

# The two checks of the house style: characters that give away generated text, and phrases left by tools. The perl
# program reads with <<>> so that a file name is never opened as a command, and decodes the files (-CSD) because the
# characters in the class are not single bytes.
hygiene() {
  local failed=0
  if tracked_sources | xargs -0 perl -CSD -e 'while (<<>>) { if (/[\x{00A0}\x{200B}\x{202F}\x{FEFF}\x{2018}\x{2019}\x{201C}\x{201D}\x{2026}]/) { print "$ARGV:$.: $_"; $bad = 1 } } continue { close ARGV if eof } exit($bad ? 1 : 0)'; then
    :
  else
    echo "ci/run.sh: non-ASCII quotes, spaces or ellipsis found (above)" >&2
    failed=1
  fi
  if tracked_sources | xargs -0 grep -niE 'as an ai|generated with|co-authored-by: claude|lorem ipsum|your-api-key|exampl[e]\.com' -- ; then
    echo "ci/run.sh: tool traces or placeholders found (above)" >&2
    failed=1
  fi
  return "$failed"
}

# A build that ran no test proves nothing (a wrong -Dtest filter, a broken surefire configuration); skipped cases do
# not count as run.
require_tests_ran() {
  local cases skipped ran
  cases="$(cat mcp-*/target/surefire-reports/TEST-*.xml 2>/dev/null | grep -c '<testcase ' || true)"
  skipped="$(cat mcp-*/target/surefire-reports/TEST-*.xml 2>/dev/null | grep -c '<skipped' || true)"
  ran=$(( ${cases:-0} - ${skipped:-0} ))
  if [[ "$ran" -le 0 ]]; then
    echo "ci/run.sh: no test was run" >&2
    exit 1
  fi
  echo "ci/run.sh: $ran test cases ran ($skipped skipped)"
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
  docker build -t "mcp-opensearch:$(git rev-parse --short=12 HEAD)" .
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
