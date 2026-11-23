import Foundation
import Observation
import TimingCore

@MainActor
@Observable
final class TimingModel {
    private(set) var pairing: Pairing?
    private(set) var heat: Heat?
    private(set) var captures: [Capture] = []
    private(set) var pairingInProgress = false
    private(set) var storageReady = true
    private(set) var clockUsable = false
    private(set) var connectionIssue: String?
    private(set) var error: String?

    @ObservationIgnored private let api = DeskAPI()
    @ObservationIgnored private let epoch = UUID()
    @ObservationIgnored private var estimate: ClockEstimate?
    @ObservationIgnored private var sending = false

    init() {
        do {
            captures = try TimingStore.captures()
            pairing = try TimingStore.pairing()
        } catch {
            storageFailed()
        }
    }

    func pair(address: String, name: String, code: String) async {
        guard pairing == nil, !pairingInProgress, storageReady else { return }
        guard let origin = DeskOrigin(address) else {
            error = "Gib eine HTTPS-Adresse ohne Pfad ein."
            return
        }
        let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty, name.utf16.count <= 60,
              let code = UUID(uuidString: code.trimmingCharacters(in: .whitespacesAndNewlines)) else {
            error = "Prüfe Gerätenamen und Kopplungscode."
            return
        }
        pairingInProgress = true
        error = nil
        defer { pairingInProgress = false }
        let bearer: String
        do {
            bearer = try await api.pair(origin: origin, name: name, code: code.uuidString.lowercased())
        } catch DeskFailure.status(403) {
            error = "Der Kopplungscode ist ungültig, abgelaufen oder wurde bereits verwendet."
            return
        } catch {
            self.error = "Kopplung unbestätigt. Prüfe die Verbindung und den Kopplungsstatus am Tisch."
            return
        }
        let newPairing = Pairing(id: UUID(), origin: origin, name: name, bearer: bearer)
        do {
            try TimingStore.save(newPairing)
            pairing = newPairing
            connectionIssue = nil
        } catch {
            storageFailed()
        }
    }

    func unpair() {
        do {
            try TimingStore.clearPairing()
            pairing = nil
            heat = nil
            estimate = nil
            clockUsable = false
            connectionIssue = nil
        } catch {
            storageFailed()
        }
    }

    func run() async {
        estimate = nil
        clockUsable = false
        async let heat: Void = observeHeat()
        async let clock: Void = observeClock()
        async let delivery: Void = observeDelivery()
        _ = await (heat, clock, delivery)
    }

    private func observeHeat() async {
        while !Task.isCancelled {
            if let pairing {
                do {
                    let updated = try await api.heat(pairing)
                    if !Task.isCancelled, self.pairing?.id == pairing.id {
                        if updated?.attempt != heat?.attempt || updated?.sync != true {
                            estimate = nil
                            clockUsable = false
                        }
                        heat = updated
                        connectionIssue = nil
                    }
                } catch {
                    if !Task.isCancelled, self.pairing?.id == pairing.id {
                        if case DeskFailure.status(401) = error {
                            expire()
                        } else {
                            let code = (error as NSError).code
                            let tls = [NSURLErrorSecureConnectionFailed, NSURLErrorServerCertificateHasBadDate,
                                       NSURLErrorServerCertificateUntrusted, NSURLErrorServerCertificateHasUnknownRoot,
                                       NSURLErrorServerCertificateNotYetValid].contains(code)
                            connectionIssue = tls ? "Prüfe Adresse und Zertifikat des Wettkampftischs."
                                                  : "Verbindung zum Wettkampftisch unterbrochen."
                        }
                    }
                }
            }
            try? await Task.sleep(for: .milliseconds(600))
        }
    }

    private func observeClock() async {
        var nextSample: Int64 = 0
        var previousAttempt: UUID?
        var previousPairing: UUID?
        while !Task.isCancelled {
            if let pairing, let heat, heat.sync,
               [.armed, .deskStart, .granted, .uncertain, .running].contains(heat.phase) {
                if previousAttempt != heat.attempt || previousPairing != pairing.id {
                    nextSample = 0
                    previousAttempt = heat.attempt
                    previousPairing = pairing.id
                }
                if monotonicMicros() >= nextSample {
                    let sample = try? await api.estimate(pairing, attempt: heat.attempt, epoch: epoch)
                    if !Task.isCancelled, pairing.id == self.pairing?.id, heat.attempt == self.heat?.attempt {
                        if let sample { estimate = sample }
                        let refresh: Int64 = heat.phase == .armed && !heat.ready ? 200 : (sample?.refreshMs ?? 200)
                        nextSample = monotonicMicros() + max(200, min(1_000, refresh)) * 1_000
                    }
                }
            } else {
                nextSample = 0
                previousAttempt = nil
                previousPairing = nil
            }
            clockUsable = estimate?.usable(at: monotonicMicros()) == true
            try? await Task.sleep(for: .milliseconds(200))
        }
    }

    private func observeDelivery() async {
        while !Task.isCancelled {
            await sendPending()
            try? await Task.sleep(for: .milliseconds(900))
        }
    }

    func sendPending() async {
        guard !sending, storageReady, let pairing else { return }
        sending = true
        defer { sending = false }
        while !Task.isCancelled, self.pairing?.id == pairing.id, storageReady,
              let event = captures.first(where: { $0.pairing == pairing.id && $0.status == .pending }) {
            do {
                try await api.deliver(event, pairing: pairing)
                update(event, status: .accepted)
            } catch DeskFailure.status(401) {
                if self.pairing?.id == pairing.id { expire() }
                return
            } catch DeskFailure.status(let code) where (400..<500).contains(code) && code != 408 && code != 429 {
                update(event, status: .rejected)
            } catch {
                return
            }
        }
    }

    func delivery(for lane: Lane) -> Capture? {
        captures.first { $0.pairing == pairing?.id && $0.attempt == heat?.attempt && $0.lane == lane.id }
    }

    var outstandingFromEarlier: [Capture] {
        captures.filter { event in
            event.status != .accepted && (event.attempt != heat?.attempt || event.pairing != pairing?.id)
        }
    }

    func mayCapture(_ lane: Lane) -> Bool {
        storageReady && heat?.phase == .running && pairing != nil && delivery(for: lane) == nil &&
            (!lane.timed || clockUsable)
    }

    func finish(_ lane: Lane, attempt: UUID) {
        let timestamp = monotonicMicros()
        guard lane.timed, mayCapture(lane), let pairing, heat?.attempt == attempt, let estimate,
              estimate.usable(at: timestamp) else { return }
        append(Capture(pairing: pairing.id, attempt: attempt, lane: lane.id, kind: .finish, micros: timestamp,
                       epoch: epoch, estimate: estimate.id))
    }

    func stopwatch(_ lane: Lane, attempt: UUID, micros: Int64) {
        guard !lane.timed, micros > 0, mayCapture(lane), let pairing, heat?.attempt == attempt else { return }
        append(Capture(pairing: pairing.id, attempt: attempt, lane: lane.id, kind: .stopwatch, micros: micros))
    }

    private func append(_ event: Capture) {
        persist(captures + [event])
    }

    private func update(_ event: Capture, status: Capture.Status) {
        guard let index = captures.firstIndex(where: { $0.id == event.id }) else { return }
        var updated = captures
        updated[index].status = status
        persist(updated)
    }

    private func persist(_ events: [Capture]) {
        do {
            try TimingStore.save(events)
            captures = events
        } catch {
            storageFailed()
        }
    }

    private func expire() {
        unpair()
        if pairing == nil { error = "Die Kopplung ist abgelaufen. Hole am Tisch einen neuen Code." }
    }

    private func storageFailed() {
        storageReady = false
        error = "Der lokale Speicher für die Zeitnahme konnte nicht verwendet werden."
    }
}
