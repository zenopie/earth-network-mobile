import CryptoKit
import Foundation

/// The passport circuits' SRS as a local file (audit 3).
///
/// The privacy circuits' SRS ships in the app (32,769 points, 2 MiB), so a
/// private proof never fetches anything. The passport circuits need up to
/// 2^19 + 1 points (brainpool512, ~425k gates: 32 MiB), too large to bundle,
/// so it is fetched once, at launch, independent of anything the wallet
/// does: the first 524,289 points of Aztec's transcript
/// (crs.aztec.network/g1.dat, a byte range), checked against a pinned hash,
/// kept in Application Support (excluded from backups). While it is missing a
/// passport proof downloads its own SRS as before (registration is public
/// anyway); a private proof never reserves the passport size from the
/// network.
public enum PassportSRS {
    /// 2^19 + 1 points of 64 bytes.
    public static let points: UInt64 = (1 << 19) + 1
    public static let sha256 = "1df37a2ce1da3713c7300691a65ffe84de144ea64899d5cfbf0061aaaeb6ad31"
    static let source = URL(string: "https://crs.aztec.network/g1.dat")!

    static var file: URL? {
        (try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true))?
            .appendingPathComponent("srs/bn254_g1_524289.dat")
    }

    /// The local file when it is complete (its hash was checked when it was written); nil otherwise.
    public static var path: String? {
        guard let f = file, let size = (try? FileManager.default.attributesOfItem(atPath: f.path))?[.size] as? NSNumber,
              size.uint64Value == points * 64 else { return nil }
        return f.path
    }

    /// Fetches the file if it is missing. Call once at launch, detached; a
    /// failure leaves nothing behind and is retried on the next launch.
    public static func prefetch() async {
        guard path == nil, let dest = file else { return }
        var r = URLRequest(url: source)
        r.setValue("bytes=0-\(points * 64 - 1)", forHTTPHeaderField: "Range")
        r.timeoutInterval = 120
        guard let (tmp, resp) = try? await URLSession.shared.download(for: r),
              let status = (resp as? HTTPURLResponse)?.statusCode, status == 206 || status == 200 else { return }
        defer { try? FileManager.default.removeItem(at: tmp) }
        guard let h = try? FileHandle(forReadingFrom: tmp) else { return }
        var hasher = SHA256()
        var n: UInt64 = 0
        while n < points * 64, let chunk = try? h.read(upToCount: Int(min(1 << 20, points * 64 - n))), !chunk.isEmpty {
            hasher.update(data: chunk)
            n += UInt64(chunk.count)
        }
        try? h.close()
        guard n == points * 64, hasher.finalize().map({ String(format: "%02x", $0) }).joined() == sha256 else { return }
        let dir = dest.deletingLastPathComponent()
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var d = dir
        var v = URLResourceValues()
        v.isExcludedFromBackup = true
        try? d.setResourceValues(v)
        let staged = dir.appendingPathComponent("bn254_g1_524289.dat.part")
        try? FileManager.default.removeItem(at: staged)
        guard (try? FileManager.default.copyItem(at: tmp, to: staged)) != nil else { return }
        // A file longer than the range (a server that ignored Range) is cut to it.
        if let w = try? FileHandle(forWritingTo: staged) { try? w.truncate(atOffset: points * 64); try? w.close() }
        _ = try? FileManager.default.replaceItemAt(dest, withItemAt: staged)
        if !FileManager.default.fileExists(atPath: dest.path) { try? FileManager.default.moveItem(at: staged, to: dest) }
    }
}
