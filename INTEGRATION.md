# Android password integration

`pw` exposes an activity that another app signed with the same key can launch
to fetch one password entry. The activity unlocks the vault when
needed and returns a unique match immediately. When several entries match, it
identifies the calling package and request and asks the user which entry to
return. It returns no secret when the user cancels or no entry matches.

## Permission

Declare the following permission in the client application's manifest:

```xml
<uses-permission android:name="nu.staldal.pw.permission.FETCH_PASSWORD" />

<queries>
    <package android:name="nu.staldal.pw" />
</queries>
```

The permission has Android's `signature` protection level. Android grants it
only when the client and `pw` are signed by the same signing identity. Release
builds must therefore use the same release key; local debug builds must use the
shared debug key described in `app/build.gradle.kts` in both projects.

## Request

Launch an activity-for-result intent with action
`nu.staldal.pw.action.FETCH_PASSWORD`, package `nu.staldal.pw`, and exactly one
of these lookup forms:

- `nu.staldal.pw.extra.NAME`: exact, case-sensitive entry name.
- `nu.staldal.pw.extra.URL`: an `https:` URL, or `http://localhost` /
  `http://127.0.0.1` for local development.
- `nu.staldal.pw.extra.URL` plus `nu.staldal.pw.extra.REALM`: the same URL,
  narrowed to an HTTP authentication realm.

URL lookup compares normalized hosts exactly. It does not release a parent
domain's credential to a subdomain. With a realm, an entry naming that realm is
preferred; if none exists, an entry without a realm is the fallback. Without a
realm, only entries without a realm match.

Name lookup is intended for trusted native-app integration. It is not subject
to URL or scheme rules and can return an entry that has no `url`. Entry names
are unique, so a successful name lookup always returns immediately after any
necessary unlock and never opens the chooser.

```kotlin
private val passwordLauncher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
) { result ->
    if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
    val data = result.data ?: return@registerForActivityResult
    val username = data.getStringExtra("nu.staldal.pw.extra.RESULT_USERNAME")
    val password = data.getStringExtra("nu.staldal.pw.extra.RESULT_PASSWORD")
    // Use the credential immediately and do not persist or log it.
}

fun fetchForHttpAuth(url: String, realm: String) {
    passwordLauncher.launch(
        Intent("nu.staldal.pw.action.FETCH_PASSWORD")
            .setPackage("nu.staldal.pw")
            .putExtra("nu.staldal.pw.extra.URL", url)
            .putExtra("nu.staldal.pw.extra.REALM", realm)
    )
}
```

Setting the package is required so no other application can claim the implicit
action. The `<queries>` declaration makes the package visible to
`resolveActivity` on modern Android versions. If resolution returns null, pw
is absent or unavailable. The launch can still throw `ActivityNotFoundException`
if the package disappears, or `SecurityException` when the client was not
signed with pw's signing identity; clients should handle both. A package that
tries to define pw's permission first can prevent pw from installing, but
cannot downgrade or bypass the signature protection.

## Result

`Activity.RESULT_OK` carries these string extras:

| Extra | Meaning |
| --- | --- |
| `nu.staldal.pw.extra.RESULT_NAME` | Entry name |
| `nu.staldal.pw.extra.RESULT_USERNAME` | Username |
| `nu.staldal.pw.extra.RESULT_PASSWORD` | Password |
| `nu.staldal.pw.extra.RESULT_URL` | Stored URL, if any |
| `nu.staldal.pw.extra.RESULT_REALM` | Stored realm, if any |

Every other result code means that no credential was returned. Treat the
password extra as sensitive: never log it, put it in saved instance state, or
retain it longer than the operation needs.
