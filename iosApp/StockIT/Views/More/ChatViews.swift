import SwiftUI
import Shared

/// Bulle de chat (ChatAdapter).
struct ChatBubble: View {
    let sender: String
    let text: String
    let mine: Bool
    var body: some View {
        HStack {
            if mine { Spacer(minLength: 40) }
            VStack(alignment: .leading, spacing: 3) {
                Text(sender).font(.caption2.bold()).opacity(0.7)
                Text(text)
            }
            .padding(10)
            .background(mine ? Color.vistaBlue : Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 14))
            .foregroundColor(mine ? .white : .primary)
            if !mine { Spacer(minLength: 40) }
        }
    }
}

struct ChatInputBar: View {
    @Binding var text: String
    let busy: Bool
    let onSend: () -> Void
    var body: some View {
        HStack {
            TextField("Tapez votre message...", text: $text, axis: .vertical).textFieldStyle(.roundedBorder).lineLimit(1...4)
            if busy { ProgressView() }
            Button(action: onSend) { Image(systemName: "paperplane.fill").font(.title3) }
                .disabled(busy || text.trimmingCharacters(in: .whitespaces).isEmpty)
        }
        .padding()
        .background(.bar)
    }
}

/// AIChatActivity — Assistant IA Vista.
struct AIChatView: View {
    @EnvironmentObject var app: AppState
    @State private var messages: [(String, String)] = [("IA", Strings.shared.AI_WELCOME)]
    @State private var input = ""
    @State private var busy = false

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 10) {
                        ForEach(Array(messages.enumerated()), id: \.offset) { i, m in
                            ChatBubble(sender: m.0 == "Moi" ? L("Moi") : L("IA"), text: m.1, mine: m.0 == "Moi").id(i)
                        }
                    }
                    .padding()
                }
                .onChange(of: messages.count) { n in withAnimation { proxy.scrollTo(n - 1, anchor: .bottom) } }
            }
            ChatInputBar(text: $input, busy: busy) { Task { await send() } }
        }
        .navigationTitle("Assistant IA Vista")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func send() async {
        let q = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return }
        messages.append(("Moi", q))
        input = ""
        busy = true; defer { busy = false }
        let a = (try? await app.repo.askAssistant(question: q)) ?? Strings.shared.AI_ERROR
        messages.append(("IA", a))
    }
}

/// ClaimActivity — Centre de Réclamations.
struct ClaimsView: View {
    @EnvironmentObject var app: AppState
    @State private var claims: [Claim] = []
    @State private var adding = false

    var body: some View {
        List {
            if claims.isEmpty { EmptyStateView(icon: "tray", title: L("Aucune réclamation"), subtitle: L("Appuyez sur + pour en créer une.")) }
            ForEach(claims) { c in
                NavigationLink { ClaimChatView(claim: c) } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(c.subject).font(.headline)
                            Spacer()
                            Pill(text: LV(c.priority ?? "-"), color: c.priority == "HAUTE" ? .vistaCoral : c.priority == "MOYENNE" ? .vistaAmber : .vistaBlue)
                        }
                        Text(c.description_ ?? "").font(.subheadline).foregroundColor(.secondary).lineLimit(2)
                        Text(verbatim: "\(c.senderName ?? "") · \(LV(c.status)) · \(formatTimestamp(c.timestamp))").font(.caption2).foregroundColor(.secondary)
                    }
                }
            }
        }
        .navigationTitle("Centre de Réclamations")
        .toolbar { Button { adding = true } label: { Image(systemName: "plus") } }
        .refreshable { await load() }
        .task { await load() }
        .sheet(isPresented: $adding) { NavigationStack { NewClaimView { await load() } } }
    }

    private func load() async { claims = (try? await app.repo.getClaims(syncRemote: true)) ?? [] }
}

struct NewClaimView: View {
    @EnvironmentObject var app: AppState
    @Environment(\.dismiss) private var dismiss
    let onSaved: () async -> Void
    @State private var subject = ""
    @State private var desc = ""
    @State private var priority = "MOYENNE"

    var body: some View {
        Form {
            TextField("Sujet (ex: PC en panne)", text: $subject)
            TextField("Description détaillée...", text: $desc, axis: .vertical).lineLimit(3...8)
            Picker("Priorité :", selection: $priority) {
                Text("BASSE").tag("BASSE"); Text("MOYENNE").tag("MOYENNE"); Text("HAUTE").tag("HAUTE")
            }
            .pickerStyle(.segmented)
        }
        .navigationTitle("Nouvelle Réclamation")
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Annuler") { dismiss() } }
            ToolbarItem(placement: .confirmationAction) {
                Button("Envoyer") {
                    guard !subject.isEmpty else { return }
                    Task {
                        try? await app.repo.addClaim(subject: subject, desc: desc, priority: priority)
                        await onSaved()
                        dismiss()
                    }
                }
            }
        }
    }
}

/// ChatActivity — messages d'une réclamation (backend).
struct ClaimChatView: View {
    @EnvironmentObject var app: AppState
    let claim: Claim
    @State private var messages: [ChatMessage] = []
    @State private var input = ""
    @State private var busy = false

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                LazyVStack(spacing: 10) {
                    if messages.isEmpty {
                        Text("Aucun message (ou serveur injoignable).").font(.caption).foregroundColor(.secondary).padding()
                    }
                    ForEach(Array(messages.enumerated()), id: \.offset) { _, m in
                        ChatBubble(sender: m.senderName ?? "?", text: m.message ?? "", mine: m.senderId == app.user?.username)
                    }
                }
                .padding()
            }
            ChatInputBar(text: $input, busy: busy) { Task { await send() } }
        }
        .navigationTitle("Détails: \(claim.subject)")
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
    }

    private func load() async { messages = (try? await app.repo.getMessages(claimId: claim.id)) ?? [] }

    private func send() async {
        let t = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        busy = true; defer { busy = false }
        if (try? await app.repo.addMessage(claimId: claim.id, text: t))?.boolValue == true {
            input = ""
            await load()
        } else {
            app.show(L("Envoi impossible (serveur injoignable)"), style: .urgent)
        }
    }
}
