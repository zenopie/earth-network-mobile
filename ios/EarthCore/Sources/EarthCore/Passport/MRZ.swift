import CryptoKit
import Foundation

/// The machine-readable zone of a TD3 passport: two lines of 44 characters.
///
/// Three of its fields unlock the chip — document number, date of birth, date
/// of expiry — and each carries a check digit. Checking them locally matters
/// because the alternative is a failed NFC session that says only "wrong key",
/// with the passport held against the phone and nothing to point at.
public struct MRZ: Equatable {

    public enum Error: Swift.Error, Equatable {
        case wrongLength(Int)
        case badCharacter(Character)
        case checkDigitFailed(field: String)
    }

    /// The three fields the chip's access key is derived from.
    /// Mutable because this is what a user types, field by field, and the
    /// entry screen binds straight to it.
    public struct Key: Equatable {
        public var documentNumber: String
        /// YYMMDD.
        public var dateOfBirth: String
        /// YYMMDD.
        public var dateOfExpiry: String

        public init(documentNumber: String, dateOfBirth: String, dateOfExpiry: String) {
            self.documentNumber = documentNumber
            self.dateOfBirth = dateOfBirth
            self.dateOfExpiry = dateOfExpiry
        }

        public var isComplete: Bool {
            !documentNumber.trimmingCharacters(in: .whitespaces).isEmpty
                && dateOfBirth.count == 6
                && dateOfExpiry.count == 6
        }
    }

    public let documentCode: String
    public let issuingState: String
    /// Surname and given names as the MRZ carries them, `<` separated.
    public let nameField: String
    public let surname: String
    public let givenNames: String
    public let documentNumber: String
    public let nationality: String
    /// YYMMDD.
    public let dateOfBirth: String
    /// `M`, `F`, or `<`.
    public let sex: String
    /// YYMMDD.
    public let dateOfExpiry: String
    public let personalNumber: String

    public var key: Key {
        Key(documentNumber: documentNumber, dateOfBirth: dateOfBirth, dateOfExpiry: dateOfExpiry)
    }

    /// Parse the 88 characters of a TD3 zone, verifying every check digit.
    ///
    /// Newlines are accepted between the lines because that is how a scan or a
    /// paste usually arrives.
    public init(td3: String) throws {
        let text = td3.filter { !$0.isWhitespace }.uppercased()
        guard text.count == 88 else { throw Error.wrongLength(text.count) }
        let characters = Array(text)

        func field(_ range: Range<Int>) -> String {
            String(characters[range])
        }

        let line2 = 44

        documentCode = field(0 ..< 2).replacingOccurrences(of: "<", with: "")
        issuingState = field(2 ..< 5).replacingOccurrences(of: "<", with: "")
        nameField = field(5 ..< 44)

        // Surname and given names are separated by a double filler; single
        // fillers separate given names from each other.
        let nameParts = nameField.components(separatedBy: "<<")
        surname = MRZ.readable(nameParts.first ?? "")
        givenNames = MRZ.readable(nameParts.dropFirst().joined(separator: "<"))

        documentNumber = field(line2 + 0 ..< line2 + 9).replacingOccurrences(of: "<", with: "")
        nationality = field(line2 + 10 ..< line2 + 13).replacingOccurrences(of: "<", with: "")
        dateOfBirth = field(line2 + 13 ..< line2 + 19)
        sex = field(line2 + 20 ..< line2 + 21)
        dateOfExpiry = field(line2 + 21 ..< line2 + 27)
        personalNumber = field(line2 + 28 ..< line2 + 42).replacingOccurrences(of: "<", with: "")

        try MRZ.verify(field(line2 + 0 ..< line2 + 9), field(line2 + 9 ..< line2 + 10), "document number")
        try MRZ.verify(field(line2 + 13 ..< line2 + 19), field(line2 + 19 ..< line2 + 20), "date of birth")
        try MRZ.verify(field(line2 + 21 ..< line2 + 27), field(line2 + 27 ..< line2 + 28), "date of expiry")
        try MRZ.verify(field(line2 + 28 ..< line2 + 42), field(line2 + 42 ..< line2 + 43), "personal number")

        // The composite digit covers the second line's data fields together, so
        // it catches a transposition between two fields that each still check
        // out on their own.
        let composite = field(line2 + 0 ..< line2 + 10)
            + field(line2 + 13 ..< line2 + 20)
            + field(line2 + 21 ..< line2 + 43)
        try MRZ.verify(composite, field(line2 + 43 ..< line2 + 44), "composite")
    }

    /// ICAO's check digit: weights cycling 7, 3, 1 over the field, mod 10.
    ///
    /// Letters count as their position in the alphabet plus ten, and the filler
    /// `<` counts as zero — which is why a check digit cannot simply be
    /// computed over the ASCII.
    public static func checkDigit(_ field: String) throws -> Int {
        let weights = [7, 3, 1]
        var sum = 0
        for (index, character) in field.enumerated() {
            let value: Int
            switch character {
            case "<": value = 0
            case "0" ... "9": value = Int(String(character))!
            case "A" ... "Z": value = Int(character.asciiValue! - 65) + 10
            default: throw Error.badCharacter(character)
            }
            sum += value * weights[index % 3]
        }
        return sum % 10
    }

    private static func verify(_ field: String, _ digit: String, _ name: String) throws {
        // A filler in place of the digit means the field is unused — a
        // passport with no personal number is the common case.
        if digit == "<" {
            guard field.allSatisfy({ $0 == "<" }) else { throw Error.checkDigitFailed(field: name) }
            return
        }
        guard let expected = Int(digit), try checkDigit(field) == expected else {
            throw Error.checkDigitFailed(field: name)
        }
    }

    private static func readable(_ mrzName: String) -> String {
        mrzName
            .replacingOccurrences(of: "<", with: " ")
            .trimmingCharacters(in: .whitespaces)
    }

    /// The BAC key seed: SHA-1 over the three key fields with their check
    /// digits, truncated to 16 bytes (ICAO Doc 9303 Part 11, §9.7.2).
    ///
    /// Reproduced here rather than left to the NFC library so the MRZ a user
    /// confirms can be checked before the reader session opens — and so this
    /// half is testable without a passport in hand.
    public static func bacKeySeed(_ key: Key) throws -> Data {
        let number = key.documentNumber.padding(toLength: 9, withPad: "<", startingAt: 0).uppercased()
        let material = try number + String(checkDigit(number))
            + key.dateOfBirth + String(checkDigit(key.dateOfBirth))
            + key.dateOfExpiry + String(checkDigit(key.dateOfExpiry))
        return Data(Insecure.SHA1.hash(data: Data(material.utf8)).prefix(16))
    }
}
