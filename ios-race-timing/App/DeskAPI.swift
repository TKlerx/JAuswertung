import Foundation
import TimingCore

enum DeskFailure: Error {
    case status(Int), response
}

private let clockOrigin = ContinuousClock.now

func monotonicMicros() -> Int64 {
    let elapsed = clockOrigin.duration(to: .now).components
    return elapsed.seconds * 1_000_000 + elapsed.attoseconds / 1_000_000_000_000
}

final class RequestObserver: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

struct DeskAPI {
    private let session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 4.5
        config.timeoutIntervalForResource = 6
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.httpShouldSetCookies = false
        config.httpCookieStorage = nil
        config.urlCredentialStorage = nil
        return URLSession(configuration: config, delegate: RequestObserver(), delegateQueue: nil)
    }()

    struct PairReply: Decodable { let zugang: String }

    func pair(origin: DeskOrigin, name: String, code: String) async throws -> String {
        let data = try await post(origin, "paaren", fields(["name": name, "code": code])).data
        let credential = try JSONDecoder().decode(PairReply.self, from: data).zugang
        guard UUID(uuidString: credential) != nil else { throw DeskFailure.response }
        return credential
    }

    func heat(_ pairing: Pairing) async throws -> Heat? {
        try await Heat.decode(post(pairing.origin, "lauf", Data("{}".utf8), bearer: pairing.bearer).data)
    }

    func estimate(_ pairing: Pairing, attempt: UUID, epoch: UUID) async throws -> ClockEstimate {
        struct ClockReply: Decodable {
            let probe: UUID
            let schreibtischEpoche: UUID
            let schreibtischEmpfangen: Int64
            let schreibtischGesendet: Int64
        }
        let sample = try await post(pairing.origin, "uhr", fields(["versuch": attempt.uuidString,
                                                                   "epoche": epoch.uuidString]), bearer: pairing.bearer)
        let reply = try JSONDecoder().decode(ClockReply.self, from: sample.data)
        guard sample.received - sample.sent <= 5_000_000,
              reply.schreibtischGesendet >= reply.schreibtischEmpfangen else { throw DeskFailure.response }
        let data = try await post(pairing.origin, "probe", fields([
            "probe": reply.probe.uuidString, "epoche": epoch.uuidString,
            "telefonGesendet": sample.sent, "telefonEmpfangen": sample.received
        ]), bearer: pairing.bearer).data
        return try JSONDecoder().decode(ClockEstimate.self, from: data)
    }

    func deliver(_ capture: Capture, pairing: Pairing) async throws {
        struct Receipt: Decodable { let angenommen: Bool }
        let data = try await post(pairing.origin, capture.kind.rawValue, capture.body(), bearer: pairing.bearer).data
        guard try JSONDecoder().decode(Receipt.self, from: data).angenommen else {
            throw DeskFailure.response
        }
    }

    private func fields(_ values: [String: Any]) throws -> Data {
        try JSONSerialization.data(withJSONObject: values)
    }

    private func post(_ origin: DeskOrigin, _ path: String, _ body: Data, bearer: String? = nil)
            async throws -> (data: Data, sent: Int64, received: Int64) {
        var request = URLRequest(url: origin.url.appendingPathComponent("api").appendingPathComponent(path))
        request.httpMethod = "POST"
        request.httpBody = body
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let bearer { request.setValue("Bearer " + bearer, forHTTPHeaderField: "Authorization") }
        let sent = monotonicMicros()
        let (data, response) = try await session.data(for: request)
        let received = monotonicMicros()
        guard let response = response as? HTTPURLResponse else { throw DeskFailure.response }
        guard response.statusCode == 200 else { throw DeskFailure.status(response.statusCode) }
        return (data, sent, received)
    }
}
