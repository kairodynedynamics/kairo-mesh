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
            let kod = (res as? HTTPURLResponse)?.statusCode
            let ok = kod == 200 && data?.count == 16
            // 422: refused for good, an empty receipt tells the node to drop it; otherwise nil retries later
            DispatchQueue.main.async { done(kod == 422 ? [] : ok ? Bytes(data!) : nil) }
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
    // Life-threatening situations first: the four large buttons (docs/08-acil-ekran.md). The rest are needs.
    static let turler: [(String, String)] = [("enkaz", "Enkaz altındayım"), ("saglik", "Yaralı / hasta var"), ("yangin", "Yangın / gaz"),
                                             ("yardim", "Başka acil durum"),
                                             ("barinma", "Açıkta kaldık"), ("gida_su", "Su / yiyecek yok"),
                                             ("kayip", "Yakınımı arıyorum"), ("guvende", "Güvendeyim")]
    static let hayati = ["enkaz", "saglik", "yangin", "yardim"]
    static let ihtiyac = ["barinma", "gida_su", "kayip"]
    static let altYazi = ["enkaz": "Bina çöktü, çıkamıyorum", "saglik": "Acil sağlık yardımı gerekiyor",
                          "yangin": "Ateş, duman ya da gaz kokusu", "yardim": "Ne olduğunu yazarak anlatın"]
    static func ad(_ tur: String) -> String { turler.first { $0.0 == tur }?.1 ?? tur }
    static func ozelAd(_ k: String) -> String { ozel.first { $0.0 == k }?.1 ?? k }
    static let ozel: [(String, String)] = [("bebek", "Bebek"), ("cocuk", "Çocuk"), ("yasli", "Yaşlı"), ("yarali", "Yaralı"),
                                           ("hamile", "Hamile"), ("engelli", "Engelli")]
    private static let temel = ["enkaz": 1, "yangin": 1, "saglik": 2, "yardim": 2, "kayip": 2, "barinma": 3, "gida_su": 3, "guvende": 4]

    static func aciliyet(_ tur: String, _ secili: Set<String>) -> Int {
        let a = temel[tur] ?? 4
        return a > 1 && tur != "guvende" && !secili.isDisjoint(with: ["bebek", "yarali", "hamile"]) ? a - 1 : a
    }

    /// "I sent that by mistake / I am safe now": points the authority at the earlier call `ref`.
    static func duzeltme(ref: String) -> String {
        let r: [String: Any] = ["v": 1, "tur": "iptal", "aciliyet": 4, "iptal_ref": ref, "zaman": Int64(Date().timeIntervalSince1970 * 1000),
                                "metin": "Önceki çağrı yanlışlıkla gönderildi ya da artık yardım gerekmiyor."]
        return String(decoding: (try? JSONSerialization.data(withJSONObject: r, options: [.sortedKeys])) ?? Data("{}".utf8), as: UTF8.self)
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
