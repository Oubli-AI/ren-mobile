#!/bin/sh
# JDK 21, Gradle and the Android SDK in the shared home (/config), so OpenCode on the computer builds the Android apps the
# Context Agent hands it. Run once as the laptop's user: `docker compose -p emrys exec computer sh /tmp/android.sh`
# after copying it in, or from the laptop's terminal. Every download is pinned and checked against its checksum.
set -eu

JDK=21.0.13_9
JDK_SHA256=d7f5d5cf865a2902b70ff549b97586a7d3f234e016f041ab447ea740c7d66d1c
GRADLE=8.14.3
GRADLE_SHA256=bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531
TOOLS=16111833
TOOLS_SHA1=e025545c62a8e64c7559119566a569fb1dec5f60

work=$(mktemp -d)
cd "$work"
curl -fsSLo jdk.tar.gz "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-$(echo $JDK | tr _ +)/OpenJDK21U-jdk_x64_linux_hotspot_$JDK.tar.gz"
echo "$JDK_SHA256  jdk.tar.gz" | sha256sum -c -
rm -rf "$HOME/.jdk" && mkdir -p "$HOME/.jdk" && tar -xzf jdk.tar.gz -C "$HOME/.jdk" --strip-components=1

curl -fsSLo gradle.zip "https://services.gradle.org/distributions/gradle-$GRADLE-bin.zip"
echo "$GRADLE_SHA256  gradle.zip" | sha256sum -c -
rm -rf "$HOME/.gradle-dist" && unzip -q gradle.zip && mv "gradle-$GRADLE" "$HOME/.gradle-dist"

curl -fsSLo tools.zip "https://dl.google.com/android/repository/commandlinetools-linux-${TOOLS}_latest.zip"
echo "$TOOLS_SHA1  tools.zip" | sha1sum -c -
sdk="$HOME/Android/Sdk"
rm -rf "$sdk/cmdline-tools" && mkdir -p "$sdk/cmdline-tools" && unzip -q tools.zip && mv cmdline-tools "$sdk/cmdline-tools/latest"
export JAVA_HOME="$HOME/.jdk"
yes | "$sdk/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$sdk" --licenses > /dev/null
"$sdk/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$sdk" "platform-tools" "platforms;android-36" "build-tools;36.0.0"

# Gradle and Kotlin share the computer's memory with OpenCode.
mkdir -p "$HOME/.gradle"
printf 'org.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=768m\nkotlin.daemon.jvmargs=-Xmx1g\n' > "$HOME/.gradle/gradle.properties"
# The owner's terminal on the laptop; the computer image sets the same in its environment.
for rc in "$HOME/.bashrc" "$HOME/.profile"; do
    grep -q ANDROID_HOME "$rc" 2>/dev/null || printf '%s\n' 'export JAVA_HOME="$HOME/.jdk" ANDROID_HOME="$HOME/Android/Sdk"' \
        'export PATH="$HOME/.jdk/bin:$HOME/.gradle-dist/bin:$ANDROID_HOME/platform-tools:$PATH"' >> "$rc"
done
rm -rf "$work"
java -version 2>&1 | head -1 || "$HOME/.jdk/bin/java" -version 2>&1 | head -1
