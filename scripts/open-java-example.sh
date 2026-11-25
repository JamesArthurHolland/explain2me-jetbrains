#!/usr/bin/env bash
# Opens the Java linear-regression example in IntelliJ IDEA.
#
#   scripts/open-java-example.sh            open it in your installed IntelliJ IDEA
#   scripts/open-java-example.sh --sandbox  build the plugin and open it in a sandbox IDEA with the plugin loaded
#
# Environment:
#   IDEA_APP   path to the IntelliJ IDEA app/launcher (default: auto-detected)
#   JAVA_HOME  JDK 21+ used by Gradle for --sandbox (default: auto-detected on macOS)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EXAMPLE="$ROOT/examples/e2m_example_java_linear_regression"
MODE="installed"

case "${1:-}" in
  --sandbox) MODE="sandbox" ;;
  -h|--help) sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
  "") ;;
  *) echo "Unknown option: $1 (try --help)" >&2; exit 2 ;;
esac

[[ -d "$EXAMPLE" ]] || { echo "Example not found: $EXAMPLE" >&2; exit 1; }

if [[ "$MODE" == "sandbox" ]]; then
  if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then
    JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
    export JAVA_HOME
  fi
  echo "Starting a sandbox IntelliJ IDEA with Explain2Me and the Java example…"
  cd "$ROOT"
  exec ./gradlew runIde -PrunExample=examples/e2m_example_java_linear_regression --console=plain
fi

# --- installed IntelliJ IDEA ------------------------------------------------------------------

find_idea() {
  if [[ -n "${IDEA_APP:-}" ]]; then echo "$IDEA_APP"; return; fi
  case "$(uname -s)" in
    Darwin)
      for app in "/Applications/IntelliJ IDEA.app" "/Applications/IntelliJ IDEA Ultimate.app" \
                 "/Applications/IntelliJ IDEA CE.app" "$HOME/Applications/IntelliJ IDEA.app" \
                 "$HOME/Applications/IntelliJ IDEA Ultimate.app" "$HOME/Applications/IntelliJ IDEA CE.app"; do
        [[ -d "$app" ]] && { echo "$app"; return; }
      done ;;
    *)
      for cmd in idea idea.sh intellij-idea-ultimate intellij-idea-community; do
        command -v "$cmd" >/dev/null 2>&1 && { command -v "$cmd"; return; }
      done ;;
  esac
}

plugin_installed() {
  local base
  case "$(uname -s)" in
    Darwin) base="$HOME/Library/Application Support/JetBrains" ;;
    *)      base="${XDG_CONFIG_HOME:-$HOME/.config}/JetBrains" ;;
  esac
  compgen -G "$base/IntelliJIdea*/plugins/explain2me-jetbrains" >/dev/null ||
    compgen -G "$base/IdeaIC*/plugins/explain2me-jetbrains" >/dev/null
}

IDEA="$(find_idea || true)"
if [[ -z "$IDEA" ]]; then
  echo "IntelliJ IDEA not found. Set IDEA_APP=/path/to/IDEA, or use --sandbox." >&2
  exit 1
fi

if ! plugin_installed; then
  echo "Note: Explain2Me doesn't seem to be installed in IntelliJ IDEA."
  echo "      Build it with ./gradlew buildPlugin and install build/distributions/*.zip via"
  echo "      Settings → Plugins → ⚙ → Install Plugin from Disk…  (or run this script with --sandbox)."
fi

echo "Opening $EXAMPLE in $IDEA"
if [[ "$(uname -s)" == "Darwin" && -d "$IDEA" ]]; then
  open -a "$IDEA" "$EXAMPLE"
else
  nohup "$IDEA" "$EXAMPLE" >/dev/null 2>&1 &
fi

cat <<'EOF'

Next:
  • Open the Explain2Me tool window (right side), pick "linear-regression", press Load, then Next ▶ or Live replay.
  • To let Claude drive it:  claude mcp add --transport http explain2me http://127.0.0.1:63390/mcp
EOF
