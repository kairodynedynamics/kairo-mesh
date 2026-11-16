import Foundation
import KairoCore

/// The authority this phone sends emergency calls to, given as one code by the authority (112, AFAD, a municipality,
/// a pilot partner): `<sealing key, base64>;<signing key, base64>;<https server URL>`. Same format as Android.
struct Kurum {
    let pub: Bytes, signPub: Bytes, url: String

    init?(code: String) {
        let p = code.trimmingCharacters(in: .whitespaces).split(separator: ";").map(String.init)
        guard p.count == 3, p[2].hasPrefix("http"),
              let a = Data(base64Encoded: p[0]), a.count == 32, let s = Data(base64Encoded: p[1]), s.count == 32 else { return nil }
        pub = Bytes(a); signPub = Bytes(s); url = p[2].hasSuffix("/") ? String(p[2].dropLast()) : p[2]
    }

    var code: String { [Data(pub).base64EncodedString(), Data(signPub).base64EncodedString(), url].joined(separator: ";") }

    /// POST the envelope; calls back on the main queue with the 16-byte receipt, or nil to retry later.
    func upload(_ env: Bytes, _ done: @escaping (Bytes?) -> Void) {
        guard let u = URL(string: url + "/v1/acil") else { return done(nil) }
        var req = URLRequest(url: u, timeoutInterval: 15)
        req.httpMethod = "POST"
        req.setValue("application/octet-stream", forHTTPHeaderField: "content-type")
        req.httpBody = Data(env)
        URLSession.shared.dataTask(with: req) { data, res, _ in
            let ok = (res as? HTTPURLResponse)?.statusCode == 200 && data?.count == 16
            DispatchQueue.main.async { done(ok ? Bytes(data!) : nil) }
        }.resume()
    }

    /// GET the authority's current signed announcements; calls back on the main queue.
    func announcements(_ done: @escaping ([Bytes]) -> Void) {
        guard let u = URL(string: url + "/v1/duyuru") else { return done([]) }
        URLSession.shared.dataTask(with: URLRequest(url: u, timeoutInterval: 15)) { data, res, _ in
            var frames: [Bytes] = []
            if (res as? HTTPURLResponse)?.statusCode == 200, let data,
               let list = try? JSONSerialization.jsonObject(with: data) as? [String] {
                frames = list.compactMap { $0.unhex }
            }
            DispatchQueue.main.async { done(frames) }
        }.resume()
    }
}

/// The emergency screen's choices, as the JSON record the authority receives. Same table and rule as Android
/// (Kurum.kt `Cagri`) and the triage model.
enum Cagri {
    static let turler: [(String, String)] = [("enkaz", "Enkaz altındayım"), ("saglik", "Yaralı / hasta var"), ("yangin", "Yangın"),
                                             ("barinma", "Açıkta kaldık"), ("gida_su", "Su / yiyecek yok"),
                                             ("kayip", "Yakınımı arıyorum"), ("guvende", "Güvendeyim")]
    static let ozel: [(String, String)] = [("bebek", "Bebek"), ("cocuk", "Çocuk"), ("yasli", "Yaşlı"), ("yarali", "Yaralı"),
                                           ("hamile", "Hamile"), ("engelli", "Engelli")]
    private static let temel = ["enkaz": 1, "yangin": 1, "saglik": 2, "kayip": 2, "barinma": 3, "gida_su": 3, "guvende": 4]

    static func aciliyet(_ tur: String, _ secili: Set<String>) -> Int {
        let a = temel[tur] ?? 4
        return a > 1 && tur != "guvende" && !secili.isDisjoint(with: ["bebek", "yarali", "hamile"]) ? a - 1 : a
    }

    static func rapor(tur: String, kisi: Int?, ozel secili: Set<String>, metin: String,
                      enlem: Double?, boylam: Double?, dogruluk: Double?, zaman: Date) -> String {
        var r: [String: Any] = ["v": 1, "tur": tur, "aciliyet": aciliyet(tur, secili), "ozel": secili.sorted()]
        if let kisi { r["kisi"] = kisi }
        if !metin.trimmingCharacters(in: .whitespaces).isEmpty { r["metin"] = String(metin.prefix(500)) }
        if let enlem, let boylam {
            var k: [String: Any] = ["enlem": enlem, "boylam": boylam, "zaman": Int64(zaman.timeIntervalSince1970 * 1000)]
            if let dogruluk { k["dogruluk_m"] = dogruluk }
            r["konum"] = k
        }
        let data = (try? JSONSerialization.data(withJSONObject: r, options: [.sortedKeys])) ?? Data("{}".utf8)
        return String(decoding: data, as: UTF8.self)
    }
}
