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
#
# The repo's pinned JDK wins over an inherited JAVA_HOME, and that precedence is the point. This
# machine has a system-wide JAVA_HOME of "C:\Program Files (x86)\Zulu\zulu-8-jre\" -- a 32-bit
# Java 8 *JRE*, no javac in it at all. The earlier version deferred to it, because it only filled
# JAVA_HOME in when the variable was empty, and "set" is not "usable": nothing in this project
# compiles under Java 8.
DEV_JDK="D:/Software/Java/jdk-26.0.1"
if [ -f "$DEV_JDK/bin/javac.exe" ] || [ -x "$DEV_JDK/bin/javac" ]; then
  JAVA_HOME="$DEV_JDK"
  export JAVA_HOME
fi

# Set, not defaulted. The inherited value on this machine is "-Xmx12g -XX:ReservedCodeCacheSize=256m
# -Xss24m", which the 32-bit JRE above rejects outright with "Invalid maximum heap size ... exceeds
# the maximum representable size" -- a message about memory for a problem about Java, which cost a
# commit to diagnose. A budget the environment can override is not a budget.
MAVEN_OPTS="-Xmx1g"
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

# Checks for a compiler, not for a variable. A JAVA_HOME pointing at a JRE satisfies every
# non-empty test and still cannot build anything, and it fails deep inside Maven with an error that
# names neither Java nor JAVA_HOME.
require_java() {
  [ -n "$JAVA_HOME" ] || fail "JAVA_HOME is unset and the dev JDK was not found at $DEV_JDK, so the backend suite cannot run."
  if [ ! -f "$JAVA_HOME/bin/javac.exe" ] && [ ! -x "$JAVA_HOME/bin/javac" ]; then
    fail "JAVA_HOME has no javac, so it is a JRE or a bad path and the backend suite cannot run.
      JAVA_HOME=$JAVA_HOME
      This project needs a JDK 21+; the pinned one is $DEV_JDK."
  fi
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
