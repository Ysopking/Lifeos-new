# LIFEOS B302–B312 — zeilengenauer Ausführungsplan

Planungsbasis: `main@5188f243c8b684495f9751c0f4f745620ae8836a`

> Die Zeilenangaben unten sind **exakt für diese Basis**. Nach jedem Merge verschieben sich Zeilennummern; deshalb steht zusätzlich immer ein Symbol-/Textanker dabei. Bei der Umsetzung gilt: erst Symbolanker prüfen, dann den geplanten Bereich ändern. Keine stale range blind anwenden.

## Ausführungsregel

- Kein produktiver Block wird auf einen nicht gemergten Vorgänger aufgebaut.
- Für jeden Exact-Main-Head: Core Fast + Android Debug + Emulator Recovery + Product Gold auf **demselben Head**.
- BuildStudio Candidate Host darf weiterhin `skipped` sein, solange kein Host-Candidate-Pfad betroffen ist.
- Schneller Modus: kleine, logisch zusammengehörige Subblöcke werden vorbereitet/gestackt; die volle Gold-Matrix läuft einmal am Paketende.
- B311 ist gestrichen; Zielkette: **B302 Rest → B303 → B304 → B305 → B306 → B307 → B308 → B309 → B310 → B312**.
- Externe Admin-Schuld bleibt sichtbar: `main` ist auf dieser Basis nicht branch-protected; die Ruleset-Datei existiert, die tatsächliche Aktivierung ist vor Abschluss B312 zu verifizieren.

---

# B302 Rest — Runtime-Monolith weiter schneiden

## B302.2 — `:core:runtime-thought`

### Verifizierter Ausgangspunkt
Die 11 Produktionsdateien unter `core/runtime/.../thought/` importieren **keine anderen `app.lifeos.core.runtime.*`-Pakete**. Direkte Abhängigkeiten sind nur `:core:model`, `:core:field`, Java/Kotlin und Coroutines. Damit ist der Split zyklusfrei möglich.

### Exakte Änderungen
1. `settings.gradle.kts:9-23` — Anker `include(`
   - direkt nach Zeile 14 (`:core:runtime-personal`) einfügen:
     `":core:runtime-thought",`

2. Neue Datei `core/runtime-thought/build.gradle.kts`, Zeilen 1–11:
   - Kotlin JVM Plugin
   - JVM 17
   - `implementation(project(":core:model"))`
   - `implementation(project(":core:field"))`
   - Coroutines Core 1.10.2
   - Kotlin test + Coroutines test
   - JUnit Platform

3. Gesamte Dateien verschieben, Inhalt unverändert:
   - `core/runtime/src/main/kotlin/app/lifeos/core/runtime/thought/DurableThoughtGraph.kt`
   - `ThoughtFingerprint.kt`
   - `ThoughtGraphAttention.kt`
   - `ThoughtGraphCompaction.kt`
   - `ThoughtGraphModels.kt`
   - `ThoughtGraphPersistence.kt`
   - `ThoughtGraphReducer.kt`
   - `ThoughtMatrixSnapshot.kt`
   - `ThoughtMatrixV2.kt`
   - `ThoughtNode.kt`
   - `ThoughtRelation.kt`
   nach `core/runtime-thought/src/main/kotlin/app/lifeos/core/runtime/thought/`.

4. Die 7 Tests unter `core/runtime/src/test/kotlin/app/lifeos/core/runtime/thought/` vollständig nach `core/runtime-thought/src/test/kotlin/app/lifeos/core/runtime/thought/` verschieben.

5. `core/runtime/build.gradle.kts:3-10` — Anker `dependencies {`
   - `api(project(":core:runtime-thought"))` direkt nach `:core:field` ergänzen, da verbleibende Runtime-Pakete Thought-Typen öffentlich verwenden.

6. `core/data/build.gradle.kts:9-16`
   - `implementation(project(":core:runtime-thought"))` ergänzen, weil Thought-Vault-/Repository-Code Thought-Typen direkt importiert.

7. `app/build.gradle.kts:28-36`
   - `implementation(project(":core:runtime-thought"))` ergänzen, falls App-Code Thought-Typen direkt referenziert; Compile-Check entscheidet, nicht transitive Sichtbarkeit annehmen.

8. `.github/scripts/ci-runtime-module-boundary-contract.sh:9-39`
   - neuer Modul-Existenzcheck
   - verbieten, dass `core/runtime/src/**/runtime/thought/**` übrig bleibt
   - `:core:runtime-thought` in `settings.gradle.kts` verlangen
   - exakte Source-/Test-Zahlen **11/7** prüfen
   - verbieten, dass `runtime-thought` von `:core:runtime` abhängt

9. `.github/scripts/ci-core-fast.sh:39-47`
   - nach `:core:runtime:test`: `./gradlew :core:runtime-thought:test --stacktrace`.

### DoD
- Kein Thought-Produktions-/Testfile mehr im Runtime-Monolith.
- Runtime-Thought hängt nicht von Runtime-Monolith ab.
- Core Fast grün; danach nächster B302-Subblock ohne Full-Gold-Zwischenlauf.

---

## B302.3 — `:core:runtime-informationasset` (zyklusfreier Kern)

### Split-Grenze
Von 21 Produktionsdateien werden 19 verschoben. Im Monolith bleiben zunächst:
- `informationasset/InformationAssetContextGate.kt` (importiert `convergence.AuditedCrossDomainBridge`)
- `informationasset/convergence/InformationAssetConvergenceCoordinator.kt` (importiert Convergence-Typen)

### Zu verschiebende Produktionsdateien
- `DomainContextPolicy.kt`
- `InformationAssetAssembler.kt`
- `InformationAssetCodec.kt`
- `InformationAssetIds.kt`
- `InformationAssetModels.kt`
- `InformationAssetPhotonFactory.kt`
- `InformationAssetRepository.kt`
- `InformationAssetRevisionIntegrity.kt`
- `InformationAssetRevisionVerifier.kt`
- `InformationAssetValidation.kt`
- `StandardInformationAssetValidation.kt`
- `StandardInformationDomains.kt`
- `code/CodeAuditInformationAsset.kt`
- `code/CodeChangeProposalInformationAsset.kt`
- `financial/FinancialInformationAsset.kt`
- `legal/LegalInformationAsset.kt`
- `organization/OrganizationInformationAsset.kt`
- `project/ProjectInformationAsset.kt`
- `scientific/ScientificInformationAsset.kt`

### Tests
Von 15 Tests werden 13 mitverschoben. Im Monolith bleiben:
- `DomainContextFirewallTest.kt`
- `convergence/InformationAssetConvergenceCoordinatorTest.kt`

### Build-/CI-Zeilen
1. `settings.gradle.kts:9-23`: `":core:runtime-informationasset",`
2. neue `core/runtime-informationasset/build.gradle.kts`: JVM17, `:core:model`, `:core:field`, Coroutines soweit Compiler verlangt.
3. `core/runtime/build.gradle.kts:3-10`: `api(project(":core:runtime-informationasset"))`.
4. `core/data/build.gradle.kts:9-16`: direkte `implementation` auf neues Modul.
5. `app/build.gradle.kts:28-36`: direkte `implementation` nur wenn Compile dies erfordert.
6. `.github/scripts/ci-runtime-module-boundary-contract.sh:9-39`: 19/13 zählen; die zwei expliziten Adapter-Dateien im Monolith als einzige Allowlist zulassen.
7. `.github/scripts/ci-core-fast.sh:39-51`: neuen Modultest ergänzen.

### DoD
- Kein Rückimport von `:core:runtime` aus dem neuen Modul.
- Nur die zwei dokumentierten Convergence-Adapter bleiben im Monolith.

---

## B302.4 — Runtime-Split-Budget schließen

1. `.github/scripts/ci-runtime-module-boundary-contract.sh:9-39` komplett auf drei extrahierte Module erweitern:
   - runtime-personal = 6 Main / 8 Tests
   - runtime-thought = 11 / 7
   - runtime-informationasset = 19 / 13
2. Neuer negativer Scan: verschobene Package-Pfade dürfen im Monolith nicht wieder entstehen.
3. `.github/scripts/ci-core-fast.sh:39-51`: alle drei Modul-Tests vor Language/Data/App.
4. Full Gold **einmal** auf dem finalen B302.4-Head.

---

# B303 — Unified Vault

## B303.1 — zentrale Vault-API

### Neue Dateien
- `core/data/src/main/kotlin/app/lifeos/core/data/security/UnifiedVault.kt`
  - Zeilen 1–35: `VaultNamespace`, `VaultObjectKey`, `VaultRecordSpec`
  - 36–60: `VaultReadResult`
  - 61–90: Interface `UnifiedVault` mit `read/write/delete/exists`
- `AndroidKeystoreUnifiedVault.kt`
  - ausschließlich hier: AndroidKeyStore, AES/GCM, AtomicFile, AAD, Größenlimits
  - physischer Pfad + Namespace + Objekt-ID + Codec-/Container-Version müssen AAD bilden.

### Bestehende Helfer
- `EncryptedLedgerVaultSupport.kt:18-136` — Anker `internal object EncryptedLedgerVaultSupport`
  - Key-/Cipher-/AtomicFile-Logik nicht duplizieren; als temporärer Adapter auf UnifiedVault reduzieren.
- `VersionedPathBoundVaultSupport.kt:18-151` — Anker `internal object VersionedPathBoundVaultSupport`
  - bestehende Pflicht-AAD-Semantik erhalten; Implementierung auf UnifiedVault-Primitive umbiegen.

### Tests
- neue `UnifiedVaultTest.kt`: falscher physischer Pfad, falsche Objekt-ID, falsche Revision, falscher Namespace, falsche Codec-Version, Truncation, Bitflip.
- bestehend `VersionedPathBoundVaultSupportTest.kt` muss unverändert weiter grün sein.

---

## B303.2 — kritische Repositories zuerst

Exakte aktuelle Bereiche:
- `EncryptedOwnerPolicyRepository.kt:23-229`; Key an 28-30, alle read/write-Helfer auf UnifiedVault.
- `EncryptedDeepSearchMissionRepository.kt:19-219`; Key an 24, Events + Head mit path-bound AAD.
- `EncryptedDeepSearchCheckpointRepository.kt:24-107`; Key an 26, read an 68-78, write darunter.
- `EncryptedResourceBudgetRepository.kt:19-123`; Key an 21-23, read ab 78.

Regel: bestehende Dateinamen, Revisionen, CAS- und Migrationssemantik bleiben unverändert; nur Kryptografie-/Container-Autorität wird vereinheitlicht.

---

## B303.3 — restliche Encrypted-Repositories migrieren

Wellenweise nach Domäne, ohne Schema-Sprung:
1. cognition/boot/checkpoint/task
2. capability/evolution/health/trace
3. goal/learning/module/informationasset
4. world/worldmodel/thought/field
5. root stores + agency/artifact/escalation/extension/language/resource

Nach jeder Welle Core/Data-Tests; Full Gold erst nach B303.4.

---

## B303.4 — Vault-Exklusivitätsvertrag

Neue `.github/scripts/ci-unified-vault-contract.sh`:
- `Cipher.getInstance`, `KeyStore.getInstance("AndroidKeyStore")`, `KeyGenerator.getInstance(...AndroidKeyStore...)` in `core/data/src/main` nur in `AndroidKeystoreUnifiedVault.kt` erlauben.
- direkte neue Nutzungen von `EncryptedLedgerVaultSupport.loadOrCreateKey` verbieten.
- alle Persistenzpfade müssen AAD/Path-Binding verwenden.
- in `.github/scripts/ci-core-fast.sh:21-47` nach Wrapper/Main-Authority einhängen.
- in `.github/scripts/ci-gold-coverage-contract.sh:54-60` als verpflichtenden Security-Contract prüfen.

Full Gold auf finalem B303-Head.

---

# B304 — Permission Profiles

## B304.1 — typisierte Profile

Neue Datei `app/src/main/java/app/lifeos/next/PermissionProfile.kt`:
- `CORE`
- `PERSONAL_DATA`
- `MEDIA_IMPORT`
- `VOICE`
- `NOTIFICATIONS`
- `STORAGE_INTELLIGENCE`
- `WEB_DEEPSEARCH` (Owner-Policy-/Network-Effekt; keine Android-Runtime-Permission vortäuschen)

Jedes Profil besitzt:
- Runtime-Permissions
- optionale Special-Access-Bedingung
- stabilen Schema-Fingerprint
- Owner-initiated Request Policy.

## B304.2 — Controller umstellen

`PrivatePermissionController.kt:15-105`
- Zeilen 15-43: flache Listen durch `missing(profile)` + `missing(profiles)` ersetzen.
- 45-61: Broad-file-access als Special-Access von `STORAGE_INTELLIGENCE` modellieren.
- 63-94: Request-Marker pro Profil/Schemaversion statt zwei globalen Keys.
- 96-105: Schema aus Profilen deterministisch erzeugen.
- 113-118: Preferences-Keys auf `permission-profile:<id>:schema` normalisieren.

`LifeOsApplication.kt:276-290`
- neue dünne Profile-Delegates.
- alte öffentliche Methoden höchstens als temporäre Kompatibilitätswrapper, danach entfernen.

`ChatMainActivity.kt:140-177`
- Profile in deterministischer Reihenfolge anfordern.
- Special Access separat; nie Broad Storage in normale `requestPermissions` mischen.

`AndroidSharedFilesInitialDataSource.kt:105-123`
- bestehende Runtime-Autorisierung bleibt letzte technische Schranke; Profil entscheidet nur, ob/was angefragt wird.

`.github/scripts/ci-private-debug-security.sh:18-39,87-123`
- Manifest-Permission muss genau einem Profil/Owner-Policy-Pfad zugeordnet sein.
- INTERNET bleibt JIT OwnerEffect, keine Baseline-Freigabe.

---

# B305 — BuildStudio wrapper-only

## B305.1 — ein einziger produktiver Gateway

Neue `core/runtime/.../buildstudio/BuildStudioGateway.kt`:
- `status()`
- `run(spec)`
- `expand(request)`
- keine Install-/Registry-Mutation nach außen.

`BuildStudioHostRuntime.kt`
- Zeilen 33-45: `BuildStudioHostAdapter` bleibt Host-SPI.
- Zeilen 52-171: `BuildStudioHostProcessRegistry` auf module-internal reduzieren.
- Zeilen 108-147: run/expand nur über Gateway erreichbar.
- Zeilen 203-204: Capability-/Subsystem-ID bleiben zentrale Konstanten.

`GenesisCapabilityExpansionRuntime.kt`
- Zeilen 5-8: Registry-Import durch Gateway ersetzen.
- Zeile 52: Capability-ID über Gateway/öffentliche Contract-Konstante.
- Zeilen 129-181: `BuildStudioHostProcessRegistry.expand(request)` an Zeile 147 durch Gateway-Aufruf ersetzen.

`host/buildstudio/build.gradle.kts:5-8`
- nach B302-Modularisierung nur auf das BuildStudio-API-Modul + nötige Contracts hängen, nicht auf unnötige Runtime-Implementierung.

## B305.2 — CI-Verbot für Bypass

Neue `.github/scripts/ci-buildstudio-wrapper-contract.sh`:
- `BuildStudioHostProcessRegistry` außerhalb des BuildStudio-Moduls verbieten.
- `JvmBuildStudioHost` außerhalb `host/buildstudio` verbieten.
- `IsolatedBuildWorkspace`, `BuildGateRunner`, `BuildArtifactCollector` dürfen produktiv nur im Host/BuildStudio-Pfad vorkommen.
- `GenesisCapabilityExpansionRuntime` muss Gateway importieren.

Einbindung in `ci-core-fast.sh` und `ci-gold-coverage-contract.sh`.

---

# B306 — Supply-Chain-Hardening

## B306.1 — vorhandene Contracts verschärfen

`.github/scripts/ci-action-pin-contract.sh:14-36`
- zusätzlich verbieten: Docker actions mit mutable tags, remote reusable workflows ohne 40-char SHA.
- Kommentar-Tag darf nur Dokumentation sein; Autorität ist SHA.

`.github/scripts/ci-gradle-wrapper-contract.sh:4-40`
- vorhandene Wrapper-/Distribution-Hashes beibehalten.
- zusätzlich `gradle-wrapper.properties` gegen unerwartete Hosts/Parameter prüfen.

`settings.gradle.kts:1-7`
- Repositories bleiben zentral; Repository-Allowlist explizit prüfen.

## B306.2 — Gradle Dependency Verification + Locking

Neue/erzeugte Dateien:
- `gradle/verification-metadata.xml`
- Lockfiles pro Configuration bzw. zentraler Lock-State gemäß Gradle 9.3.1.

Root `build.gradle.kts:1-6` und Modul-Buildfiles:
- keine dynamischen Versionen, keine `+`, keine SNAPSHOT-Abhängigkeiten.

Neuer `ci-dependency-integrity-contract.sh`:
- Verification-Metadata vorhanden/nonempty
- Lock-State vorhanden
- dynamische Versionen verbieten
- Plugin-/Dependency-Repositories außerhalb Allowlist verbieten.

## B306.3 — Workflow least privilege

`.github/workflows/core-fast.yml:12-17`,
`android.yml:11-15`,
`product-gold.yml:12-17`,
`android-emulator-recovery.yml:11-15`
- `contents: read` bleibt Standard.
- kein `write` ohne block-spezifischen Nachweis.
- Concurrency/Exact-Head-Vertrag nicht ändern.

Full Gold nach B306.

---

# B307 — DeepSearch Resilience

## B307.1 — deterministische Source-Retry-State-Machine

`DeepSearchModels.kt:242-257`
- Trace-Typen ergänzen: `SOURCE_RETRY_SCHEDULED`, `SOURCE_RETRY_EXHAUSTED`.

`DeepSearchPlannerCheckpoint.kt:13-48`
- `sourceAttempts: Map<String, Int>`
- optional `sourceFailureFingerprints`
- Fingerprint muss Retry-State binden.
- Codec-Version 2 → 3; v1/v2 weiterhin lesbar.

`DeepSearchPlannerCheckpoint.kt:102-165`
- v3 schreiben; v1/v2 migrieren mit leerem Retry-State.

`DeepSearchPlannerV2.kt:19-24`
- RetryPolicy injizieren; bounded, deterministisch, kein Schlafen über Gesamtzeitbudget.

`DeepSearchPlannerV2.kt:198-264`
- Source-Exception nicht sofort permanent `failed`; erst Attempt inkrementieren, Checkpoint persistieren, Retry nur solange Work-/Time-Budget erlaubt.
- Cancellation immer durchwerfen.
- Timeout bleibt globales Zeitbudgetsignal, nicht blind retryen.

## B307.2 — Mission-Failure durable schließen

`DeepSearchMissionCoordinator.kt:108-130`
- neben Cancellation auch nicht-cancellation failure abfangen.
- vor Propagation/Terminalisierung letzten gültigen Checkpoint erhalten.
- Ledger in expliziten failure/unresolved Zustand bringen; kein dauerhaftes EXPLORING nach Prozessfehler.

`DeepSearchMissionRecoveryAudit.kt:31-116`
- stranded EXPLORING + exhausted retry state erkennen.
- inkonsistente Result-Photon-/Checkpoint-Paare fail-closed melden.

`AndroidWebDeepSearchSource.kt:108-169,350-410`
- connect/read timeout bereits vorhanden: beibehalten.
- Redirect-/HTTP-/parse Fehler in stabile Fehlerklassen projizieren; keine rohe Exception-Nachricht als Retry-Identität.
- Connection in allen Pfaden schließen.

Tests:
- retry then success
- retry exhausted
- cancellation no retry
- cold restart after retry reservation
- timeout budget exhausted
- corrupt checkpoint + retry state
- source disappears between checkpoint and resume

Full Gold nach B307.

---

# B308 — Orphaned/Dormant Modules schließen

## B308.1 — produktiver Closure-Audit

Neue `core/runtime/.../topology/SubsystemClosureAudit.kt`:
- bekannte Manifest-IDs
- Binding vorhanden?
- Dependency operational?
- required capability provider vorhanden?
- StartupOwner erklärt bewusst extern/optional?
- Ergebnis: ACTIVE / DEGRADED / EXEMPT_EXTERNAL / ORPHANED / DORMANT.

`LifeOsRuntimeTopology.kt:60-179`
- 43 kanonische Subsysteme bleiben Source of Truth.
- für jedes Manifest expliziten Closure-Status ableitbar machen.

`LifeOsRuntimeTopology.kt:190-268`
- Snapshot um Closure-Report ergänzen; `fullyConnected` darf keine stillen ORPHANED/DORMANT enthalten.

`LifeOsRuntimeWiring.kt:46-92`
- Ready-Manifests nicht nur registrieren, sondern nach Startup-Phase Closure-Check ausführen.
- EXTERNAL_HOST und bewusst OPTIONAL_RUNTIME dürfen explizit exempt sein; alles andere muss gebunden oder begründet degraded sein.

Tests:
- `LifeOsRuntimeTopologyTest.kt:27-78`: Inventory 43 beibehalten.
- neuer Test: kein nicht-exemptes Manifest bleibt REGISTERED/unbound nach kompletter produktiver Wiring-Fixture.
- `LifeOsRuntimeWiringTest.kt:20-89`: Startup-owner chain + closure.

## B308.2 — CI-Inventar

Neuer `ci-subsystem-closure-contract.sh`:
- Manifest-ID-Duplikate/fehlende IDs
- bekannte Registry-/Binding-IDs müssen im Manifest existieren
- kein produktiver neuer RuntimeRegistry-Name ohne Manifest-/Closure-Test.

---

# B309 — Performance Gold

## B309.1 — echte Messwerte in Gold-Evidence

`BootPerformance.kt:3-95`
- Recorder ist vorhanden, aber derzeit nicht in BootEngine/KernelBootLifecycle verdrahtet.
- pro BootPhase `measure` um produktive Phasen legen.
- Snapshot mit total + phase timings in Boot-Result/Evidence projizieren.

`RuntimeTelemetry.kt:18-65`
- CPU, heap, native heap, traffic bereits typisiert.
- Performance-Gold-Snapshot bekommt Start/End-Deltas, nicht Rohwerte ohne Kontext.

`AndroidRuntimeTelemetryReader.kt:14-62`
- Reader bleibt permission-free; keine fremden Prozessdaten.

`KernelBootLifecycle.kt`
- BootPerformanceRecorder injizieren und Snapshot nach erfolgreichem/fehlgeschlagenem Boot versiegeln.

`ci-product-gold.sh:56-89`
- Performance-Evidence-Datei erzeugen und vor dem Emulator versiegeln.

`seal-gold-evidence.py:200-277`
- Performance-Datei + SHA256 in Manifest aufnehmen.

## B309.2 — Baseline erst messen, dann Budget festschreiben

Keine willkürlichen Millisekunden erfinden.
1. 3 identische Emulator-Gold-Runs auf einem Exact Head.
2. Median für Boot total/Phasen, Heap Peak, Native Heap Delta, CPU Delta.
3. `.github/performance/product-gold-baseline.json` mit Head + Messwerten einchecken.
4. Budget-Grenzen aus Baseline + dokumentierter Toleranz ableiten.
5. neuer `ci-performance-gold-contract.sh` failt bei Regression.

Full Gold ist der Beweis von B309.

---

# B310 — Native Safety

## B310.1 — Integer-/Buffer-Sicherheit JNI

`NativeMmsiBridge.kt:24-34`
- `pixelCount * BYTES_PER_PIXEL` nicht als unchecked Int.
- `Math.multiplyExact(pixelCount.toLong(), bytesPerPixel.toLong())`; gegen `Int.MAX_VALUE` und Buffer-`capacity().toLong()` prüfen.

`NativeMmsiBridge.kt:83-90`
- allocate-Helfer dieselbe checked-size Funktion verwenden; negative/overflow sofort rejecten.

`mmsi_native.cpp:181-188`
- `GetDirectBufferCapacity` negativ/invalid explizit behandeln.
- Exception-Class lookup/null/exception state sauber behandeln.

`mmsi_native.cpp:206-227`
- Pixelcount und Byte-Multiplikationen mit checked helper; keine stillen Overflow-/Wrap-Pfade.
- nach geworfener JNI-Exception keine weitere Native-Arbeit.

## B310.2 — Fence/Handle ownership

`MmsiSyncFence.kt:10-31`
- `take()` darf nur validen Descriptor übertragen; Double-take explizit failen oder -1 bewusst typisieren.
- Close idempotent testen.

`VulkanSyncFdHardwareBufferMmsiRenderer.kt:24-74`
- Ownership von acquireFd bei native failure dokumentieren/testen.
- wenn native API den FD nicht übernimmt, Java-Pfad muss schließen; Contract eindeutig machen.

`VulkanSyncFdHardwareBufferMmsiRenderer.kt:77-83`
- close/dispatch race bleibt synchronisiert.

## B310.3 — Toolchain-Hardening

`core/image-native/src/main/cpp/CMakeLists.txt:19-26`
- zusätzlich zu `-O3 -fno-exceptions`: Warn-/Format-/Stack-Protector-/RELRO/NOW-Hardening, nur Flags die Android NDK reproduzierbar unterstützt.
- kein blanket `-Werror` ohne vorherigen Warnungs-Clean-Pass; mindestens sicherheitsrelevante Warnungen fatal.

`core/image-native/build.gradle.kts:6-17`
- Native build flags/ABI-Scope zentralisieren.
- Debug Native-Safety-Testkonfiguration ergänzen.

Tests:
- max/overflow pixelCount
- non-direct buffers
- undersized buffers
- NaN/Inf inputs
- double-close/double-take
- dispatch after close
- failed nativeCreate
- sync-fd ownership failure
- Vulkan fallback to non-native path

Full Gold + Emulator auf B310-Head.

---

# B312 — Architecture-Budget-Gates (final, blocking)

## B312.1 — Source-Budgets

Neue `.github/scripts/ci-architecture-budget-contract.sh`.

Basiswerte auf `main@5188f243c8b684495f9751c0f4f745620ae8836a`:
- `LifeOsKernel.kt`: 387 Zeilen
- `LifeOsApplication.kt`: 300
- `LifeOsViewModel.kt`: 222
- `ProcessRuntimeInstaller.kt`: 497
- `DeepSearchPlannerV2.kt`: 733

Finale Maxima:
- Kernel <= 400
- Application <= 320
- ViewModel <= 240
- ProcessRuntimeInstaller <= 520
- DeepSearchPlannerV2 darf nach B307 **nicht wachsen**; Ziel ist <= aktueller Wert oder weitere Extraktion, kein Budget > 733.
- neue Dateien > 500 Zeilen müssen explizit auf Allowlist + Begründung stehen.

## B312.2 — Modul-Budgets

`.github/scripts/ci-runtime-module-boundary-contract.sh`
- runtime-personal 6/8
- runtime-thought 11/7
- runtime-informationasset 19/13
- verschobene Packages dürfen nie in `:core:runtime` zurückwachsen.
- Monolith-Gesamtzahl nach B302 als feste Obergrenze einfrieren; spätere Blöcke dürfen sie nicht erhöhen.

## B312.3 — Architektur-/Security-Gates in Core Fast

`.github/scripts/ci-core-fast.sh:21-66`
Reihenfolge:
1. action pin
2. language semantics
3. wrapper integrity
4. main authority
5. unified vault
6. dependency integrity
7. permission-profile/static security
8. BuildStudio wrapper-only
9. runtime module boundary
10. subsystem closure
11. architecture budget
12. Module-/Core-/App-Tests

`.github/scripts/ci-gold-coverage-contract.sh:54-186`
- alle neuen Contracts als mandatory coverage aufnehmen.

## B312.4 — Main Ruleset real aktivieren

Aktuelle Ruleset-Datei:
`.github/rulesets/main-protection.json:1-51`
- enthält bereits deletion/non-fast-forward/PR/required-status-checks.
- Required checks an Zeilen 33-45: Core Fast, Android Debug, Emulator Recovery, Product Gold.

Externer Admin-Schritt:
- Ruleset in GitHub tatsächlich aktivieren.
- danach Branch-API muss `protected: true` bzw. aktives Ruleset zeigen.
- kein Bypass Actor.
- erst dann B312 schließen.

---

# Beschleunigte Merge-Pakete

1. **P302** = B302.2 + B302.3 + B302.4 → eine Full-Gold-Matrix.
2. **P303** = B303.1–B303.4 → Core/Data nach jeder Migrationswelle, eine Full-Gold-Matrix am Ende.
3. **P304/305** = Permission Profiles + BuildStudio wrapper-only → eine Full-Gold-Matrix, sofern Core Fast/Debug nach jedem Subblock grün.
4. **P306** = Supply chain → Full Gold zwingend direkt, weil CI selbst verändert wird.
5. **P307** = DeepSearch Resilience → Full Gold + DeepSearch corruption/recovery device tests.
6. **P308** = Closure Audit → Full Gold.
7. **P309** = Performance Gold → drei Baseline-Runs + finaler Budget-Gold-Run.
8. **P310** = Native Safety → Emulator + Product Gold.
9. **P312** = finale Architektur-Gates + reale Main-Protection-Aktivierung.

# Definition of Done gesamt

Fertig ist die Kette erst, wenn:
- B302-Runtime-Splits zyklusfrei und CI-blockierend sind,
- alle dauerhaften verschlüsselten Stores über Unified Vault laufen,
- Android-/Owner-Permissions typisiert und zentral prüfbar sind,
- BuildStudio keinen produktiven Registry-Bypass mehr besitzt,
- Supply-Chain-Verifikation/Locks aktiv sind,
- DeepSearch nach Source-/Process-Fehlern deterministisch recoverbar ist,
- kein nicht-exemptes Subsystem orphaned/dormant bleibt,
- Performance-Budgets mit gemessener Baseline blockieren,
- JNI/FD/Buffer-Pfade overflow- und ownership-sicher sind,
- Architecture-Budgets in Core Fast blockieren,
- und das GitHub-Main-Ruleset tatsächlich aktiv ist.
