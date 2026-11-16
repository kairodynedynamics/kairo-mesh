import Foundation

/// On-device triage: turns a rushed, misspelt Turkish help message into a structured record. Port of
/// android/.../core/Triyaj.kt and triyaj/model.py (character embedding + case feature, 2-layer bidirectional GRU,
/// per-character span tags and pooled heads). No dependencies; checked against PyTorch outputs
/// (triyaj/telefon/altin.json). Text is handled as Unicode scalars, as Python does, so spans line up with emoji.
public final class Triyaj {
    public struct Kayit: Equatable {
        public let tur: String, aciliyet: Int, kisi: Int?, il: String?, ilce: String?
        public let mahalle: String?, sokak: String?, bina: String?, ad: String?, tel: String?, ozel: [String]
    }

    private let harfler: [UInt32: Int]
    private let uzun: Int
    private let alanlar: [String], turler: [String], ozeller: [String], iller: [String], ciftler: [String]
    private let temel: [String: Int], sayiYazi: [String: Int]
    private let w: [String: [Float]]
    private let harfBoy: Int, tipBoy: Int, gizli2: Int
    private let kodlayici: Kodlayici?
    /// Which model made the message-level decisions: the Turkish encoder, or the character model alone.
    public var modelAdi: String { kodlayici != nil ? "kodlayici" : "harf" }

    public init(meta: Data, weights: Data, kodlayici: Kodlayici? = nil) throws {
        self.kodlayici = kodlayici
        guard let m = try JSONSerialization.jsonObject(with: meta) as? [String: Any],
              let h = m["harfler"] as? [String], let t = m["tensorler"] as? [[String: Any]] else { throw KairoError.malformed }
        var idx: [UInt32: Int] = [:]
        for (i, c) in h.enumerated() { if let s = c.unicodeScalars.first { idx[s.value] = i + 2 } }
        harfler = idx
        uzun = m["uzun"] as? Int ?? 192
        alanlar = m["alanlar"] as? [String] ?? []; turler = m["turler"] as? [String] ?? []
        ozeller = m["ozeller"] as? [String] ?? []; iller = m["iller"] as? [String] ?? []; ciftler = m["ciftler"] as? [String] ?? []
        temel = m["temel_aciliyet"] as? [String: Int] ?? [:]; sayiYazi = m["sayi_yazi"] as? [String: Int] ?? [:]
        let floats: [Float] = weights.withUnsafeBytes { raw in
            (0..<(raw.count / 4)).map { Float(bitPattern: UInt32(littleEndian: raw.loadUnaligned(fromByteOffset: $0 * 4, as: UInt32.self))) }
        }
        var tensors: [String: [Float]] = [:]
        for o in t {
            guard let ad = o["ad"] as? String, let sekil = o["sekil"] as? [Int], let ofs = o["ofs"] as? Int else { throw KairoError.malformed }
            let n = sekil.reduce(1, *)
            guard ofs + n <= floats.count else { throw KairoError.malformed }
            tensors[ad] = Array(floats[ofs..<(ofs + n)])
        }
        w = tensors
        guard let he = w["harf.weight"], let te = w["tip.weight"], let b0 = w["gru.bias_ih_l0"] else { throw KairoError.malformed }
        harfBoy = he.count / (h.count + 2); tipBoy = te.count / 3; gizli2 = 2 * b0.count / 3
    }

    private func kucuk(_ s: Unicode.Scalar) -> UInt32 {
        if s == "İ" { return ("i" as Unicode.Scalar).value }
        if s == "I" { return ("ı" as Unicode.Scalar).value }
        return String(s).lowercased().unicodeScalars.first?.value ?? s.value
    }

    private func tip(_ s: Unicode.Scalar) -> Int {
        switch s.properties.generalCategory {
        case .uppercaseLetter: return 2
        case .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter: return 1
        default: return 0
        }
    }

    /// Raw outputs: per-character tag logits and the five head logit vectors (tür, aciliyet, özel, il, ilçe).
    public func ham(_ metin: String) -> (etiket: [[Float]], tur: [Float], aciliyet: [Float], ozel: [Float], il: [Float], ilce: [Float]) {
        let sc = Array(metin.unicodeScalars.prefix(uzun))
        let he = w["harf.weight"]!, te = w["tip.weight"]!
        let x: [[Float]] = sc.map { s in
            let h = harfler[kucuk(s)] ?? 1 // 0 padding, 1 unknown character
            let tp = tip(s)
            return Array(he[(h * harfBoy)..<((h + 1) * harfBoy)]) + Array(te[(tp * tipBoy)..<((tp + 1) * tipBoy)])
        }
        let y = katman(katman(x, "l0"), "l1")
        let etiket = y.map { lin($0, "etiket") }
        var p = [Float](repeating: 0, count: 2 * gizli2)
        for j in 0..<gizli2 {
            var s: Float = 0, mx = -Float.infinity
            for v in y { s += v[j]; mx = max(mx, v[j]) }
            p[j] = y.isEmpty ? 0 : s / Float(y.count)
            p[gizli2 + j] = y.isEmpty ? -1e4 : mx
        }
        return (etiket, lin(p, "tur"), lin(p, "aciliyet"), lin(p, "ozel"), lin(p, "il"), lin(p, "ilce"))
    }

    private func lin(_ v: [Float], _ ad: String) -> [Float] {
        let W = w[ad + ".weight"]!, b = w[ad + ".bias"]!
        return (0..<b.count).map { o in
            var s = b[o]
            let off = o * v.count
            for i in 0..<v.count { s += W[off + i] * v[i] }
            return s
        }
    }

    private func katman(_ x: [[Float]], _ l: String) -> [[Float]] {
        let ileri = yon(x, "gru.weight_ih_\(l)", "gru.weight_hh_\(l)", "gru.bias_ih_\(l)", "gru.bias_hh_\(l)", ters: false)
        let geri = yon(x, "gru.weight_ih_\(l)_reverse", "gru.weight_hh_\(l)_reverse", "gru.bias_ih_\(l)_reverse", "gru.bias_hh_\(l)_reverse", ters: true)
        return zip(ileri, geri).map { $0 + $1 }
    }

    private func sig(_ v: Float) -> Float { Float(1 / (1 + exp(-Double(v)))) }

    /// One direction of a PyTorch GRU layer (gate order r, z, n).
    private func yon(_ x: [[Float]], _ wi: String, _ wh: String, _ bi: String, _ bh: String, ters: Bool) -> [[Float]] {
        let Wi = w[wi]!, Wh = w[wh]!, Bi = w[bi]!, Bh = w[bh]!
        let h3 = Bi.count, hs = h3 / 3, inp = Wi.count / h3
        var h = [Float](repeating: 0, count: hs)
        var out = [[Float]](repeating: [], count: x.count)
        var gi = [Float](repeating: 0, count: h3), gh = [Float](repeating: 0, count: h3)
        for s in 0..<x.count {
            let t = ters ? x.count - 1 - s : s
            let xt = x[t]
            for o in 0..<h3 {
                var a = Bi[o]; let oi = o * inp
                for i in 0..<inp { a += Wi[oi + i] * xt[i] }
                gi[o] = a
                var b = Bh[o]; let oh = o * hs
                for i in 0..<hs { b += Wh[oh + i] * h[i] }
                gh[o] = b
            }
            var yeni = [Float](repeating: 0, count: hs)
            for k in 0..<hs {
                let r = sig(gi[k] + gh[k]), z = sig(gi[hs + k] + gh[hs + k])
                let n = Float(tanh(Double(gi[2 * hs + k] + r * gh[2 * hs + k])))
                yeni[k] = (1 - z) * n + z * h[k]
            }
            h = yeni
            out[t] = yeni
        }
        return out
    }

    private func argmax(_ v: [Float]) -> Int { var b = 0; for i in v.indices where v[i] > v[b] { b = i }; return b }

    private static let asciiHarf: [Character: Character] = Dictionary(uniqueKeysWithValues: zip("çğıöşüÇĞİÖŞÜâÂîû", "cgiosuCGIOSUaAiu"))

    public func coz(_ metin: String) -> Kayit {
        let r = ham(metin)
        let sc = Array(metin.unicodeScalars)
        func dilim(_ a: Int, _ b: Int) -> String {
            var s = String.UnicodeScalarView(); s.append(contentsOf: sc[a..<b])
            return String(s).trimmingCharacters(in: .whitespaces)
        }
        let etiketler = r.etiket.map(argmax)
        var aralik: [String: (Int, Int)] = [:]
        var j = 0
        while j < etiketler.count {
            let k = etiketler[j]
            if k != 0 && k % 2 == 1 {
                let alan = alanlar[(k - 1) / 2], s = j
                j += 1
                while j < etiketler.count && etiketler[j] == k + 1 { j += 1 }
                if aralik[alan] == nil { aralik[alan] = (s, j) }
            } else { j += 1 }
        }
        func al(_ a: String) -> String? { aralik[a].map { dilim($0.0, $0.1) } }
        let kisi: Int? = al("kisi").flatMap { k in
            if let n = Int(k) { return n }
            let kn = String(k.map { $0 == "İ" ? "i" : $0 == "I" ? "ı" : Character($0.lowercased()) })
            return sayiYazi[kn] ?? sayiYazi[String(kn.map { Self.asciiHarf[$0] ?? $0 })]
        }
        var tel: String? = nil
        if let re = try? NSRegularExpression(pattern: "0?5\\d{2}\\s?\\d{3}\\s?\\d{2}\\s?\\d{2}"),
           let m = re.firstMatch(in: metin, range: NSRange(metin.startIndex..., in: metin)), let rr = Range(m.range, in: metin) {
            let d = metin[rr].filter(\.isNumber)
            tel = "0" + String(d.drop(while: { $0 == "0" }))
        }
        // message-level decisions: the encoder when present (triyaj/model/kodlayici-olcum.json), else the character model
        var bas = kodlayici?.ham(metin) ?? [r.tur, r.aciliyet, r.ozel, r.il, r.ilce]
        let ilI = argmax(bas[3])
        if kodlayici != nil && ilI > 0 { // the district only among the predicted province's districts, as measured
            for x in 1...ciftler.count where !ciftler[x - 1].hasPrefix(iller[ilI - 1] + "/") { bas[4][x] = -1e9 }
        }
        let ilceI = argmax(bas[4])
        var il: String? = ilI > 0 ? iller[ilI - 1] : nil
        var ilce: String? = nil
        if ilceI > 0 {
            let parca = ciftler[ilceI - 1].split(separator: "/", maxSplits: 1).map(String.init)
            if il == nil || il == parca[0] { il = parca[0]; ilce = parca[1] }
        }
        let tur = turler[argmax(bas[0])]
        let ozel = bas[2].indices.filter { bas[2][$0] > 0 }.map { ozeller[$0] }.sorted()
        var kural = temel[tur] ?? 4
        if kural > 1 && !["guvende", "bilgi", "alakasiz"].contains(tur) && !Set(ozel).isDisjoint(with: ["bebek", "yarali", "hamile"]) { kural -= 1 }
        // safety rule: the model may never rank a call as less urgent than the rule does
        let aciliyet = min(argmax(bas[1]) + 1, kural)
        return Kayit(tur: tur, aciliyet: aciliyet, kisi: kisi, il: il, ilce: ilce, mahalle: al("mahalle"), sokak: al("sokak"),
                     bina: al("bina"), ad: al("ad"), tel: tel, ozel: ozel)
    }
}
