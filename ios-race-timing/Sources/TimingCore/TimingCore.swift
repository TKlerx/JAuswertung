import Foundation

public struct DeskOrigin: Codable, Equatable {
    public let url: URL

    public init?(_ value: String) {
        guard !value.unicodeScalars.contains(where: { $0.value < 32 || $0.value == 127 }),
              var parts = URLComponents(string: value.trimmingCharacters(in: .whitespaces)),
              parts.scheme?.lowercased() == "https",
              let host = parts.host, !host.isEmpty, !host.contains("%"),
              parts.user == nil, parts.password == nil, parts.query == nil, parts.fragment == nil,
              parts.percentEncodedPath == "" || parts.percentEncodedPath == "/",
              parts.port.map({ (1...65535).contains($0) }) ?? !hasExplicitEmptyPort(value)
        else { return nil }
        parts.scheme = "https"
        parts.host = host.lowercased()
        parts.path = ""
        if parts.port == 443 { parts.port = nil }
        guard let url = parts.url else { return nil }
        self.url = url
    }

    public init(from decoder: Decoder) throws {
        let value = try decoder.singleValueContainer().decode(String.self)
        guard let origin = Self(value) else { throw TimingError.invalidAddress }
        self = origin
    }

    public func encode(to encoder: Encoder) throws {
        var value = encoder.singleValueContainer()
        try value.encode(url.absoluteString)
    }
}

private func hasExplicitEmptyPort(_ value: String) -> Bool {
    let address = value.trimmingCharacters(in: .whitespaces)
    return address.hasSuffix(":") || address.hasSuffix(":/")
}

public enum TimingError: Error {
    case invalidAddress, invalidReply
}

public struct Lane: Decodable, Identifiable {
    public let id: Int
    public let name: String
    public let timed: Bool

    private enum CodingKeys: String, CodingKey {
        case id = "nummer", name = "teilnehmer", timed = "telefonzeit"
    }
}

public struct Heat: Decodable {
    public enum Phase: String, Decodable {
        case armed = "BEREIT", deskStart = "TISCHSTART", granted = "FREIGEGEBEN"
        case uncertain = "START_UNSICHER", running = "LAEUFT", closed = "GESCHLOSSEN", applied = "UEBERNOMMEN"
    }

    public let attempt: UUID
    public let id: UUID
    public let discipline: String
    public let phase: Phase
    public let ready: Bool
    public let sync: Bool
    public let lanes: [Lane]

    private enum CodingKeys: String, CodingKey {
        case attempt = "versuch", id = "lauf", discipline = "disziplin", phase = "zustand"
        case ready = "bereit", sync = "abgleich", lanes = "bahnen"
    }

    public static func decode(_ data: Data) throws -> Heat? {
        struct Header: Decodable { let versuch: UUID? }
        let decoder = JSONDecoder()
        guard try decoder.decode(Header.self, from: data).versuch != nil else { return nil }
        let heat = try decoder.decode(Heat.self, from: data)
        let ids = heat.lanes.map(\.id)
        guard ids.allSatisfy({ $0 > 0 }), Set(ids).count == ids.count else { throw TimingError.invalidReply }
        return heat
    }
}

public struct ClockEstimate: Decodable {
    public let id: UUID
    public let offset: Int64
    public let uncertainty: Int64
    public let reference: Int64
    public let driftPpm: Int64
    public let refreshMs: Int64

    private enum CodingKeys: String, CodingKey {
        case id, offset = "versatz", uncertainty = "unsicherheit"
        case reference = "bezugszeit", driftPpm, refreshMs = "auffrischungMs"
    }

    public func usable(at time: Int64) -> Bool {
        guard time >= 0, (0...10_000).contains(uncertainty),
              (0..<1_000_000).contains(driftPpm), refreshMs >= 200 else { return false }
        let (mapped, mapOverflow) = time.addingReportingOverflow(offset)
        let (delta, deltaOverflow) = mapped.subtractingReportingOverflow(reference)
        guard !mapOverflow, !deltaOverflow else { return false }
        let (drift, driftOverflow) = delta.magnitude.multipliedReportingOverflow(by: UInt64(driftPpm))
        let remaining = UInt64(10_000 - uncertainty) * 1_000_000
        return !driftOverflow && drift <= remaining
    }
}

public struct Capture: Codable, Identifiable {
    public enum Kind: String, Codable { case finish = "ziel", stopwatch = "stoppuhr" }
    public enum Status: String, Codable { case pending, accepted, rejected, blocked }

    public let id: UUID
    public let pairing: UUID
    public let attempt: UUID
    public let lane: Int
    public let kind: Kind
    public let micros: Int64
    public let epoch: UUID?
    public let estimate: UUID?
    public var status: Status

    public init(pairing: UUID, attempt: UUID, lane: Int, kind: Kind, micros: Int64,
                epoch: UUID? = nil, estimate: UUID? = nil) {
        self.id = UUID()
        self.pairing = pairing
        self.attempt = attempt
        self.lane = lane
        self.kind = kind
        self.micros = micros
        self.epoch = epoch
        self.estimate = estimate
        self.status = .pending
    }

    public func body() throws -> Data {
        var fields: [String: Any] = ["versuch": attempt.uuidString, "ereignis": id.uuidString, "bahn": lane]
        switch kind {
        case .finish:
            guard let epoch, let estimate, micros >= 0 else { throw TimingError.invalidReply }
            fields["zeit"] = micros
            fields["epoche"] = epoch.uuidString
            fields["schaetzung"] = estimate.uuidString
        case .stopwatch:
            guard micros > 0 else { throw TimingError.invalidReply }
            fields["dauer"] = micros
        }
        return try JSONSerialization.data(withJSONObject: fields, options: [.sortedKeys])
    }
}

public func stopwatchMicros(_ input: String) -> Int64? {
    let value = input.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: ",", with: ".")
    let decimal = value.split(separator: ".", omittingEmptySubsequences: false)
    guard (1...2).contains(decimal.count) else { return nil }
    let parts = decimal[0].split(separator: ":", omittingEmptySubsequences: false)
    guard (1...2).contains(parts.count), parts.allSatisfy({ !$0.isEmpty && $0.utf8.allSatisfy { (48...57).contains($0) } }),
          let leading = Int64(parts[0]) else { return nil }
    var seconds = leading
    if parts.count == 2 {
        guard parts[1].count == 2, let tail = Int64(parts[1]), tail < 60 else { return nil }
        let (minutes, overflow) = leading.multipliedReportingOverflow(by: 60)
        guard !overflow, minutes <= Int64.max - tail else { return nil }
        seconds = minutes + tail
    }
    var micros: Int64 = 0
    if decimal.count == 2 {
        let fraction = decimal[1]
        guard (1...6).contains(fraction.count), fraction.utf8.allSatisfy({ (48...57).contains($0) }),
              let padded = Int64(fraction + String(repeating: "0", count: 6 - fraction.count)) else { return nil }
        micros = padded
    }
    guard seconds <= (Int64.max - micros) / 1_000_000 else { return nil }
    let duration = seconds * 1_000_000 + micros
    return duration > 0 ? duration : nil
}
