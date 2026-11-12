#!/usr/bin/env bash
# =============================================================================
# whereq-dosbox — Release Script
# =============================================================================
# Bump the version, write it to the VERSION file, commit, tag and push.
#
# The release branch is `main`; `origin` is whereq/whereq-dosbox, the only
# remote. The DOSBox core stays at the repo root, imported as a single
# commit, so the port work is the commits on top of that import.
#
# USAGE
#   bin/release.sh [OPTIONS]
#
# OPTIONS
#   -m, --message <msg>     Commit message (required unless --skip-commit).
#       --tag-message <msg> Annotation for the git tag (default: --message).
#       --major              Bump major (e.g. v1.0.0.6 → v2.0.0.7). Mutually
#                           exclusive with --minor/--patch; the default is the
#                           build counter only.
#       --minor              Bump minor (e.g. v1.0.0.6 → v1.1.0.7).
#       --patch              Bump patch (e.g. v1.0.0.6 → v1.0.1.7).
#       --skip-commit        Do not commit — only bump VERSION and tag.
#       --skip-tag           Do not create or push a tag.
#       --skip-push          Do not push (commit and tag stay local).
#   -y, --yes               Skip the pre-release interactive confirmation.
#       --dry-run            Print all commands without executing them.
#
# TAG NAMING
#   v{major}.{minor}.{patch}.{build}
#   Strictly four numeric segments, "v" prefixed. The build counter is
#   auto-incremented; --major/--minor/--patch bump those segments too (the
#   build counter still increments). For the very first release on this clone
#   it starts at v1.0.0.1. Override with RELEASE_TAG=vX.Y.Z.A to pin a tag.
#
#   Only tags matching the scheme count when the next version is computed, so
#   anything else - the upstream DOSBox tags (dosbox-0.74, r4010-20170505,
#   r4010-20221207) among them - is ignored.
#
#   The tag is the single source of truth for the version: it is mirrored into
#   the VERSION file at the repo root, and the Android build reads that file
#   for android:versionName / android:versionCode.
#
# TYPICAL FLOW
#   bin/release.sh -m "Fix the rootless toolchain bootstrap"
#   bin/release.sh --patch -m "Tune the Android audio latency"
#   bin/release.sh --dry-run -m "Preview the next release"
# =============================================================================
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

# -----------------------------------------------------------------------------
# Colours
# -----------------------------------------------------------------------------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
DIM='\033[2m'
BOLD='\033[1m'
NC='\033[0m'

# -----------------------------------------------------------------------------
# Helpers
# -----------------------------------------------------------------------------
info()  { echo -e "${CYAN}▶${NC} $*"; }
ok()    { echo -e "${GREEN}✔${NC} $*"; }
warn()  { echo -e "${YELLOW}⚠${NC}  $*" >&2; }
error() { echo -e "${RED}✖${NC} $*" >&2; }
dim()   { echo -e "${DIM}  $*${NC}"; }

STEP=0
step() {
    STEP=$((STEP + 1))
    echo ""
    echo -e "${BOLD}${CYAN}━━━ Step ${STEP}: $*${NC}"
}

run() {
    if [[ "$DRY_RUN" == true ]]; then
        echo -e "  ${DIM}[dry-run]${NC} ${BOLD}$*${NC}"
    else
        "$@"
    fi
}

usage() {
    # Print the header comment block: every comment line up to the first line
    # of code, with the comment markers and the "=====" separators stripped.
    awk '
        NR == 1 { next }
        /^# ={10,}$/ { next }
        /^#/ { sub(/^# ?/, ""); print; next }
        { exit }
    ' "${BASH_SOURCE[0]}"
    exit 0
}

# -----------------------------------------------------------------------------
# State
# -----------------------------------------------------------------------------
COMMIT_MSG=""
TAG_MSG=""
BUMP_MAJOR=false
BUMP_MINOR=false
BUMP_PATCH=false
SKIP_COMMIT=false
SKIP_TAG=false
SKIP_PUSH=false
ASSUME_YES=false
DRY_RUN=false
RELEASE_BRANCH="${RELEASE_BRANCH:-main}"
RELEASE_REMOTE="${RELEASE_REMOTE:-origin}"
VERSION_FILE="VERSION"

# The house tag scheme: strictly four numeric segments.
TAG_RE='^v[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$'
VERSION_RE='^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$'

# -----------------------------------------------------------------------------
# Arguments
# -----------------------------------------------------------------------------
while [[ $# -gt 0 ]]; do
    case "$1" in
        -m|--message)      COMMIT_MSG="${2:-}"; shift 2 ;;
        --tag-message)     TAG_MSG="${2:-}"; shift 2 ;;
        --major)           BUMP_MAJOR=true; shift ;;
        --minor)           BUMP_MINOR=true; shift ;;
        --patch)           BUMP_PATCH=true; shift ;;
        --skip-commit)     SKIP_COMMIT=true; shift ;;
        --skip-tag)        SKIP_TAG=true; shift ;;
        --skip-push)       SKIP_PUSH=true; shift ;;
        -y|--yes)          ASSUME_YES=true; shift ;;
        --dry-run)         DRY_RUN=true; shift ;;
        -h|--help)         usage ;;
        *) error "unknown option: $1"; usage ;;
    esac
done

if [[ "$BUMP_MAJOR" == true && "$BUMP_MINOR" == true ]] \
   || [[ "$BUMP_MAJOR" == true && "$BUMP_PATCH" == true ]] \
   || [[ "$BUMP_MINOR" == true && "$BUMP_PATCH" == true ]]; then
    error "--major, --minor and --patch are mutually exclusive"
    exit 1
fi

if [[ "$SKIP_COMMIT" == false && -z "$COMMIT_MSG" ]]; then
    error "a commit message is required: bin/release.sh -m \"...\""
    exit 1
fi
[[ -z "$TAG_MSG" ]] && TAG_MSG="$COMMIT_MSG"

# -----------------------------------------------------------------------------
# Version computation
# -----------------------------------------------------------------------------

# The current version is the newest tag that follows the scheme. The VERSION
# file only *mirrors* that tag - it is an output of a release, never the
# input for the next one, otherwise a stale or hand-edited file would
# desynchronise the build counter.
current_version() {
    local last
    last=$(git tag --sort=-version:refname | grep -E "$TAG_RE" | head -1 || true)

    local version="1.0.0"
    if [[ -n "$last" ]]; then
        version="${last#v}"
    fi

    if [[ -f "$VERSION_FILE" ]]; then
        local v
        v="$(tr -d '[:space:]' < "$VERSION_FILE")"
        if [[ "$v" =~ $VERSION_RE ]] && [[ "$v" != "$version" ]]; then
            warn "$VERSION_FILE holds '$v' but the last release is '$version' - the tag wins"
        fi
    fi

    echo "$version"
}

# The version of the release we are about to make.
next_version() {
    if [[ -n "${RELEASE_TAG:-${NEXT_TAG:-}}" ]]; then
        local pinned="${RELEASE_TAG:-${NEXT_TAG:-}}"
        if [[ ! "$pinned" =~ $TAG_RE ]]; then
            error "RELEASE_TAG/NEXT_TAG='$pinned' is malformed (must be vX.Y.Z.A)"
            exit 1
        fi
        echo "${pinned#v}"
        return
    fi

    local body major minor patch build
    body="$(current_version)"
    IFS='.' read -r major minor patch build <<< "$body"
    major="${major:-1}"; minor="${minor:-0}"; patch="${patch:-0}"; build="${build:-0}"

    if [[ "$BUMP_MAJOR" == true ]]; then
        major=$((major + 1)); minor=0; patch=0
    elif [[ "$BUMP_MINOR" == true ]]; then
        minor=$((minor + 1)); patch=0
    elif [[ "$BUMP_PATCH" == true ]]; then
        patch=$((patch + 1))
    fi

    build=$((build + 1))
    echo "${major}.${minor}.${patch}.${build}"
}

# android:versionCode has to be a monotonically increasing integer. Encode the
# four segments so that v1.0.0.1 < v1.0.0.2 < v1.0.1.1 < v2.0.0.1.
version_code() {
    local body="$1"
    IFS='.' read -r major minor patch build <<< "$body"
    echo $(( major * 1000000 + minor * 10000 + patch * 100 + build ))
}

# -----------------------------------------------------------------------------
# Pre-flight
# -----------------------------------------------------------------------------
step "Pre-flight"

BRANCH="$(git rev-parse --abbrev-ref HEAD)"
if [[ "$BRANCH" != "$RELEASE_BRANCH" ]]; then
    error "not on the release branch '$RELEASE_BRANCH' (on '$BRANCH')"
    error "run: git checkout $RELEASE_BRANCH"
    exit 1
fi

git fetch --tags --prune "$RELEASE_REMOTE" 2>&1 | sed 's/^/  /' || true

if [[ -n "$(git status --porcelain)" ]]; then
    info "working tree has changes:"
    git status --short | sed 's/^/    /'
else
    dim "working tree is clean"
fi

VERSION="$(next_version)"
TAG="v${VERSION}"
VC="$(version_code "$VERSION")"

if git rev-parse "$TAG" >/dev/null 2>&1; then
    error "tag $TAG already exists"
    exit 1
fi

info "current version : $(current_version)"
info "next version    : $VERSION  (tag $TAG, android:versionCode $VC)"
info "branch          : $BRANCH"

if [[ "$SKIP_COMMIT" == false ]]; then
    echo ""
    echo -e "${BOLD}Commit message:${NC} $COMMIT_MSG"
    echo -e "${BOLD}Tag message:   ${NC} $TAG_MSG"
fi

if [[ "$ASSUME_YES" == false && "$DRY_RUN" == false ]]; then
    echo ""
    read -r -p "Proceed? [y/N] " reply
    case "$reply" in
        [yY]|[yY][eE][sS]) ;;
        *) info "aborted"; exit 1 ;;
    esac
fi

# -----------------------------------------------------------------------------
# Version file
# -----------------------------------------------------------------------------
step "Write VERSION"

if [[ "$SKIP_COMMIT" == true && "$SKIP_TAG" == true ]]; then
    dim "nothing to do (--skip-commit --skip-tag)"
    exit 0
fi

if [[ "$DRY_RUN" == true ]]; then
    dim "[dry-run] would write $VERSION_FILE = $VERSION"
else
    echo "$VERSION" > "$VERSION_FILE"
    ok "$VERSION_FILE = $VERSION"
fi

# -----------------------------------------------------------------------------
# Commit
# -----------------------------------------------------------------------------
if [[ "$SKIP_COMMIT" == false ]]; then
    step "Commit"
    run git add -A
    if [[ "$DRY_RUN" == true ]]; then
        run git commit -m "$COMMIT_MSG"
        ok "committed"
    elif git diff --cached --quiet; then
        dim "nothing to commit - keeping the current HEAD"
    else
        run git commit -m "$COMMIT_MSG"
        ok "committed"
    fi
else
    dim "skipping the commit"
fi

# -----------------------------------------------------------------------------
# Tag
# -----------------------------------------------------------------------------
if [[ "$SKIP_TAG" == false ]]; then
    step "Tag"
    if git rev-parse "$TAG" >/dev/null 2>&1; then
        error "tag $TAG already exists - delete it first if you want to recreate"
        exit 1
    fi
    # Annotated tag: the release note travels with the tag, not just in the
    # commit. Unsigned, to match the other whereq repositories.
    run git tag -a "$TAG" -m "$TAG_MSG"
    ok "tagged $TAG"
else
    dim "skipping the tag"
fi

# -----------------------------------------------------------------------------
# Push
# -----------------------------------------------------------------------------
if [[ "$SKIP_PUSH" == false ]]; then
    step "Push"
    run git push "$RELEASE_REMOTE" "HEAD:refs/heads/$RELEASE_BRANCH"
    if [[ "$SKIP_TAG" == false ]]; then
        run git push "$RELEASE_REMOTE" "refs/tags/$TAG"
    fi
    ok "pushed to $RELEASE_REMOTE"
else
    dim "skipping the push"
fi

# -----------------------------------------------------------------------------
# Summary
# -----------------------------------------------------------------------------
echo ""
echo -e "${BOLD}${GREEN}═══ Release ${TAG} ═══${NC}"
echo -e "  version      : ${VERSION}"
echo -e "  tag          : ${TAG}"
echo -e "  versionCode  : ${VC}"
echo -e "  branch       : ${RELEASE_BRANCH}"
echo -e "  build it with:"
echo -e "    ./android/build-apk.sh --version-name ${VERSION} --version-code ${VC}"
echo ""
