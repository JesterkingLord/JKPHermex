import SwiftUI

/// "About" card for the Settings screen — shows the installed app version
/// and a "Check for updates" button that hits GitHub for the latest release
/// of `JesterkingLord/JKPHermex`.
///
/// v1.5.0. Mirrors the Android `SettingsScreen.kt` About section so the
/// two clients share the same UX (button + dialog with the three
/// UpdateResult branches: update available, up to date, failed).
///
/// Unverified on a Mac from this Windows host; pattern follows the rest
/// of the app (typed actor for the network call, sheet for the dialog,
/// `@State` for transient UI).
struct AppUpdateCard: View {
    @State private var isChecking = false
    @State private var result: AppUpdateResult?
    @State private var showSheet = false

    private let checker = AppUpdateChecker(owner: "JesterkingLord", repo: "JKPHermex")

    private static let installedVersion: String = {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
    }()

    var body: some View {
        SettingsCard(title: String(localized: "About")) {
            SettingsRow(
                label: String(localized: "App version"),
                value: Self.installedVersion
            )
            SettingsRow(
                label: String(localized: "Update channel"),
                value: String(localized: "Stable (GitHub releases)")
            )
            Button {
                Task {
                    isChecking = true
                    result = await checker.check()
                    isChecking = false
                    showSheet = true
                }
            } label: {
                HStack {
                    if isChecking {
                        ProgressView()
                            .controlSize(.small)
                    }
                    Text(isChecking
                         ? String(localized: "Checking…")
                         : String(localized: "Check for updates"))
                }
            }
            .disabled(isChecking)
        }
        .sheet(isPresented: $showSheet) {
            AppUpdateDialog(
                loading: isChecking,
                result: result,
                installedVersion: Self.installedVersion
            )
        }
    }
}

/// Reusable read-only row used by the About card. Matches the visual style
/// of the existing settings rows in `SettingsView`.
private struct SettingsRow: View {
    let label: String
    let value: String

    var body: some View {
        HStack {
            Text(label)
                .foregroundStyle(.primary)
            Spacer()
            Text(value)
                .foregroundStyle(.secondary)
        }
        .font(.subheadline)
    }
}

/// Sheet that renders the three `AppUpdateResult` branches:
///  - `.updateAvailable` → version, size, release notes, "Download" button
///    that opens the APK URL in the browser.
///  - `.upToDate` → "You're on the latest version" with confirmation.
///  - `.failed` → short reason, no stack traces.
struct AppUpdateDialog: View {
    let loading: Bool
    let result: AppUpdateResult?
    let installedVersion: String

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 14) {
                Text(titleText)
                    .font(.title3.bold())
                content
                Spacer()
                buttons
            }
            .padding(20)
            .navigationTitle("")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "Close")) { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private var titleText: String {
        if loading { return String(localized: "Checking for updates…") }
        switch result {
        case .updateAvailable: return String(localized: "Update available")
        case .upToDate: return String(localized: "You're up to date")
        case .failed: return String(localized: "Couldn't check for updates")
        case .none: return ""
        }
    }

    @ViewBuilder
    private var content: some View {
        if loading {
            HStack(spacing: 12) {
                ProgressView()
                Text(String(localized: "Contacting GitHub…"))
            }
        } else if let result {
            switch result {
            case .updateAvailable(let current, let latest, let release):
                VStack(alignment: .leading, spacing: 8) {
                    Text("\(current) → \(latest)")
                        .foregroundStyle(.tint)
                    if let apk = release.apkAsset {
                        Text("\(apk.name) · \(apk.sizeMB)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    if !release.body.isEmpty {
                        Text(String(release.body.prefix(400)))
                            .font(.footnote)
                    }
                }
            case .upToDate(let current, let latest):
                VStack(alignment: .leading, spacing: 4) {
                    Text("Installed: \(current)")
                    if let latest {
                        Text("Latest on GitHub: \(latest)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            case .failed(let reason):
                Text(reason)
            }
        }
    }

    @ViewBuilder
    private var buttons: some View {
        if case .updateAvailable(let _, let _, let release) = result {
            HStack {
                Button(String(localized: "Later")) { dismiss() }
                    .buttonStyle(.bordered)
                Spacer()
                Button(String(localized: "Download")) {
                    let urlString = release.apkAsset?.browserDownloadURL ?? release.htmlURL
                    if let url = URL(string: urlString) {
                        UIApplication.shared.open(url)
                    }
                    dismiss()
                }
                .buttonStyle(.borderedProminent)
            }
        }
    }
}