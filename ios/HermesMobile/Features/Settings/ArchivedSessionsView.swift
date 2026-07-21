import SwiftUI

struct ArchivedSessionsView: View {
    let server: URL

    @State private var viewModel: ArchivedSessionsViewModel
    @AppStorage(SessionRowDisplaySettings.showMessageCountKey) private var showsSessionMessageCount = true
    @AppStorage(SessionRowDisplaySettings.showWorkspaceKey) private var showsSessionWorkspace = true
    @AppStorage(AppHaptics.isEnabledKey) private var isHapticsEnabled = true

    init(server: URL) {
        self.server = server
        _viewModel = State(initialValue: ArchivedSessionsViewModel(server: server))
    }

    var body: some View {
        content
            .navigationTitle("Archived Sessions")
            .task {
                await viewModel.load()
            }
            .refreshable {
                await viewModel.load()
            }
            .alert(
                "Action Failed",
                isPresented: Binding(
                    get: { viewModel.actionErrorMessage != nil },
                    set: { isPresented in
                        if !isPresented {
                            viewModel.clearActionError()
                        }
                    }
                )
            ) {
                Button("OK") {
                    viewModel.clearActionError()
                }
            } message: {
                Text(viewModel.actionErrorMessage ?? "")
            }
    }

    @ViewBuilder
    private var content: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                if viewModel.isLoading && viewModel.sessions.isEmpty {
                    ArchivedStatusRow(title: String(localized: "Loading archived sessions..."), systemImage: "archivebox")
                        .padding(.horizontal, 24)
                } else if let errorMessage = viewModel.errorMessage, viewModel.sessions.isEmpty {
                    VStack(alignment: .leading, spacing: 10) {
                        ArchivedStatusRow(title: String(localized: "Could not load archived sessions"), systemImage: "exclamationmark.triangle")

                        Text(errorMessage)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .lineLimit(3)

                        Button("Try Again") {
                            Task { await viewModel.load() }
                        }
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(.primary)
                    }
                    .padding(.horizontal, 24)
                } else if viewModel.sessions.isEmpty {
                    ArchivedStatusRow(title: String(localized: "No archived sessions"), systemImage: "archivebox")
                        .padding(.horizontal, 24)
                } else {
                    VStack(spacing: 2) {
                        ForEach(visibleSessions) { session in
                            SessionRowView(
                                session: session,
                                showsMessageCount: showsSessionMessageCount,
                                showsWorkspace: showsSessionWorkspace
                            )
                                .contextMenu {
                                    Button {
                                        Task {
                                            let didUnarchive = await viewModel.unarchive(session)
                                            if didUnarchive {
                                                SessionHaptics.archiveStateChanged(isEnabled: isHapticsEnabled)
                                            }
                                        }
                                    } label: {
                                        Label("Unarchive", systemImage: "arrow.up.bin")
                                    }
                                    .disabled(viewModel.isUnarchiving(session))
                                }
                        }
                    }
                    .padding(.horizontal, 12)
                }
            }
            .padding(.top, 28)
            .padding(.bottom, 44)
        }
    }

    private var visibleSessions: [SessionSummary] {
        viewModel.sessions.sorted { left, right in
            if (left.pinned == true) != (right.pinned == true) {
                return left.pinned == true
            }

            return timestamp(for: left) > timestamp(for: right)
        }
    }

    private func timestamp(for session: SessionSummary) -> Double {
        session.lastMessageAt ?? session.updatedAt ?? session.createdAt ?? 0
    }
}

private struct ArchivedStatusRow: View {
    let title: String
    let systemImage: String

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: systemImage)
                .font(.body)
                .foregroundStyle(.secondary)
                .frame(width: 24)
                .accessibilityHidden(true)

            Text(title)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .lineLimit(2)

            Spacer(minLength: 0)
        }
        .frame(minHeight: 42)
    }
}
