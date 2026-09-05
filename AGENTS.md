# Project agent instructions

These rules apply to every coding task in this repo. They are not
suggestions — follow them in order.

## 1. Before touching any library or package

Never add, upgrade, or rely on a dependency based on memory alone. Before
using a library you haven't already verified in this session:

1. Confirm it actually exists and check its current version:
   - npm: `npm view <package> version` or check npmjs.com
   - PyPI: `pip index versions <package>` or check pypi.org
   - Cargo/Go/etc: check the relevant registry
2. Check it's not deprecated/archived/yanked — look at the last commit date
   and open issues on its GitHub repo, or search the web for
   "<package> deprecated" / "<package> alternative".
3. If it's already in this repo's lockfile/manifest, just confirm the
   version pinned there still matches what you're about to use — don't
   assume the version you remember from training is the one installed.
4. If you cannot verify a library in the current environment (no internet,
   no registry access), say so explicitly instead of proceeding as if you
   checked.

Skipping this step and later finding out the package doesn't exist, is
abandoned, or has a breaking API change is a failure mode to actively avoid.

## 2. The task loop

For every non-trivial task (bug fix, feature, refactor), follow this loop:

1. **Analyze** — read the actual relevant files before proposing a fix.
   Don't reason from a summary of the file; open it. Identify the smallest
   set of lines responsible for the problem.
2. **Plan the smallest fix** — state which file(s) and which line range(s)
   you're going to touch, and why. If the fix requires touching more than
   ~2-3 files, pause and explain why before proceeding.
3. **Apply** — make the change. Prefer a targeted patch (equivalent to
   `str_replace` / a small diff) over rewriting the file. If the bug is on
   line 215, the fix touches line 215 (and directly adjacent lines it
   depends on) — not the whole function, not the whole file, unless the bug
   genuinely requires it.
4. **Test** — run the existing test suite, or the specific test/repro for
   this change. Don't declare success without running something.
5. **If it fails** — do not guess-and-retry blindly. Read the actual error
   or stack trace, identify the exact line/condition that's wrong, and fix
   only that. Repeat from step 4.
6. **If it passes** — report back exactly what changed: file path, line
   numbers, and a one-line reason. Don't say "I fixed the bug" without
   saying where.

## 3. Diff size discipline

- A fix should be proportional to the bug. A one-line null-check bug gets a
  one-line fix, not a refactor of the surrounding function.
- If you notice unrelated issues while working (bad naming, dead code,
  missing types), note them at the end of your report as suggestions —
  don't fix them inline unless asked.
- Never reformat a whole file (re-indent, reorder imports, change quote
  style) as a side effect of an unrelated fix. That inflates the diff and
  hides the real change.

## 4. When you're not sure

- If a test doesn't exist for the code you're changing, say so and offer to
  add one — don't silently skip verification.
- If you had to guess at intent because the request was ambiguous, state
  the assumption you made in your final report.
- If you could not verify something (a library's behavior, whether a config
  flag exists, whether an API endpoint accepts a field), say that plainly
  instead of presenting a guess as fact.

## 5. Project conventions

- Language/framework: Kotlin / Android (minSdk 24, targetSdk 34)
- Build tool: Gradle 8.4 (Android Gradle Plugin 8.1.4)
- Build command: `gradle assembleDebug` / `./gradlew assembleDebug`
- Key directories and what lives where:
  - `app/src/main/java/com/mungil/browser/` : Main application Kotlin code (MainActivity, CobaltDownloader, NativeStreamDownloader)
  - `app/src/main/res/` : Layout XMLs, drawables, styles, and Android resources
  - `app/src/main/assets/` : Bundled offline assets (e.g. `eruda.js`)
- Things never to touch directly:
  - `app/mungil.keystore` : Permanent cryptographic signing key for seamless APK upgrades
  - Signing configuration in `app/build.gradle.kts`
