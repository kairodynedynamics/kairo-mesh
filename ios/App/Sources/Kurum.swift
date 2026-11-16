import Foundation
import KairoCore

/// The authority this phone sends emergency calls to, given as one code by the authority (112, AFAD, a municipality,
/// a pilot partner) and accepted only when a root built into the app approved it (KairoCore.KurumKodu).
struct Kurum {
    let pub: Bytes, signPub: Bytes, url: String, ad: String, code: String

    // Root keys that may approve authority codes (src/kurum-cli.js kok/onayla). Release builds trust only the
    // production root, whose private half is kept offline; debug builds also trust the public test root (test/kok-test).
    static let kokler: [Bytes] = {
        var k = ["KMrne+ak0Iqr3rLzZ9uI7Qic8fWv6o/Na2H15FF25So="]
        #if DEBUG
        k.append("mCZWxnPAXq0pTVoc5wwzaPbHgJvMARPQX626+hmR+zs=")
        #endif
        return k.map { Bytes(Data(base64Encoded: $0)!) }
    }()

    /// Nil for a malformed code or one no trusted root approved (a fake code is never stored).
    init?(code: String) {
        guard let k = KurumKodu(code, kokler: Kurum.kokler) else { return nil }
        pub = k.pub; signPub = k.signPub; url = k.url; ad = k.ad
        self.code = code.trimmingCharacters(in: .whitespacesAndNewlines)
    }

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

    /// `adres`: il, ilçe, mahalle, sokak, bina read from the message by the on-device model, when there was text.
    static func rapor(tur: String, kisi: Int?, ozel secili: Set<String>, metin: String,
                      enlem: Double?, boylam: Double?, dogruluk: Double?, zaman: Date,
                      adres: [String: String?] = [:], aciliyet modelAciliyet: Int? = nil, model: Bool = false) -> String {
        var r: [String: Any] = ["v": 1, "tur": tur, "aciliyet": min(modelAciliyet ?? 4, aciliyet(tur, secili)), "ozel": secili.sorted()]
        if let kisi { r["kisi"] = kisi }
        for (k, v) in adres { if let v, !v.isEmpty { r[k] = v } }
        if model { r["kaynak"] = "telefon-modeli" }
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
