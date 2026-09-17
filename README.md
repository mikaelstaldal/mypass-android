# pw for Android

A password manager for Android that keeps your passwords in a single
encrypted file, using the same **standard scrypt encrypted-data format** as
[`pw`](https://github.com/mikaelstaldal/pw), the command line password manager it is the companion of. 
The same `pw.scrypt` file works on both: copy it across and every entry, username,
`url` and `realm` is there, byte for byte.

All cryptography happens in-process. Nothing is uploaded anywhere; the app
requests no network permission at all.

## Build

```sh
gradle assembleDebug       # debug build
gradle assembleRelease     # release build
gradle test                # unit tests (JVM, no device needed)
```

No Gradle wrapper — uses the system `gradle` command. Point `sdk.dir` in
`local.properties` at your Android SDK.

## What it does

| Screen                                                | The `pw` command it replaces           |
|-------------------------------------------------------|----------------------------------------|
| Unlock / Create vault                                 | `pw init`                              |
| Entry list, with search                               | `pw list [PATTERN]`                    |
| Entry detail — copy username, copy or reveal password | `pw get <name> [--show]`               |
| Add entry                                             | `pw add <name> [username] …`           |
| Edit entry (with "keep the existing password")        | `pw update <name> … [--keep-password]` |
| Remove entry, after confirmation                      | `pw remove <name>`                     |
| Generate password                                     | `pw generate`                          |
| Settings → Export decrypted JSON                      | `pw export`                            |
| Settings → Use pw for autofill                        | `pw install-browser`                   |

Plus what a phone needs and a terminal does not: an auto-lock timer, optional
fingerprint unlock, and importing and exporting the vault file through the
system file picker.

Settings can also import entries from KeePass 2.x KDBX 3.x and 4.x databases.
The import adds the live entries to the unlocked pw vault, mapping title,
username, password and URL. KeePass groups, history, notes, custom fields and
attachments have no counterpart in pw and are not imported. Existing entries
remain; a duplicate or invalid title rejects the whole import without writing.

The **username** is a free-form label stored alongside the password; it may be
omitted. Generated passwords use a cryptographically secure random number
generator (`SecureRandom`, OS-seeded) without modulo bias, and default to 16
characters of letters, digits and `-` — the same default as the desktop.
Custom character sets must contain at least two distinct Unicode code points;
duplicate code points are rejected so every permitted character has the same
probability.

## Getting your desktop vault onto the phone

1. Copy `~/pw.scrypt` to the phone (over a cable, not through a cloud drive if
   you can help it — the file is encrypted, but there is no reason to hand
   anyone a copy to grind on offline).
2. Open pw, tap **Import an existing pw.scrypt vault**, pick the file and type
   the master passphrase.

The import is verified before anything is written: it must decrypt and parse,
so a mistyped passphrase or a truncated copy is refused while the existing
vault is still there. It is then re-encrypted under this device's configured
KDF cost. The local backup `pw.scrypt.bak` is replaced with a snapshot of the
imported vault under the imported passphrase before the primary is replaced.
The previous local entries are discarded; export them before importing if you
need to keep them. An interrupted import leaves a complete primary recoverable.

Imported and existing vaults must have unique entry names and obey the same
metadata rules as desktop `pw` entry creation: at most 256 Unicode code points,
no control, bidirectional-control or zero-width characters, nonempty names and
present site hints, and a URL whenever a realm is present. Legacy vaults with
incompatible metadata are rejected with field-only errors before import changes
the local vault or backup; correct the source metadata and re-export to migrate.
Existing incompatible vaults are also refused on unlock. The lock screen offers
**Export encrypted vault** without unlocking, and **Import a replacement vault**
for the corrected copy. These recovery actions appear only in the main app,
never in autofill dialogs. They do not require the current master passphrase:
someone with access to the unlocked phone can export ciphertext for offline
passphrase guessing or replace both the vault and backup with their own vault.
Replacement requires an explicit confirmation before picking the incoming file.
Export first, repair the indicated entry metadata using
desktop `pw`, then import the corrected vault. Bare-array legacy vaults remain
decodable but must pass these metadata checks.
No entries are silently discarded. Presentation labels replace spoofing/control
characters and bound text length defensively. Passwords are never sanitized.
The scrypt v0 format, version-1 envelope and legacy array decoding are unchanged.

Changing the master passphrase similarly replaces both the primary and local
backup with snapshots encrypted under the new passphrase. The backup is committed
first, then the primary; each rename is followed by a directory fsync. An
interrupted change may leave the primary using either passphrase. A failure after
the primary replacement reports that the incoming passphrase is in effect and
disables fingerprint unlock. Unlock with the passphrase that works and retry the
change to complete it. Previously exported or otherwise copied ciphertext remains
accessible under its original passphrase; changing this device cannot revoke it.

Going the other way, **Settings → Export encrypted vault** writes the file out
through the system file picker, unchanged and still encrypted.

Android applies resource limits before importing or unlocking a vault: encrypted
files are limited to 4 MiB, JSON nesting to 8 levels, entries to 10,000, and
encoded string tokens to 16,384 characters (including the closing quote).
The parser is also limited to 110,016 value/container tokens before allocating
the JSON tree, including object keys and primitives in malformed entries.
Provider opening, reading, parsing and closing run off the UI thread. Reads stop
at the file limit plus one byte even for an endless stream; a provider that
blocks inside a read can still occupy an import worker until it returns, but
provider reads do not hold the repository mutex and cannot block other vault
operations.

The KDF is limited to `N * r * p <= 2^20` and an estimated scratch allocation of
`128 * r * (N + p + 2)` bytes, capped at the smaller of 160 MiB and half the
process maximum heap. These limits also apply to writes so a vault cannot be
saved with parameters this phone refuses to unlock. Standard desktop defaults
fit on phones with enough heap; smaller phones should lower the scrypt cost in
Settings. Defaults and the Settings range are chosen from the costs this
phone accepts; older saved Settings values are clamped for future writes.
Existing higher-cost ciphertext is still refused and needs desktop migration.
Higher-cost desktop files are refused with a resource-limit message,
without changing the existing vault or backup. To migrate one, decrypt it using
the desktop `scrypt` tool and re-encrypt the unchanged JSON with
`scrypt enc --logN 16 -r 8 -p 1` (or a lower logN for a smaller phone), then import
that file. Handle the decrypted desktop file as a secret. There is no automatic
high-cost retry or timeout around the synchronous KDF. The shared file format
remains unchanged; the accepted parameter range deliberately differs from
desktop to bound Android resource use and respect Bouncy Castle requirements.
These are JVM-tested limits; device ANR/OOM exploitability
from a malicious user-selected document/provider has not been demonstrated.

## Where the vault lives

`pw.scrypt` sits in the app's private storage, where no other app on the device
can reach it — not on the SD card or in `Documents`, where anything with
storage access could copy it away for an offline attack. That is also why there
is no "open a vault from anywhere" option: the file has to be somewhere pw
alone can read.

Getting a vault in and out is therefore explicit, and both directions go
through the system file picker: **Import** on the unlock screen (or, once a
vault exists, **Settings → Import a vault, replacing this one**) and
**Settings → Export encrypted vault**.

## Browser integration

pw is an Android **autofill service**. Turn it on in
**Settings → Use pw for autofill** (it takes you to the system screen that
actually grants it). On a login page, tap the username or password field and
pw appears in the keyboard strip or in a dropdown.

This is the counterpart of the desktop's Firefox integration, and it keeps the
same rules — which are stricter than most Android password managers':

- **Only entries with a `url` are ever offered.** The `url` is the sole
  association between an entry and a site, set by you in the app. An entry's
  `name` is never matched against the visited site, so it can be anything you
  like — useful when you keep two accounts on one site.
- **Origin matching** uses the desktop's layered rule: an entry matches a page
  on `https://login.example.co.uk` when the host part of its `url` equals the
  page's hostname exactly (`login.example.co.uk`) or is a parent domain at a
  label boundary (`example.co.uk`), up to but not including the registrable
  domain determined by the Public Suffix List — so a `url` of `co.uk` or `com`
  never matches. Matching is case-insensitive and IDNA/punycode-normalized,
  under UTS #46 non-transitional processing — the same profile the desktop and
  browsers use, so an internationalized name resolves to the same real host on
  both (see **Matching parity** below).
- **`https:` only**, plus `http://localhost` and `http://127.0.0.1` for local
  development. A browser that reports no scheme at all is refused rather than
  assumed to mean `https:`.
- **Browsers only.** Android hands an autofill service whatever web address the
  *source app* put in the request, and does not check it. An app that could
  name a host freely could name yours, so a web address counts only when it
  comes from a package pw recognises as a browser (see **Which browsers**
  below).
- **Web pages only.** Native app screens are never filled. An entry's
  association with a site is its `url`, which has no meaning for an app, and
  inventing a second kind of association would change the vault format the
  desktop shares.
- **The service never writes the vault.** A save offer hands off to a
  confirmation screen in the app, with the vault open and the entry on screen
  before anything is stored.
- **Which box is the username** is decided the way the desktop decides it. A
  field that identifies itself — an autofill hint, an `autocomplete`, `name` or
  `id` that names a user, or `type="email"` — is the username. When nothing on
  the page identifies one, it is the nearest text input *preceding* the
  password field inside the same form, which is desktop pw's rule verbatim
  (`webextension/fill.js`). Plenty of real login pages render their username
  box as a bare `<input type="text">` with no name, no id and
  `autocomplete="off"`, and no amount of name-matching recognises those.
  Getting this wrong fills the wrong box, which is a nuisance rather than a
  disclosure: what the *site* receives is decided by the origin, never by which
  field the text lands in. Several *identified* username fields are still left
  alone rather than guessed among, and a save offer names the username field
  only as optional — the required field is the password.

When the vault is locked, a fill request shows a single **Unlock pw** entry
instead of a password. Tapping it opens pw's own passphrase screen — outside
the browser, which cannot see or drive it — and the fill then lands where you
asked for it. Nothing about the vault is revealed by the offer: pw cannot tell
whether it has an entry for a site until it is open, so the offer necessarily
comes before that is known.

Each unlock offer is bound to its approved browser package, normalized host,
username/password field IDs and original focus in a process-local
request whose authority is consumed once at credential release. Before prompting and again before releasing a dataset, pw rechecks
browser publisher trust and the password field's eligible origin against that
request. Changed destinations are refused. Requests expire after five minutes;
process death also invalidates them. Cancelling a fill computation before its
offer is published withdraws the request; backing out of the unlock screen
allows a retry within the expiry window. This validates the
framework-supplied snapshot, not browser navigation that the framework does not
report. No ordinary-app exploitation of the authentication PendingIntent has
been demonstrated on a device.

### Which browsers

**Migration notice:** Brave, Edge, Vivaldi, Opera, DuckDuckGo, Tor, and Firefox
rebuilds require explicit enrollment of their installed certificates. The
browsers listed below have built-in publisher pins. Old package-only trust is
removed rather than silently trusting an installed replacement.

`autofill/Browsers.kt` pins SHA-256 signing-certificate digests for:

- Firefox, Firefox Beta, and Firefox Nightly (`org.mozilla.fenix`).
- Chrome Stable, Beta, Dev, and Canary.
- Samsung Internet and Samsung Internet Beta.

Firefox pins come from [Mozilla's certificate documentation](https://firefox-source-docs.mozilla.org/mobile/android/fenix/certificates.html).
Chrome and Samsung Internet pins come from
[Google's curated credential-browser allowlist](https://www.gstatic.com/gpm-passkeys-privileged-apps/apps.json),
retrieved on 2026-09-15, using its release entries. Google is the sole source for
the Chrome/Samsung browser package-to-certificate associations; this is not a
claim that each browser publisher independently documented those associations.
Samsung's `C8A2…2AB8` key is excluded despite Google's release label: the
[Samsung Account assetlinks](https://account.samsung.com/.well-known/assetlinks.json)
entry for `com.osp.app.signin` annotates its fingerprint list
`debug,platform,R_platformkey`. Read positionally, this suggests `C8A2…2AB8` is
a debug key and the retained `34DF…0A42` is a Samsung platform key. This is an
inference from a free-form comment for Samsung Account, not a browser-specific
build label, so pw errs on the side of excluding the suspected debug key.
The platform certificate may sign other Samsung apps, but trust remains scoped
to the two Samsung Internet packages. Different distributions may need enrollment.
The tests keep an offline excerpt of Google's list to verify pin transcription;
builds and autofill never fetch that list automatically.

A package name alone never grants trust. Both fill and save check the installed
package's certificates afresh and fail closed if they cannot be read (including
package-visibility restrictions). Android-verified single-signer rotation history
can link a new key to a pinned one; apps with multiple signers require every
current signer to be pinned.

Other browsers, rebuilds, and differently signed distributions require
**Settings → Installed browser package → Review enrollment**. Install from a
source you trust, review the package and SHA-256 certificate disclosure, then
explicitly confirm. An enrolled app may claim **any website** and receive its
credentials: enrollment identifies the installed publisher, not its honesty.
Enrollments can be removed in Settings. Old package-only permissions are not
migrated and must be enrolled again. Pins live in local settings outside the
shared vault and are excluded from backup/transfer.

Built-in pins require maintenance: verify new distributions and certificate
changes against publisher documentation, authenticated release artifacts, or
Google's curated credential-browser release list before updating the map and
rebuilding pw. Check for debug/test keys even when a list labels them release.
Unlinked key changes and local rebuilds fail closed until deliberately enrolled;
never substitute a package-only
fallback. No network lookup is performed by pw.

### Why a fill did not happen

Every refusal above is silent. The autofill framework gives a service no way to
say "I declined, and here is why" — it returns no offer, and the keyboard shows
nothing — so an unpinned certificate, a browser that never asked pw in the
first place, a page reporting no scheme, and an entry without a `url` all look
identical from the outside, while each has a different fix.

**Settings → Record why fills were refused** turns on a diagnostic that keeps
the last few requests and what pw decided about each: the requesting package,
the scheme and host it claimed, how many fields were classified, and the
outcome. It is **off by default**, and deliberately weak as a store — the
records live in this process's memory and are dropped when the switch goes off
or pw stops. While enabled, the same metadata is also written to Logcat with
tag `pw-autofill`, so it can be captured without switching back to pw. They
hold no field contents and no entry names. Logcat retention is controlled by
Android rather than pw; capture it over USB debugging with
`adb logcat -s pw-autofill:I '*:S'`, and turn the diagnostic off after
collecting the result. Ordinary apps cannot read another app's Logcat output,
but a privileged app or a device bug report can.

Two things it is worth knowing before switching it on. The records name the
hosts of pages you focused a password field on, so while it is on, pw's own
settings screen shows a little of your browsing; `FLAG_SECURE` still applies,
and "Clear records" drops them immediately. And on a refused request the origin
shown is what the browser *claimed*, recorded before the eligibility rules
judged it — that is what makes a missing scheme visible, and it is never used
to decide a fill. A request that got as far as matching shows the normalized
host instead, which is the one the entries were compared against.

A common first answer it gives is that no record appears at all. That means the
browser never asked pw, which is a setting inside the browser rather than
anything in pw: Chrome has *Settings → Autofill services → Autofill using
another service*, and Samsung Internet has its own autofill provider choice
that defaults to Samsung Pass. Android's own **Settings → Use pw for autofill**
has to be set too.

### Matching parity with the desktop

Which site may receive which credential is decided by string comparison on
hostnames, so the two sides have to normalize them identically or they disagree
about what a name means. Two places where the obvious Android answer would have
diverged from `../pw`:

- **IDNA.** `java.net.IDN` implements the older IDNA2003, which folds the four
  *deviation characters* instead of leaving them alone. `faß.de` would have
  become `fass.de` here and `xn--fa-hia.de` on the desktop; `γάτος.example`
  would have become `xn--hxan1bqd.example` here and `xn--hxan1bmi.example`
  there. The second is not exotic — final sigma ends a great many ordinary
  Greek words. `Matching` therefore uses ICU's **UTS #46 non-transitional**
  processing, which reproduces the Rust `idna` crate's output exactly; the
  expected values in `MatchingTest` were taken from that crate itself. ICU
  brings ~38 MiB of data for the rest of what it can do, none of which UTS #46
  reads; the build drops it, and `tools/verify-icu-data.sh` is what proves
  dropping it changes nothing.
- **The Public Suffix List's default rule.** Guava supplies the list but stops
  at it: a host under a TLD the list does not know (`evil.example`,
  `corp.internal`) reports no public suffix at all, which would have allowed
  only exact matches. The list's own rule is that an unrecognised suffix is
  `*` — one label — so `Matching` fills that in, as Rust's `psl` does.

### What has no Android counterpart

The desktop integration also answers HTTP authentication challenges (the
browser's own username/password dialog), with a stricter exact-host match and
a `realm` to tell protection spaces apart. Android's autofill framework never
sees those challenges — the browser answers them itself — so there is nothing
for pw to hook into. The `realm` field is still shown, edited and
round-tripped, so a vault shared with the desktop survives a write from this
app unchanged.

### Security model

| Threat | Mitigation |
|---|---|
| A malicious app claiming to be a web page on your bank's domain | A web address counts only from a package with a pinned or explicitly enrolled signing certificate. A sideloaded replacement under a known browser name with another certificate gets no fill or save offer. An explicitly enrolled or compromised browser can still lie about any website. |
| A malicious page harvesting a fill | Nothing is filled without you tapping the suggestion; there is no gesture-less path at all on Android, since the framework only asks when a field is focused. |
| An attacker-controlled subdomain of a site you have an entry for | Parent-domain matching is bounded by the Public Suffix List, and only ever climbs *up* from the visited host — an entry for `login.example.com` is never released to `example.com`. |
| Phishing domain (`github.com.evil.example`) | Suffix matching at label boundaries bounded by the Public Suffix List — only `evil.example`'s own entries can match. |
| A page provoking a passphrase prompt | The prompt is pw's own activity, opened by the framework only after you tap the suggestion. A page can put the *suggestion* in front of you; it cannot type on it. |
| A compromised browser reading the vault | The browser never receives the vault, the passphrase, or any entry it did not match — only the username and password of the entry you picked, for the field you picked it in. |
| Adjacent forms or frames causing the wrong account to be filled | Exactly one candidate field must be focused. On Android 9 (API 28), this requires the browser to report the node focus flag; browsers that omit it receive no offer. Android 10+ uses the framework focused ID. Pairing requires the same eligible origin and enclosing HTML form, or immediate parent container when no form is reported. Multiple password fields (including password confirmation on registration forms) are refused when the username is focused; focusing the intended password still works. Ambiguous usernames are omitted. Missing container metadata permits only a focused password, without a username. A username field that identifies itself outranks position; when none does, the nearest text input preceding the password in the same form is used, never one after it and never one in another form or origin. These boundaries depend on the form/container tree the browser reports; a flattened tree cannot distinguish unreported forms. |
| A cross-origin iframe collecting the outer page's credential | The origin is taken from the password field's own node, not from the page as a whole, and a username field on a different origin is dropped rather than filled. A new origin declaration replaces both scheme and domain, including missing components. A field's own origin annotation — browsers attach one to each field of a form that spans frames — settles that field's origin but not which form it belongs to; a document boundary still resets the enclosing form for everything below it, and pairing requires the same origin as well as the same container, so neither check alone carries the boundary. |
| Another app reading the vault file | It lives in the app's private storage, owner-only, and is never backed up to the cloud or transferred to a new device (`backup_rules.xml`). |
| Screenshots, the recents thumbnail, screen recording | Every pw window sets `FLAG_SECURE`. |
| A shoulder-surfer, screenshot, or Logcat reader observes the refusal diagnostic | It is off by default and holds no field contents and no entry names — the requesting package, the claimed origin, a field count and the decision. While on, it names hosts you focused a password field on both in pw's memory and under the `pw-autofill` Logcat tag. Turning it off clears pw's copy; Android controls Logcat retention. |
| Clipboard sniffers | The autofill path does not use the clipboard at all. A password copied by hand is flagged sensitive (kept out of the system clipboard preview and history on Android 13+) and cleared after the timeout in Settings or when the vault locks. If Android prevents safe ownership verification while pw is backgrounded, clearing waits until pw next enters the foreground rather than overwriting a newer clip. |

## File format and recovery

The vault is a standard
[scrypt encrypted-data format](https://github.com/Tarsnap/scrypt/blob/master/FORMAT)
(version 0) file: scrypt KDF (`N=2^17, r=8, p=1` when writing), AES-256-CTR
encryption and HMAC-SHA256 integrity protection. Inside is the same small JSON
document the desktop writes:

```json
{"version":1,"entries":[{"name":"github.com","username":"mikael","password":"..."}]}
```

Because the container is the standard format, the vault can always be
recovered without pw, using the common
[scrypt](https://www.tarsnap.com/scrypt.html) tool:

```sh
scrypt dec pw.scrypt
```

Export the file with **Settings → Export encrypted vault** first. The
known-answer fixture in `app/src/test/resources/known_answer.scrypt` is the
desktop repository's own, generated by scrypt 1.3.2, and `InteropTest` fails
the build if this app can no longer read it.

Writes are atomic (write to `pw.scrypt.tmp`, fsync, rename). Ordinary edits keep
the previous version as `pw.scrypt.bak`; rotation and import instead retain a
snapshot of the incoming vault under its passphrase. A crash mid-write can never
leave a truncated vault.

`N=2^17` needs about 128 MiB to open the vault and takes a second or two on a
current phone. **Settings → scrypt cost** lowers it for a device that cannot
manage that — at the cost of a cheaper offline attack on the file, and note
that a vault written with a lower cost still opens fine on the desktop.

## Security notes, honestly

- **The decrypted vault is held in memory while it is unlocked.** The CLI
  re-derives the key from a freshly typed passphrase on every command; a phone
  cannot ask for a 30-character passphrase per operation. Auto-lock bounds how
  long — five minutes of inactivity by default, and **Lock when pw leaves the
  screen** makes it immediate. The window is enforced by a timer, not only when
  something next reads the vault, so a screen left open on a revealed password
  relocks on time; a process that was frozen or dozing may run the timer late,
  so the window is re-checked whenever the app returns to the foreground. That second setting is off by default because
  the autofill service shares this process: with it on, every fill in a
  browser asks for the passphrase again.
- **Secrets are not reliably wiped from memory.** `Passphrase` holds UTF-8
  bytes and wipes them, and the decrypted plaintext buffer and derived keys are
  wiped, but a password is a `String`, and neither ART nor the JVM lets a
  `String`'s backing array be cleared — the JSON parser would have made its own
  copies anyway. pw-android relies on process isolation and a short auto-lock
  instead. The desktop's `Zeroizing`/`ZeroizeOnDrop` guarantee has no honest
  equivalent here, so it is not claimed.
- **Fingerprint unlock trades the passphrase for the device's biometric gate.**
  It is off by default. When on, the passphrase is wrapped with an AES-GCM key
  in the Android Keystore (hardware-backed where the device has a TEE) marked
  `setUserAuthenticationRequired`, so the key cannot be used without a fresh
  authentication, and `setInvalidatedByBiometricEnrollment`, so enrolling a new
  fingerprint destroys it rather than granting the new finger access. Only the
  wrapped bytes are stored. Changing the master passphrase turns it off, since
  the wrapped copy would no longer open the vault.
- **A clipboard history manager may keep a copy pw cannot reach.** The clip is
  flagged sensitive, which keeps it out of the system's own clipboard history
  on Android 13 and later, but a third-party clipboard manager is not obliged
  to honour that. The autofill path avoids the clipboard entirely, so prefer it
  where it works.
- The app requests **no network permission**, so it cannot leak the vault even
  if it wanted to.

## Layering

The library half mirrors the desktop's, layer for layer, so the two can be read
side by side:

| pw-android | pw |
|---|---|
| `crypto/ScryptFormat.kt` | `src/scrypt_format.rs` |
| `vault/Vault.kt`, `vault/PasswordEntry.kt`, `Secret`, `Passphrase` | `src/vault.rs` |
| `data/Matching.kt`, `data/Validation.kt`, `data/PasswordGenerator.kt`, `data/PwRepository.kt` | `src/lib.rs` |
| `ui/**`, `MainActivity` | `src/main.rs` (the CLI) |
| `autofill/**` | `src/bin/pw-browser-host/` + `webextension/` |

Errors are layered the same way too: `ScryptFormatException` →
`VaultException` → `PwException`, with wrong-passphrase, corrupt-vault and I/O
kept distinct.

## License

Copyright 2026 Mikael Ståldal.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
