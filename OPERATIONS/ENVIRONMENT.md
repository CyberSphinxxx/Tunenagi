# Environment Preflight

## Required tools

Git, Python >=3.10, JDK compatible with chosen Android Gradle Plugin, Gradle Wrapper from trustworthy stable distribution, Android command-line SDK/platform tools, emulator or Android device with USB debugging, internet for Android dependencies and provider API checks. Optional ktlint/detekt, Android Lint, Macrobenchmark, apksigner, GitHub CLI.

## Windows PowerShell example (agent must adapt commands)

```
Get-Command java, git, python, adb -ErrorAction SilentlyContinue
java -version
adb devices
python SCRIPTS/validate_package.py
python SCRIPTS/taskctl.py status
```

For builds once the project exists: `./gradlew.bat --version`, `./gradlew.bat :app:assembleDebug`, `./gradlew.bat testDebugUnitTest`, `./gradlew.bat lintDebug`. Linux/macOS: `./gradlew` correspondingly. On initial kit, Gradle wrapper **does not yet exist**: Phase 00 generates it.

## Emulator/device preflight

At minimum: one modern API emulator and one older-API test where feasible. Test real media session and lockscreen/notification, not only unit tests. Record device model/API and emulator configuration in the audit. A running emulator may consume significant RAM and CPU, so throttle concurrent Gradle/agent tasks.

## Host resource safety

Monitor free disk (SDK + Gradle + emulator), thermals, network, API usage and process memory. Do not run multiple mutating coding agents on the same tree without coordination. Protect sleeps/suspend/power settings responsibly; let owner set OS policies. Use rate limits and reduce expensive tests to phase boundaries when appropriate, without skipping mandatory gates.
