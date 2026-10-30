# Release signing

The release key is **not** in this repository.

- Keystore: `~/.config/better-coooltv/release.jks` (PKCS12, alias `release`, RSA 4096, valid 100 years)
- Passwords and path: `~/.gradle/gradle.properties` (`coooltvReleaseStoreFile`, `coooltvReleaseStorePassword`,
  `coooltvReleaseKeyAlias`, `coooltvReleaseKeyPassword`), file mode 600
- Certificate SHA-256: `4C:96:BA:4C:08:74:28:E4:4B:37:72:99:7B:DB:D2:BC:29:41:6E:F5:0B:91:50:AB:CB:83:B6:6E:BA:9D:4B:10`

**Back up the keystore file and its password together** (for example in a password manager). Without them,
installed copies can never be updated: Android only accepts updates signed with the same key.

If the properties are missing, `assembleRelease` still works but signs with the debug key and logs a warning.

## Builds

| Build | App ID | Label | Signed with |
|---|---|---|---|
| debug | `dev.bettercoool.tv.debug` | Coool TV Lite (dev) | debug key |
| release | `dev.bettercoool.tv` | Coool TV Lite | release key |

They install side by side and pair separately.

```bash
./gradlew :app:assembleRelease   # app/build/outputs/apk/release/app-release.apk
```
