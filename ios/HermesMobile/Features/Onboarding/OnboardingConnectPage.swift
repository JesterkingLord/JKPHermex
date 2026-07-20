import SwiftUI
import UIKit

enum OnboardingConnectField: Hashable {
    case serverURL
    case password
}

struct OnboardingConnectPage: View {
    @Bindable var viewModel: OnboardingViewModel
    @Bindable var authManager: AuthManager
    @FocusState.Binding var focusedField: OnboardingConnectField?

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var isShowingAdvanced = false
    @State private var isShowingPairSheet = false
    @State private var pairText = ""

    private var canSubmit: Bool {
        !viewModel.serverURLString.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private func submitConnection() {
        guard canSubmit else { return }
        Task { await viewModel.connect(authManager: authManager) }
    }

    var body: some View {
        ScrollView(.vertical, showsIndicators: false) {
            VStack(alignment: .leading, spacing: 18) {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Connect")
                        .font(.title3.weight(.bold))
                        .foregroundStyle(.white)

                    Text("Enter the Tailscale URL your agent returned, for example `http://<tailnet-ip>:8787`.")
                        .font(.footnote)
                        .foregroundStyle(.white.opacity(0.5))
                        .fixedSize(horizontal: false, vertical: true)
                }

                VStack(spacing: 12) {
                    OnboardingField(systemImage: "link", title: String(localized: "Server URL")) {
                        ZStack(alignment: .leading) {
                            if viewModel.serverURLString.isEmpty {
                                Text(verbatim: "http://100.64.0.1:8787")
                                    .foregroundStyle(.white.opacity(0.38))
                                    .allowsHitTesting(false)
                            }

                            TextField("", text: $viewModel.serverURLString)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                                .keyboardType(.URL)
                                .foregroundStyle(.white)
                                .submitLabel(.go)
                                .tint(Color(red: 1.0, green: 0.74, blue: 0.10))
                                .focused($focusedField, equals: .serverURL)
                                .onSubmit(submitConnection)
                        }
                    }

                    if viewModel.isPasswordRequired {
                        OnboardingField(systemImage: "key.fill", title: String(localized: "Password")) {
                            SecureField(
                                "",
                                text: $viewModel.password,
                                prompt: Text("Server password")
                                    .foregroundStyle(.white.opacity(0.38))
                            )
                            .textContentType(.password)
                            .submitLabel(.go)
                            .focused($focusedField, equals: .password)
                            .onSubmit(submitConnection)
                        }
                    }
                }

                DisclosureGroup(isExpanded: $isShowingAdvanced) {
                    CustomHeadersEditor(headers: $viewModel.customHeaders, style: .onboarding)
                        .padding(.top, 10)
                } label: {
                    Label("Advanced", systemImage: "slider.horizontal.3")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.white.opacity(0.85))
                }
                .tint(.white.opacity(0.6))

                // v1.6.0+: QR/paste pairing entry point. The desktop's
                // `python -m jkp pair` mints a short-lived pair_id+token; the
                // user pastes the URL the host prints here, and the phone
                // registers itself on the server. Mirror of the Android
                // OnboardingScreen's "Scan QR or paste pairing URL" button.
                Button {
                    isShowingPairSheet = true
                } label: {
                    Label("Scan QR or paste pairing URL", systemImage: "qrcode.viewfinder")
                        .font(.subheadline.weight(.semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                }
                .buttonStyle(.bordered)
                .tint(.white.opacity(0.85))
                .disabled(viewModel.isWorking)

                if viewModel.isWorking {
                    OnboardingStatusBanner(
                        text: String(localized: "Checking server..."),
                        systemImage: "arrow.triangle.2.circlepath",
                        tint: .white.opacity(0.7),
                        showsProgress: true
                    )
                }

                if let connectionMessage = viewModel.connectionMessage {
                    OnboardingStatusBanner(
                        text: connectionMessage,
                        systemImage: "checkmark.circle.fill",
                        tint: Color(red: 0.45, green: 0.92, blue: 0.56)
                    )
                }

                if let errorMessage = viewModel.errorMessage {
                    OnboardingStatusBanner(
                        text: errorMessage,
                        systemImage: "exclamationmark.triangle.fill",
                        tint: Color(red: 1.0, green: 0.47, blue: 0.34)
                    )
                }
            }
            .padding(.horizontal, 22)
            .padding(.top, dynamicTypeSize.isAccessibilitySize ? 18 : 24)
            .padding(.bottom, 24)
        }
        .scrollBounceBehavior(.basedOnSize)
        .sheet(isPresented: $isShowingPairSheet) {
            PairFromURLSheet(
                pairText: $pairText,
                onSubmit: { raw in
                    isShowingPairSheet = false
                    Task { await viewModel.pairFromText(authManager: authManager, rawText: raw) }
                },
                onCancel: { isShowingPairSheet = false },
            )
        }
    }
}

/// v1.6.0+: sheet that mirrors the Android `PairUrlDialog`. Hosts paste a
/// URL the desktop's `python -m jkp pair` printed; we hand it straight to
/// [OnboardingViewModel.pairFromText] which routes through the new
/// `AuthManager.pairAndConfigure` extension.
private struct PairFromURLSheet: View {
    @Binding var pairText: String
    let onSubmit: (String) -> Void
    let onCancel: () -> Void

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("On the host machine run `python -m jkp pair`, then copy the URL it prints and paste it here.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                Section("Pairing URL") {
                    TextField(
                        "http://100.x.y.z:8642/v1/pair/connect?pair_id=…&token=…",
                        text: $pairText
                    )
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.URL)
                    Button("Paste from clipboard") {
                        if let clip = UIPasteboard.general.string {
                            pairText = clip
                        }
                    }
                    .disabled(UIPasteboard.general.string?.isEmpty ?? true)
                }
            }
            .navigationTitle("Pair from URL")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: onCancel)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Pair") { onSubmit(pairText) }
                        .disabled(pairText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }
}