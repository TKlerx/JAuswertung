import SwiftUI
import TimingCore

@main
@MainActor
struct ZeitnahmeApp: App {
    var body: some Scene {
        WindowGroup { TimingScreen() }
    }
}

@MainActor
struct TimingScreen: View {
    @Environment(\.scenePhase) private var scenePhase
    @State private var model = TimingModel()
    @State private var address = ""
    @State private var name = ""
    @State private var code = ""
    @State private var confirmUnpair = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    if let error = model.error {
                        Label(error, systemImage: "exclamationmark.triangle").foregroundStyle(.red)
                    }
                    if model.pairing != nil {
                        heatScreen
                    } else {
                        pairScreen
                    }
                    if !model.outstandingFromEarlier.isEmpty {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Offene Meldungen aus früheren Läufen").font(.headline)
                            ForEach(model.outstandingFromEarlier) { event in
                                Text("Bahn \(event.lane) · \(status(event.pairing == model.pairing?.id ? event.status : .blocked))")
                            }
                        }
                    }
                }
                .frame(maxWidth: 560, alignment: .leading)
                .padding(24)
                .frame(maxWidth: .infinity)
            }
            .scrollDismissesKeyboard(.interactively)
            .navigationTitle("Zeitnahme")
            .toolbar {
                if model.pairing != nil {
                    Button("Trennen") { confirmUnpair = true }
                        .frame(minHeight: 44)
                }
            }
            .confirmationDialog("Kopplung trennen?", isPresented: $confirmUnpair, titleVisibility: .visible) {
                Button("Kopplung trennen", role: .destructive) { model.unpair() }
            } message: {
                Text("Offene Zeiten bleiben gespeichert. Hole für eine neue Kopplung einen Code am Wettkampftisch.")
            }
        }
        .task(id: scenePhase) {
            if scenePhase == .active { await model.run() }
        }
    }

    private var pairScreen: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Mit dem Wettkampftisch koppeln").font(.title2.bold())
            Text("Adresse und einmaligen Kopplungscode am Wettkampftisch ablesen.")
                .foregroundStyle(.secondary)
            TextField("HTTPS-Adresse", text: $address)
                .keyboardType(.URL)
                .textContentType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .frame(minHeight: 44)
            TextField("Gerätename", text: $name)
                .frame(minHeight: 44)
            TextField("Kopplungscode", text: $code)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .frame(minHeight: 44)
            Button {
                Task { await model.pair(address: address, name: name, code: code) }
            } label: {
                Text(model.pairingInProgress ? "Koppelt …" : "Koppeln")
                    .frame(maxWidth: .infinity, minHeight: 48)
            }
            .buttonStyle(.borderedProminent)
            .disabled(model.pairingInProgress || !model.storageReady || address.isEmpty || name.isEmpty || code.isEmpty)
        }
        .textFieldStyle(.roundedBorder)
    }

    @ViewBuilder
    private var heatScreen: some View {
        if let pairing = model.pairing {
            Text("Gekoppelt als \(pairing.name)").foregroundStyle(.secondary)
        }
        if let problem = model.connectionIssue {
            Label(problem, systemImage: "wifi.slash")
            if model.heat != nil { Text("Zuletzt empfangener Lauf").foregroundStyle(.secondary) }
        }
        if let heat = model.heat {
            VStack(alignment: .leading, spacing: 8) {
                Text(heat.discipline).font(.title2.bold())
                Text(phase(heat)).font(.headline)
                if heat.phase == .running && heat.sync && !model.clockUsable {
                    Text("Zielerfassung pausiert. Der Uhrabgleich fehlt oder ist abgelaufen.")
                }
            }
            if heat.lanes.isEmpty { Text("Diesem Handy sind keine Bahnen zugewiesen.") }
            ForEach(heat.lanes) { lane in
                LaneCard(lane: lane, allowed: model.mayCapture(lane), result: model.delivery(for: lane),
                         finish: { model.finish(lane, attempt: heat.attempt) },
                         stopwatch: { model.stopwatch(lane, attempt: heat.attempt, micros: $0) })
                    .id("\(heat.attempt)-\(lane.id)")
            }
        } else {
            Text("Wartet auf einen Lauf am Wettkampftisch.")
        }
        if model.captures.contains(where: { $0.pairing == model.pairing?.id && $0.status == .pending }) {
            Button {
                Task { await model.sendPending() }
            } label: {
                Text("Ausstehende Meldungen erneut senden").frame(minHeight: 44)
            }
        }
    }

    private func phase(_ heat: Heat) -> String {
        switch heat.phase {
        case .armed: return heat.ready ? "Startbereit" : "Scharfgeschaltet · wartet auf Uhrabgleich"
        case .deskStart, .granted: return "Wartet auf Startsignal"
        case .uncertain: return "Start noch nicht bestätigt"
        case .running: return "Lauf läuft"
        case .closed: return "Lauf geschlossen"
        case .applied: return "Ergebnisse übernommen"
        }
    }
}

private struct LaneCard: View {
    let lane: Lane
    let allowed: Bool
    let result: Capture?
    let finish: () -> Void
    let stopwatch: (Int64) -> Void

    @State private var entry = ""
    @State private var invalid = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Bahn \(lane.id)").font(.title3.bold()).accessibilityAddTraits(.isHeader)
            Text(lane.name)
            if let result {
                Label(status(result.status), systemImage: result.status == .accepted ? "checkmark.circle" : "info.circle")
            } else if lane.timed {
                Button(action: finish) {
                    Text("Ziel erfassen · Bahn \(lane.id)").frame(maxWidth: .infinity, minHeight: 56)
                }
                .buttonStyle(.borderedProminent)
                .disabled(!allowed)
            } else {
                TextField("Stoppuhrzeit für Bahn \(lane.id)", text: $entry)
                    .textFieldStyle(.roundedBorder)
                    .frame(minHeight: 44)
                    .disabled(!allowed)
                if invalid { Text("Gib eine positive Zeit ein, z. B. 1:05,20.").foregroundStyle(.red) }
                Button {
                    if let micros = stopwatchMicros(entry) {
                        invalid = false
                        stopwatch(micros)
                    } else {
                        invalid = true
                    }
                } label: {
                    Text("Zeit melden · Bahn \(lane.id)").frame(maxWidth: .infinity, minHeight: 48)
                }
                .buttonStyle(.bordered)
                .disabled(!allowed)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.quaternary, in: RoundedRectangle(cornerRadius: 16))
    }
}

private func status(_ value: Capture.Status) -> String {
    switch value {
    case .pending: return "Gespeichert · wartet auf Bestätigung"
    case .accepted: return "Am Wettkampftisch eingegangen"
    case .rejected: return "Vom Wettkampftisch abgelehnt"
    case .blocked: return "Senden gesperrt · ursprüngliche Kopplung fehlt"
    }
}
