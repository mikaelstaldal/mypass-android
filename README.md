# pw for Android

A password manager for Android that keeps your passwords in a single
encrypted file, using the same **standard scrypt encrypted-data format** as
[`pw`](../pw), the command line password manager it is the companion of. The
same `pw.scrypt` file works on both: copy it across and every entry, username,
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

| Screen | The `pw` command it replaces |
|---|---|
| Unlock / Create vault | `pw init` |
| Entry list, with search | `pw list [PATTERN]` |
| Entry detail — copy username, copy or reveal password | `pw get <name> [--show]` |
| Add entry | `pw add <name> [username] …` |
| Edit entry (with "keep the existing password") | `pw update <name> … [--keep-password]` |
| Remove entry, after confirmation | `pw remove <name>` |
| Generate password | `pw generate` |
| Settings → Export decrypted JSON | `pw export` |
| Settings → Use pw for autofill | `pw install-browser` |

Plus what a phone needs and a terminal does not: an auto-lock timer, optional
fingerprint unlock, and importing and exporting the vault file through the
system file picker.

The **username** is a free-form label stored alongside the password; it may be
omitted. Generated passwords use a cryptographically secure random number
generator (`SecureRandom`, OS-seeded) without modulo bias, and default to 16
characters of letters, digits and `-` — the same default as the desktop.

## Getting your desktop vault onto the phone

1. Copy `~/pw.scrypt` to the phone (over a cable, not through a cloud drive if
   you can help it — the file is encrypted, but there is no reason to hand
   anyone a copy to grind on offline).
2. Open pw, tap **Import an existing pw.scrypt vault**, pick the file and type
   the master passphrase.

The import is verified before anything is written: it must decrypt and parse,
so a mistyped passphrase or a truncated copy is refused while the existing
vault is still there. It is then re-encrypted under this device's configured
KDF cost, and the previous vault — if there was one — is kept as
`pw.scrypt.bak`.

Going the other way, **Settings → Export encrypted vault** writes the file out
through the system file picker, unchanged and still encrypted.

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

When the vault is locked, a fill request shows a single **Unlock pw** entry
instead of a password. Tapping it opens pw's own passphrase screen — outside
the browser, which cannot see or drive it — and the fill then lands where you
asked for it. Nothing about the vault is revealed by the offer: pw cannot tell
whether it has an entry for a site until it is open, so the offer necessarily
comes before that is known.

### Which browsers

`autofill/Browsers.kt` lists the packages whose claim of a web address is
believed: the Firefox family and its rebuilds, the Chromium family and its
rebuilds, Samsung Internet, and a handful of others. This is the Android
counterpart of the desktop host's `allowed_extensions` pin.

If your browser is not on the list, pw will silently decline to fill in it.
Add its package name under **Settings → Extra browser packages** rather than
going without — a package name is a weak trust anchor, but a password manager
that does nothing is a worse one.

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
| A malicious app claiming to be a web page on your bank's domain | A web address counts only from a package pw recognises as a browser. Anything else gets no datasets at all. |
| A malicious page harvesting a fill | Nothing is filled without you tapping the suggestion; there is no gesture-less path at all on Android, since the framework only asks when a field is focused. |
| An attacker-controlled subdomain of a site you have an entry for | Parent-domain matching is bounded by the Public Suffix List, and only ever climbs *up* from the visited host — an entry for `login.example.com` is never released to `example.com`. |
| Phishing domain (`github.com.evil.example`) | Suffix matching at label boundaries bounded by the Public Suffix List — only `evil.example`'s own entries can match. |
| A page provoking a passphrase prompt | The prompt is pw's own activity, opened by the framework only after you tap the suggestion. A page can put the *suggestion* in front of you; it cannot type on it. |
| A compromised browser reading the vault | The browser never receives the vault, the passphrase, or any entry it did not match — only the username and password of the entry you picked, for the field you picked it in. |
| A cross-origin iframe collecting the outer page's credential | The origin is taken from the password field's own node, not from the page as a whole, and a username field on a different origin is dropped rather than filled. |
| Another app reading the vault file | It lives in the app's private storage, owner-only, and is never backed up to the cloud or transferred to a new device (`backup_rules.xml`). |
| Screenshots, the recents thumbnail, screen recording | Every pw window sets `FLAG_SECURE`. |
| Clipboard sniffers | The autofill path does not use the clipboard at all. A password copied by hand is flagged sensitive (kept out of the system clipboard preview and history on Android 13+) and cleared after the timeout in Settings. |

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

Writes are atomic (write to `pw.scrypt.tmp`, fsync, rename), and the previous
version is kept as `pw.scrypt.bak` next to it. A crash mid-write can never
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
