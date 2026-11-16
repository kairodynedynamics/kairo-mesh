import Foundation

/// The pre-trained Turkish encoder (ELECTRA-small, dbmdz, MIT; fine-tuned in triyaj/kodlayici.py) that makes the
/// message-level decisions: type, urgency, special status, province, district. Port of
/// android/.../core/Kodlayici.kt: a cased BERT WordPiece tokenizer and the encoder's forward pass, no dependencies.
/// Weights are float16 on disk; all but the word embeddings are widened to Float on load, word embedding rows as used.
/// Checked against PyTorch (triyaj/telefon/kodlayici-altin.json).
public final class Kodlayici {
    private let uzun: Int, katman: Int, bas: Int, eps: Float
    private let sozluk: [String: Int]
    private let unk: Int, cls: Int, sep: Int
    private let w: [String: [Float]]
    private let kelime: [UInt16] // word embeddings, float16 bits
    private let gomme: Int

    public init(meta: Data, weights: Data) throws {
        guard let m = try JSONSerialization.jsonObject(with: meta) as? [String: Any],
              let s = m["sozluk"] as? [String], let t = m["tensorler"] as? [[String: Any]],
              let u = m["uzun"] as? Int, let k = m["katman"] as? Int, let b = m["bas"] as? Int, let e = m["eps"] as? Double
        else { throw KairoError.malformed }
        uzun = u; katman = k; bas = b; eps = Float(e)
        var sz: [String: Int] = [:]
        for (i, x) in s.enumerated() { sz[x] = i }
        sozluk = sz
        guard let un = sz["[UNK]"], let c = sz["[CLS]"], let se = sz["[SEP]"] else { throw KairoError.malformed }
        unk = un; cls = c; sep = se
        let halves: [UInt16] = weights.withUnsafeBytes { raw in
            (0..<(raw.count / 2)).map { UInt16(littleEndian: raw.loadUnaligned(fromByteOffset: $0 * 2, as: UInt16.self)) }
        }
        var tensors: [String: [Float]] = [:], kel: [UInt16] = [], g = 0
        for o in t {
            guard let ad0 = o["ad"] as? String, let sekil = o["sekil"] as? [Int], let ofs = o["ofs"] as? Int else { throw KairoError.malformed }
            let n = sekil.reduce(1, *)
            guard ofs + n <= halves.count else { throw KairoError.malformed }
            let ad = ad0.hasPrefix("govde.") ? String(ad0.dropFirst(6)) : ad0
            if ad == "embeddings.word_embeddings.weight" { kel = Array(halves[ofs..<(ofs + n)]); g = sekil[1] }
            else { tensors[ad] = halves[ofs..<(ofs + n)].map(Kodlayici.yarim) }
        }
        w = tensors; kelime = kel; gomme = g
        guard g > 0 else { throw KairoError.malformed }
    }

    // ---- tokenizer: BertNormalizer (clean text, chinese chars, no lowercasing, no accent stripping), BertPreTokenizer
    // (whitespace, punctuation isolated), WordPiece (greedy longest match, "##", 100 characters at most)

    private static func kontrol(_ s: Unicode.Scalar) -> Bool {
        switch s.properties.generalCategory {
        case .control, .format, .unassigned, .privateUse, .surrogate: return true
        default: return false
        }
    }

    private static func bosluk(_ s: Unicode.Scalar) -> Bool {
        s == " " || s == "\t" || s == "\n" || s == "\r" || s.properties.generalCategory == .spaceSeparator
    }

    private static func noktalama(_ s: Unicode.Scalar) -> Bool {
        let v = s.value
        if (33...47).contains(v) || (58...64).contains(v) || (91...96).contains(v) || (123...126).contains(v) { return true }
        switch s.properties.generalCategory {
        case .connectorPunctuation, .dashPunctuation, .openPunctuation, .closePunctuation,
             .initialPunctuation, .finalPunctuation, .otherPunctuation: return true
        default: return false
        }
    }

    private static func cince(_ v: UInt32) -> Bool {
        (0x4E00...0x9FFF).contains(v) || (0x3400...0x4DBF).contains(v) || (0x20000...0x2A6DF).contains(v) ||
            (0x2A700...0x2B73F).contains(v) || (0x2B740...0x2B81F).contains(v) || (0x2B820...0x2CEAF).contains(v) ||
            (0xF900...0xFAFF).contains(v) || (0x2F800...0x2FA1F).contains(v)
    }

    private func kelimeler(_ metin: String) -> [[Unicode.Scalar]] {
        var out: [[Unicode.Scalar]] = [], cur: [Unicode.Scalar] = []
        func bitir() { if !cur.isEmpty { out.append(cur); cur = [] } }
        for s in metin.unicodeScalars {
            if s.value == 0 || s.value == 0xFFFD { continue }
            if Self.bosluk(s) { bitir(); continue }
            if Self.kontrol(s) { continue }
            if Self.noktalama(s) || Self.cince(s.value) { bitir(); out.append([s]); continue }
            cur.append(s)
        }
        bitir()
        return out
    }

    /// Token ids as the training tokenizer gives them: [CLS] pieces… [SEP], cut to `uzun`. Public for the golden test.
    public func belirtecler(_ metin: String) -> [Int] {
        var ids = [cls]
        for k in kelimeler(metin) {
            if k.count > 100 { ids.append(unk); continue }
            var parca: [Int] = [], bas = 0, bilinmez = false
            while bas < k.count {
                var son = k.count, bulunan = -1
                while son > bas {
                    var v = String.UnicodeScalarView(); v.append(contentsOf: k[bas..<son])
                    if let id = sozluk[(bas > 0 ? "##" : "") + String(v)] { bulunan = id; break }
                    son -= 1
                }
                if bulunan < 0 { bilinmez = true; break }
                parca.append(bulunan); bas = son
            }
            if bilinmez { ids.append(unk) } else { ids += parca }
        }
        return Array(ids.prefix(uzun - 1)) + [sep]
    }

    // ---- forward pass

    private func lin(_ x: [Float], _ ad: String) -> [Float] {
        let W = w[ad + ".weight"]!, b = w[ad + ".bias"]!
        let n = x.count
        var out = b
        W.withUnsafeBufferPointer { wp in
            x.withUnsafeBufferPointer { xp in
                for o in 0..<b.count {
                    var s: Float = 0
                    let off = o * n
                    for i in 0..<n { s += wp[off + i] * xp[i] }
                    out[o] += s
                }
            }
        }
        return out
    }

    private func norm(_ x: [Float], _ ad: String) -> [Float] {
        let g = w[ad + ".weight"]!, b = w[ad + ".bias"]!
        let ort = x.reduce(0, +) / Float(x.count)
        let vr = x.reduce(0) { $0 + ($1 - ort) * ($1 - ort) } / Float(x.count)
        let r = 1 / (vr + eps).squareRoot()
        return x.indices.map { (x[$0] - ort) * r * g[$0] + b[$0] }
    }

    private static func gelu(_ x: Float) -> Float { Float(0.5 * Double(x) * (1 + erf(Double(x) / 2.0.squareRoot()))) }

    /// Raw logits of the five heads (tür, aciliyet, özel, il, ilçe) for `metin`.
    public func ham(_ metin: String) -> [[Float]] {
        let ids = belirtecler(metin)
        let n = ids.count
        let pos = w["embeddings.position_embeddings.weight"]!, tip = w["embeddings.token_type_embeddings.weight"]!
        var x: [[Float]] = (0..<n).map { i in
            let e = (0..<gomme).map { j in Kodlayici.yarim(kelime[ids[i] * gomme + j]) + pos[i * gomme + j] + tip[j] }
            return lin(norm(e, "embeddings.LayerNorm"), "embeddings_project")
        }
        let h = x[0].count, d = h / bas
        let olcek = 1 / Float(d).squareRoot()
        for l in 0..<katman {
            let p = "encoder.layer.\(l)"
            let q = x.map { lin($0, p + ".attention.self.query") }
            let k = x.map { lin($0, p + ".attention.self.key") }
            let v = x.map { lin($0, p + ".attention.self.value") }
            var baglam = [[Float]](repeating: [Float](repeating: 0, count: h), count: n)
            var skor = [Float](repeating: 0, count: n)
            for b in 0..<bas {
                let o = b * d
                for i in 0..<n {
                    var mx = -Float.infinity
                    for j in 0..<n {
                        var s: Float = 0
                        for c in 0..<d { s += q[i][o + c] * k[j][o + c] }
                        s *= olcek; skor[j] = s; mx = max(mx, s)
                    }
                    var top: Float = 0
                    for j in 0..<n { skor[j] = exp(skor[j] - mx); top += skor[j] }
                    for j in 0..<n { let a = skor[j] / top; for c in 0..<d { baglam[i][o + c] += a * v[j][o + c] } }
                }
            }
            x = (0..<n).map { i in
                let ao = lin(baglam[i], p + ".attention.output.dense")
                let a = norm(zip(ao, x[i]).map(+), p + ".attention.output.LayerNorm")
                let ara = lin(a, p + ".intermediate.dense").map(Kodlayici.gelu)
                return norm(zip(lin(ara, p + ".output.dense"), a).map(+), p + ".output.LayerNorm")
            }
        }
        // pooled as in training: the [CLS] vector and the mean over all tokens
        var havuz = x[0] + [Float](repeating: 0, count: h)
        for c in 0..<h { var s: Float = 0; for i in 0..<n { s += x[i][c] }; havuz[h + c] = s / Float(n) }
        return ["tur", "aciliyet", "ozel", "il", "ilce"].map { lin(havuz, $0) }
    }

    /// IEEE 754 half to Float (Float16 is not available on every platform this builds for).
    public static func yarim(_ hb: UInt16) -> Float {
        let isaret = UInt32(hb >> 15) << 31
        let us = UInt32((hb >> 10) & 0x1F), kesir = UInt32(hb & 0x3FF)
        let f: UInt32
        if us == 0 && kesir == 0 { f = isaret }
        else if us == 0 { // subnormal
            var e: UInt32 = 127 - 15 + 1, k = kesir
            while k & 0x400 == 0 { k <<= 1; e -= 1 }
            f = isaret | (e << 23) | ((k & 0x3FF) << 13)
        } else if us == 31 { f = isaret | 0x7F80_0000 | (kesir << 13) }
        else { f = isaret | ((us + 127 - 15) << 23) | (kesir << 13) }
        return Float(bitPattern: f)
    }
}
