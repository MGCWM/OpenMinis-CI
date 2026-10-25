# MinisApp shell configuration
# Loaded by /etc/profile via the profile.d mechanism (login shells only).

# Prompt parity with iOS — `root@minis:/var/minis#`. iOS bakes the
# literal "minis" into PS1 (deps/prepare_alpine_rootfs.sh) rather than
# relying on \h, so the prompt is stable regardless of what /etc/hostname
# happens to contain. We do the same on Android so a fresh install
# matches without needing a rootfs rebuild.
export PS1='\u@minis-linux:\w\$ '

# Enable bash command history with arrow keys
export HISTFILE="$HOME/.bash_history"
export SHELL=/bin/bash
export DEBIAN_FRONTEND=noninteractive
export HISTSIZE=1000

# TLS: trust the host-injected bundle at /etc/ssl/certs/ca-certificates.crt.
# RootfsManager.injectHostCaBundle rewrites it on every boot from the Android
# trust store (AndroidCAStore + APEX fallback); PRootKernel also seeds these
# into every process envp so non-login shells see them.
export SSL_CERT_FILE="${SSL_CERT_FILE:-/etc/ssl/certs/ca-certificates.crt}"
export SSL_CERT_DIR="${SSL_CERT_DIR:-/etc/ssl/certs}"
export CURL_CA_BUNDLE="${CURL_CA_BUNDLE:-$SSL_CERT_FILE}"
export REQUESTS_CA_BUNDLE="${REQUESTS_CA_BUNDLE:-$SSL_CERT_FILE}"
export GIT_SSL_CAINFO="${GIT_SSL_CAINFO:-$SSL_CERT_FILE}"
export PIP_CERT="${PIP_CERT:-$SSL_CERT_FILE}"
export NODE_EXTRA_CA_CERTS="${NODE_EXTRA_CA_CERTS:-$SSL_CERT_FILE}"

# Interactive bash reads ~/.bashrc; keep ENV for dash leftovers.
export ENV="$HOME/.bashrc"

# Default pager — less is standard on Alpine; keep explicit for scripts
# that probe $PAGER.
export PAGER=less

# URL interception: $BROWSER is also seeded directly into every process
# envp via PRootKernel.customEnvironment so non-login shells (which never
# source profile.d) still see it. The xdg-open / sensible-browser /
# www-browser / x-www-browser / gnome-open / kde-open wrappers live as
# real files in default_mount/usr/local/bin/ and are overlaid on every
# boot.
export BROWSER=/usr/local/bin/minis-open

# PRoot's link2symlink extension creates .l2s.* sentinel files alongside
# every hardlinked file. uv's default `hardlink` mode tries to re-link these
# sentinels when populating site-packages, which PRoot rejects with EPERM.
# Force uv to symlink package files instead — the sentinels are then never
# touched as link sources. Reported as openminis/openminis#7.
export UV_LINK_MODE=symlink

# openminis/openminis#375: same failure family as UV_LINK_MODE above, via Git.
# `git gc` / `repack` / `clone --local` create objects with link(), which
# link2symlink turns into .l2s.* sentinels written INTO .git/objects/pack/.
# Git chmods that directory to 0555, so the sentinel bookkeeping (which runs
# as the real uid, not the faked root) fails and the pack files become
# undeletable. Copying instead of hardlinking keeps Git off that path
# entirely. Costs disk on a local clone; sandbox repos are small.
#
# Only affects NEW operations -- it cannot repair already-stuck files.
# Toolchain paths (populated by minis-dev-setup / minis-android-sdk-setup).
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
export PATH="$PATH:/opt/bin:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:/opt/android-sdk/build-tools/35.0.2:/opt/gradle/bin"
for _jdk in /usr/lib/jvm/java-21-openjdk-arm64 /usr/lib/jvm/java-17-openjdk-arm64 /usr/lib/jvm/default-java; do
  if [ -d "$_jdk" ]; then
    export JAVA_HOME="$_jdk"
    export PATH="$JAVA_HOME/bin:$PATH"
    break
  fi
done
unset _jdk

# [T-firstboot] Install a small core toolchain once. The guest rootfs lives in
# app-private storage, so uninstalling or clearing the app wipes it; this makes
# a fresh install usable without the user re-running minis-dev-setup by hand.
# Backgrounded on purpose — a shell must never block on apt. Heavy extras
# (gcc, jdk, ffmpeg, gradle) stay opt-in via minis-dev-setup.
if [ ! -f /var/lib/minis-firstboot.done ] && [ -x /usr/local/bin/minis-firstboot ]; then
    ( /usr/local/bin/minis-firstboot >/dev/null 2>&1 & )
fi
# openminis/openminis#409: this used to run
# `git config --system core.createObject copy`, but `copy` is not a valid
# value (only `link` and `rename` are), so every git command died with
# "invalid mode for object creation". Git is now told
# core.createObject=rename through GIT_CONFIG_COUNT / GIT_CONFIG_KEY_0 /
# GIT_CONFIG_VALUE_0, injected by PRootKernel into every shell (login or
# not), and RootfsManager removes the invalid line from /etc/gitconfig at boot.
