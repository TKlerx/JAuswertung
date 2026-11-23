import Foundation
import Security
import TimingCore

struct Pairing: Codable {
    let id: UUID
    let origin: DeskOrigin
    let name: String
    let bearer: String
}

enum TimingStore {
    private static let key: [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: "de.df.jauswertung.zeitnahme.ios",
        kSecAttrAccount as String: "desk-session"
    ]

    enum Failure: Error { case keychain(OSStatus) }

    static func pairing() throws -> Pairing? {
        var query = key
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var found: CFTypeRef?
        let result = SecItemCopyMatching(query as CFDictionary, &found)
        if result == errSecItemNotFound { return nil }
        guard result == errSecSuccess, let data = found as? Data else { throw Failure.keychain(result) }
        return try JSONDecoder().decode(Pairing.self, from: data)
    }

    static func save(_ pairing: Pairing) throws {
        let data = try JSONEncoder().encode(pairing)
        var update = [kSecValueData as String: data as Any]
        var result = SecItemUpdate(key as CFDictionary, update as CFDictionary)
        if result == errSecItemNotFound {
            key.forEach { update[$0.key] = $0.value }
            update[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            result = SecItemAdd(update as CFDictionary, nil)
        }
        guard result == errSecSuccess else { throw Failure.keychain(result) }
    }

    static func clearPairing() throws {
        let result = SecItemDelete(key as CFDictionary)
        guard result == errSecSuccess || result == errSecItemNotFound else { throw Failure.keychain(result) }
    }

    private static func journal() throws -> URL {
        var folder = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                                 appropriateFor: nil, create: true)
            .appendingPathComponent("Zeitnahme", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try folder.setResourceValues(values)
        return folder.appendingPathComponent("captures.json")
    }

    static func captures() throws -> [Capture] {
        let file = try journal()
        do {
            return try JSONDecoder().decode([Capture].self, from: Data(contentsOf: file))
        } catch let error as NSError where error.domain == NSCocoaErrorDomain && error.code == NSFileReadNoSuchFileError {
            return []
        }
    }

    static func save(_ captures: [Capture]) throws {
        try JSONEncoder().encode(captures).write(to: journal(), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
}
