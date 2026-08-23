# Development

Technical notes for building, understanding, and testing SMS Forwarder. For what the app
does and how to use it, see [README.md](README.md).

---

## Build

Requires JDK 17 and the Android 35 SDK. Android Studio is optional — everything works
from the command line.

```bash
./gradlew assembleDebug
```

```bash
./gradlew test
```

```bash
./gradlew connectedAndroidTest
```

Point `local.properties` at your SDK (`sdk.dir=...`), or let Android Studio create it.

---

## Architecture

```
SmsReceiver ──parse only──▶ MessageProcessor ──▶ SettingsStore (history)
(manifest BroadcastReceiver)      │
                                  ├──▶ WorkManagerDispatcher ──▶ ForwardWorker
Simulate screen ──────────────────┘                                 │
(same entry point)                                                  ▼
                                                    HttpForwarder / EmailForwarder
                                                          / SmsRelayForwarder
```

The central design decision: **the BroadcastReceiver does nothing but parse.** It turns
the intent into an `IncomingSms` and hands it to `MessageProcessor`. All matching and
dispatch logic lives in that one directly-callable class, which is why the simulator
screen and the unit tests can exercise the entire pipeline without a radio or a SIM.

| File | Role |
|------|------|
| `sms/SmsReceiver.kt` | PDU decoding and multi-part concatenation. No matching, no I/O. |
| `core/MessageProcessor.kt` | The whole decision layer. Callable from tests and the simulator. |
| `core/RuleMatcher.kt` | Regex matching. An invalid regex never matches and never throws. |
| `core/TemplateRenderer.kt` | `{sender}` `{body}` `{timestamp}` `{rule_name}` substitution, JSON-escaping when the target is JSON. |
| `core/Defaults.kt` | Seed rules and forwarders. |
| `core/DeliveryKey.kt` | Destination + rendered payload, used to suppress duplicate deliveries. |
| `core/Throwables.kt` | Flattens a cause chain into one readable line for History. |
| `model/Model.kt` | Every data type, in one file. |
| `data/SettingsStore.kt` | The state: rules, forwarders, history, appearance, known numbers, master switch. |
| `data/JsonPrefs.kt` | The storage primitive — one encrypted preferences file of opaque strings. |
| `data/Migrations.kt` | On-load fix-ups, as pure functions so they can be unit-tested. |
| `data/ConfigBackup.kt` | Export/import. Redaction of credentials lives here. |
| `data/ContactResolver.kt` | Optional reverse lookup from a number to a contact name. |
| `data/FailureNotifier.kt` | Notifies on terminal delivery failures only. |
| `forwarder/*.kt` | The three `Forwarder` implementations plus the factory. |
| `work/ForwardWorker.kt` | One WorkManager job per (rule → forwarder) pair. |
| `ui/MainActivity.kt` | Four bottom tabs; Settings hosts sub-pages with back handling. |
| `ui/Theme.kt` | Material You, or a scheme derived from the user's accent colour. |
| `ui/SettingsScreen.kt` | Settings hub, appearance picker, About. |
| `ui/Splash.kt` | Cold-start logo animation, shared logo composable. |
| `ui/*.kt` | The remaining Compose screens: Rules, Forwarders, History, Simulate, Permissions. |

### Theming

`AppTheme` uses Material You on Android 12+ when enabled, otherwise derives a scheme from
the stored accent colour. Material's own algorithm needs the material-color-utilities
library; `accentScheme` is a ~30-line stand-in that blends towards white and black instead.

The important part is that the user can pick **any** colour, including pale yellow or
white, and `primary` doubles as a text colour on light surfaces. So the derivation
darkens (or lightens, in dark mode) the accent until it clears the WCAG AA 4.5:1 contrast
ratio against the surface. `ThemeTest` asserts that for a set of deliberately awkward
seeds — without it, choosing a pale accent produced invisible buttons, which is exactly
what happened during development.

### Storage

Everything lives in a single `EncryptedSharedPreferences` file — not just SMTP passwords
and webhook headers, but the history log too, since that contains the OTP codes
themselves. Values are JSON blobs; there is no database. Cloud backup and device transfer
are disabled in `res/xml/data_extraction_rules.xml`.

### Reliability

- The receiver never blocks on network I/O. It parses, logs, and enqueues.
- Delivery runs in WorkManager: 5 attempts, 30s exponential backoff (~8 minutes total).
- Network-backed forwarders carry a `CONNECTED` constraint. The SMS relay does not — it
  goes over the radio, not the network.
- Misconfiguration throws `ForwarderConfigException` and is **not** retried; History shows
  `FAILED — not retried — <reason>` immediately.
- `ForwardWorker` wraps everything in an outer catch. A forwarder that throws instead of
  returning `Result.failure` would otherwise leave the attempt stuck at PENDING forever.
- History is capped at 300 entries, newest first.

### Duplicate delivery suppression

Several rules can match one message. Without care that means several deliveries — a
duplicate on the recipient's phone and a second billable SMS.

`MessageProcessor` therefore deduplicates jobs by **delivery key**: destination *plus
rendered payload* (`core/DeliveryKey.kt`), not by forwarder ID. Forwarder ID would be too
coarse in one direction and too blunt in the other — two separate relay instances aimed at
the same number would still double-send, while two webhooks on one URL with different
body templates would be wrongly collapsed.

| Situation | Result |
|---|---|
| Two rules → same relay, same number, same text | **one** send |
| Two relay instances → same number, same text | **one** send |
| Two relays → different numbers | both send |
| Relay + webhook + email | all three send |
| Two webhooks → same URL, different body templates | both send |
| Same destination, different message templates | both send |

Deduplicating too eagerly silently drops a forward, which is worse than a duplicate, so
keys must match exactly and everything errs towards sending. A template containing
`{rule_name}` renders differently per rule, so those forwarders never deduplicate — by
design, since the messages genuinely differ.

Rules are evaluated in list order and the first match wins attribution; History still
records *every* rule that matched, so nothing is hidden.

### Decisions worth not undoing

Each of these looks like it could be simplified. Each was written this way for a reason
that cost something to learn.

**Sender matching is include + exclude, not one regex.** Evaluated as
`(include empty OR any include matches) AND no exclude matches`. That single form covers
"any", "these senders", and "everything except". Rules written before this carry a legacy
`senderPattern`, migrated on load — dropping it would silently widen a rule to *any*
sender, which is the dangerous direction to fail in.

**A contact criterion stores every number on that contact.** Storing only the number the
picker returned meant excluding a contact named SPAM with five numbers blocked one and
let four through, silently. Collecting the rest needs `READ_CONTACTS`, which is why the
permission is requested at that moment rather than up front.

**Phone numbers match by suffix, but only at seven digits or more.** `+18064755252` and
`8064755252` are the same phone; `37268` is a short code and must match exactly, or it
would collide with every number ending in those digits.

**The bank pattern uses phrases, not words.** A bare `did you` matched "Did you get the
$20 back?" and a bare `spent` matched "I spent $50 on dinner". `did you <verb>` and
`you spent` keep the bank phrasings and drop the conversational ones. Eight ordinary
money texts are pinned as negative tests.

**Duplicate suppression keys on destination + rendered payload,** not forwarder ID. ID is
simultaneously too coarse (two relays aimed at the same number both fire) and too blunt
(two webhooks on one URL with different bodies get collapsed). It errs towards sending:
a duplicate is annoying, a suppressed forward is a missed code.

**Pausing still logs.** A paused message that vanished would be indistinguishable from
one that matched nothing, so it is recorded and marked.

**Only terminal failures notify.** A retrying attempt may still succeed, and a
notification per attempt trains you to dismiss them.

**Backups exclude history always and credentials by default.** History holds the codes
themselves. `withoutSecrets` is `internal` rather than `private` specifically so the
tests exercise the real redaction — an earlier test duplicated the logic and would have
passed while production leaked.

### Design notes

`Forwarder.send` returns `Result<String>`, not `Result<Unit>`. The success value is the
detail line History displays (`HTTP 200`, `carrier accepted 2/2 part(s); delivered`).
Without it there is no way to record the difference between "the API call didn't throw"
and "the carrier accepted it" — exactly the distinction the SMS relay needs to report.
`send` also takes a `ForwardRequest` (message + rule name) rather than a bare message,
because `{rule_name}` is a template placeholder.

The SMS relay reports success only after the platform delivers the per-part `sentIntent`
result. Delivery reports are then awaited best-effort for 45s and appended to the detail
line; many carriers never send them, which is reported as "no delivery report" rather
than as a failure.

---

## Testing

Four layers, cheapest first. Layers 1–3 need no SIM.

### 1. In-app simulator

**Simulate** tab → type a sender and body → *Simulate incoming SMS*. Calls
`MessageProcessor.process()` — the identical call the BroadcastReceiver makes — so rule
matching, dispatch, retries and the History entry all run for real. Only PDU decoding is
skipped. Entries are tagged as simulated.

### 2. Unit tests (JVM)

```bash
./gradlew test
```

- `MessageProcessorTest` — a table of sender/body inputs against expected matches, plus
  dispatch behaviour (disabled rules and forwarders, unknown IDs, multiple rules).
- `TemplateRendererTest` — substitution, JSON escaping (the rendered body is re-parsed by
  a strict JSON parser), and `$`/`\` in message bodies.

### 3. Instrumented tests

```bash
./gradlew connectedAndroidTest
```

`SmsReceiverTest` hand-builds GSM SMS-DELIVER PDUs (UCS2 payload, concatenation UDH for
the multi-part case), packs them into an intent exactly as the platform does, and
dispatches to the real `SmsReceiver`. This validates PDU decoding and multi-part
concatenation, which the simulator skips.

`TemplateRendererAndroidTest` exists because **JVM unit tests cannot catch Android regex
bugs**. Android's `java.util.regex` is ICU-backed and stricter than the JVM's: an
unescaped `}` compiles fine under JUnit and throws `PatternSyntaxException` on device.
Inside an object initialiser that becomes an `ExceptionInInitializerError` at first touch
— which is exactly the bug that shipped in the first build, crashing the app and leaving
history entries stuck at PENDING. **Any regex compiled at class-init time needs an
on-device test, not just a JVM one.**

### 4. Emulator end-to-end

The emulator fires a real `SMS_RECEIVED` broadcast from the OS itself:

```bash
adb emu sms send 15551234567 "Your verification code is 458213"
```

Or: emulator **Extended controls → Phone → SMS message**. Physical devices without a SIM
can't receive SMS at all, so this is emulator-only.

### Testing the forwarders

**Webhook** — point it at [webhook.site](https://webhook.site) for a disposable URL that
shows the exact body and headers received. The "Send test message" button fires a
synthetic message through one forwarder, bypassing rule matching.

**Email** — run a local SMTP catcher instead of sending real mail:

```bash
docker run --rm -p 1025:1025 -p 8025:8025 mailhog/mailhog
```

Configure host = your machine's LAN IP (from the standard emulator, `10.0.2.2` reaches
the host), port `1025`, no username (auth is skipped when the username is blank), STARTTLS
off. Rendered mail appears at `http://localhost:8025`.
[smtp4dev](https://github.com/rnwood/smtp4dev) works the same way.

**SMS relay** — the emulator does more here than expected: its virtual modem accepts
`sendMultipartTextMessage` and fires both the `sentIntent` and `deliveryIntent` callbacks,
so a test send reports `OK — carrier accepted 1/1 part(s); delivered` without a SIM. What
it can't tell you is whether a real carrier accepts your destination number, or whether
the message actually arrives. On a physical device:

1. **Point it at a low-stakes number first** — not the phone you actually read — so
   iterating doesn't spam it.
2. **Watch History, not the receiving phone.** It distinguishes carrier acceptance from
   carrier rejection, which is the only way to tell them apart without eyeballing the
   other handset.
3. **Check per-message cost** for the destination before leaving it enabled long-term.

---

## Troubleshooting

### `Unable to establish loopback connection` when Gradle starts

Seen on at least one Windows machine. Java NIO's `Selector.open()` builds its wakeup pipe
from an **AF_UNIX socket pair** (`WEPollSelectorProvider` → `PipeImpl` →
`UnixDomainSockets.connect0`). If that socket file lands in a directory where AF_UNIX
fails, the call returns `WSAEINVAL` and Gradle dies instantly with a misleading loopback
error. Every JVM tool is affected — Gradle, Maven, IntelliJ, Android Studio.

**Fix:** point `TEMP` and `TMP` at a different directory.

```powershell
$env:TEMP = "$env:USERPROFILE\jtmp"; $env:TMP = $env:TEMP
```

Notes from diagnosing it, so it isn't re-litigated:

- Plain TCP loopback bind+connect works fine, so it is **not** the firewall, **not**
  IPv4/IPv6, and **not** the hosts file.
- `-Djava.io.tmpdir` does **not** help — the JDK's internal pipe reads the `TEMP`/`TMP`
  *environment variables*.
- Reproduced identically on Temurin 17 and 21, so it isn't a JDK-version bug.
- The offending directory was `%LOCALAPPDATA%\Temp`; other paths, including longer ones
  and ones containing spaces, worked. It was not a reparse point; the underlying cause
  (ACL or filter driver) was never pinned down.

### Known issues

**Emulator SMS timestamps can be an hour off.** `adb emu sms send` encodes an SCTS field
that may not match the device clock. `IncomingSms.timestampMillis` is the *carrier's*
timestamp as decoded by `SmsMessage`, not the moment of receipt, so this is emulator PDU
generation rather than a parsing bug. To ignore carrier timestamps entirely, use
`System.currentTimeMillis()` in `SmsReceiver.parse`.
