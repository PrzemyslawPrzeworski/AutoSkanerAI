#!/bin/sh
# Shared setup for the git hooks in this directory. Sourced, not executed.
#
# Both hooks need node (frontend) and a JDK (backend), and on this machine neither is on the default
# PATH. The rule throughout: if a toolchain is missing, FAIL rather than skip. A gate that quietly
# does nothing when its interpreter is absent is how the PostToolUse prettier hook stayed dead from
# May to September -- see .claude/hooks/post-edit-check.sh.

# nvm4w keeps node outside the default PATH.
case ":$PATH:" in
  *:/c/nvm4w/nodejs:*) ;;
  *) PATH="/c/nvm4w/nodejs:$PATH"; export PATH ;;
esac

# The dev JDK. Maven needs JAVA_HOME; the wrapper does not search for one.
if [ -z "$JAVA_HOME" ] && [ -d "D:/Software/Java/jdk-26.0.1" ]; then
  JAVA_HOME="D:/Software/Java/jdk-26.0.1"
  export JAVA_HOME
fi

# Keeps the backend suite inside a memory budget that leaves the machine usable.
MAVEN_OPTS="${MAVEN_OPTS:--Xmx1g}"
export MAVEN_OPTS

REPO_ROOT=$(git rev-parse --show-toplevel)
cd "$REPO_ROOT" || exit 1

fail() {
  echo ""
  echo "  x $1"
  echo ""
  exit 1
}

step() {
  echo "  - $1"
}

require_node() {
  command -v node >/dev/null 2>&1 || fail "node is not on PATH, so the frontend checks cannot run (this machine: /c/nvm4w/nodejs)."
}

require_java() {
  [ -n "$JAVA_HOME" ] || fail "JAVA_HOME is unset and the dev JDK was not found, so the backend suite cannot run."
}

# Runs the backend suite, adding the one hint that explains its most confusing failure mode.
run_backend_tests() {
  require_java
  if ! (cd backend && ./mvnw -o test > /tmp/hook-backend.log 2>&1); then
    # Surefire prints the failure list immediately before Maven's ~20-line build epilogue, so a fixed
    # tail window shows it only while the list stays short. Pull the list out by name instead; fall
    # back to the tail when there is no list at all, which is what a compile error looks like.
    SUREFIRE_FAILURES=$(grep -a -E '^\[ERROR\]   |^\[ERROR\] (Failures|Errors|Tests run):' \
      /tmp/hook-backend.log | tr -d '\000' | head -40)
    if [ -n "$SUREFIRE_FAILURES" ]; then
      echo "$SUREFIRE_FAILURES"
    else
      tail -40 /tmp/hook-backend.log | tr -d '\000'
    fi
    echo ""
    echo "  Full log: /tmp/hook-backend.log"
    echo "  Note: this runs Maven offline (-o) for speed. If you just added or bumped a dependency,"
    echo "  that alone can fail here -- run 'cd backend && ./mvnw test' once online, then retry."
    fail "backend suite failed."
  fi
}

run_frontend_tests() {
  require_node
  if ! (cd frontend && npm test -- --watch=false > /tmp/hook-frontend.log 2>&1); then
    tail -60 /tmp/hook-frontend.log
    echo ""
    echo "  Full log: /tmp/hook-frontend.log"
    fail "frontend suite failed."
  fi
}
