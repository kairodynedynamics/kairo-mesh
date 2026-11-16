import AVFoundation
import SwiftUI
import UIKit

@main
struct KairoMeshApp: App {
    @StateObject private var mesh = Mesh()
    var body: some Scene { WindowGroup { ContentView().environmentObject(mesh) } }
}

/// KairoMesh's one screen, the same as Android's (docs/08-acil-ekran.md): four large life-threatening situations,
/// the selection written out with an undo, typing alone enough, a summary of exactly what will be sent, a 5-second
/// countdown with a large CANCEL, a correction for a call sent by mistake, a sound signal for rescuers.
/// Light grey, slightly glossy theme with near-black text.
struct ContentView: View {
    @EnvironmentObject private var mesh: Mesh
    @State private var tur: String?
    @State private var kisi: Int?
    @State private var ozel = Set<String>()
    @State private var metin = ""
    @State private var geriSayim: Int?
    @State private var sinyal = false
    private let sayac = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        ZStack {
            LinearGradient(colors: [Tema.zeminUst, Tema.zeminAlt], startPoint: .top, endPoint: .bottom).ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    baslik
                    acilKart
                    Button { sinyalDegistir() } label: {
                        Text(sinyal ? "■ Sesli sinyali durdur" : "♪ Sesli sinyal · kurtarıcılar sizi duysun").kalinDugme(Tema.kart, Tema.metin, kenar: Tema.cizgi)
                    }.accessibilityIdentifier("sinyal")
                    Button {
                        tur = "guvende"; gonderBas() // one tap: "I am safe" never sends anything urgent
                    } label: {
                        Text("Güvendeyim · kuruma bildir")
                            .kalinDugme(Tema.yesilZemin, Tema.yesil)
                    }
                    kart {
                        Text("Kurum duyuruları").font(.title3.weight(.semibold)).foregroundColor(Tema.metin)
                        if mesh.duyurular.isEmpty { Text("Henüz duyuru yok.").foregroundColor(Tema.ikincil) }
                        ForEach(mesh.duyurular, id: \.self) { Text($0).foregroundColor(Tema.metin) }
                    }
                    #if DEBUG
                    // the technical log, for development and UI tests only
                    Text(mesh.log.joined(separator: "\n")).font(.caption2.monospaced()).foregroundColor(Tema.ikincil)
                        .frame(maxWidth: .infinity, alignment: .leading).accessibilityIdentifier("log")
                    #endif
                }
                .padding(16)
            }
            if let kalan = geriSayim { geriSayimKatmani(kalan) }
        }
        .preferredColorScheme(.light)
        .onAppear { UIApplication.shared.isIdleTimerDisabled = true; mesh.konumIste() } // the screen must not go dark under rubble
        .onReceive(sayac) { _ in
            guard let k = geriSayim else { return }
            if k <= 1 { gonder() } else { geriSayim = k - 1; UIAccessibility.post(notification: .announcement, argument: "\(k - 1)") }
        }
    }

    // MARK: parts

    private var baslik: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 10) {
                Circle().fill(Tema.kirmizi).frame(width: 14, height: 14)
                Text("KairoMesh").font(.system(size: 28, weight: .heavy)).foregroundColor(Tema.metin)
            }
            Text(mesh.kurumVar ? "Çağrılar şu kuruma gider: \(mesh.kurumAd)" : "Bu sürümde çağrıların gideceği kurum tanımlı değil (kurumla sözleşme öncesi sürüm).")
                .font(.subheadline).foregroundColor(Tema.ikincil)
            Text(agYazisi).font(.callout.bold()).foregroundColor(mesh.internet || mesh.yakin > 0 ? Tema.yesil : Tema.amber)
        }
    }

    private var agYazisi: String {
        if mesh.internet { return "● İnternet var · çağrılar doğrudan kuruma gider" }
        if mesh.yakin > 0 { return "● İnternet yok · yakında çağrınızı taşıyabilecek \(mesh.yakin) telefon var" }
        return "● İnternet yok · yakında KairoMesh'li telefon yok; biri yaklaşınca çağrınız gider"
    }

    private var acilKart: some View {
        kart {
            Text("Acil yardım çağrısı").font(.title2.bold()).foregroundColor(Tema.metin)
            Text("İnternet olmasa da çalışır. Durumunuzu seçin ya da yazın, sonra gönderin.").font(.subheadline).foregroundColor(Tema.ikincil)
            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 8) {
                ForEach(Cagri.hayati, id: \.self) { k in durumDugmesi(k, buyuk: true) }
            }
            HStack {
                Text(tur.map { "Seçili: \(Cagri.ad($0))" } ?? "Durum seçilmedi").font(.headline)
                    .foregroundColor(tur == nil ? Tema.ikincil : Tema.kirmizi).accessibilityIdentifier("secim")
                Spacer()
                if tur != nil {
                    Button("Kaldır") { secimDegistir(nil) }.font(.headline).foregroundColor(Tema.kirmizi)
                        .padding(.horizontal, 18).frame(minHeight: 44)
                        .background(Capsule().fill(Tema.kirmiziZemin))
                        .accessibilityLabel("Seçimi kaldır")
                }
            }
            Text("Kaç kişisiniz?").font(.headline).foregroundColor(Tema.metin)
            HStack(spacing: 14) {
                Button { kisi = kisi.flatMap { $0 <= 1 ? nil : $0 - 1 } } label: { Text("−").font(.system(size: 30, weight: .bold)).frame(width: 64, height: 54) }
                    .buttonStyle(KutuStil()).accessibilityLabel("Bir azalt")
                Text(kisi.map(String.init) ?? "?").font(.system(size: 30, weight: .heavy)).frame(minWidth: 50).foregroundColor(Tema.metin)
                Button { kisi = min(99, (kisi ?? 0) + 1) } label: { Text("+").font(.system(size: 30, weight: .bold)).frame(width: 64, height: 54) }
                    .buttonStyle(KutuStil()).accessibilityLabel("Bir artır")
                Text("? = bilinmiyor").font(.subheadline).foregroundColor(Tema.ikincil)
            }
            Text("Yanınızda").font(.headline).foregroundColor(Tema.metin)
            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())], spacing: 6) {
                ForEach(Cagri.ozel, id: \.0) { item in
                    let secili = ozel.contains(item.0)
                    Button { if secili { ozel.remove(item.0) } else { ozel.insert(item.0) } } label: {
                        Text((secili ? "✓ " : "") + item.1).font(.headline).frame(maxWidth: .infinity, minHeight: 50)
                            .foregroundColor(secili ? Tema.kirmizi : Tema.metin)
                            .background(Capsule().fill(secili ? Tema.kirmiziZemin : Tema.ozetZemin))
                            .overlay(Capsule().stroke(secili ? Tema.kirmizi : Tema.cizgi, lineWidth: 1))
                    }.accessibilityValue(secili ? "seçili" : "seçili değil")
                }
            }
            Text("Ya da kısaca yazın").font(.headline).foregroundColor(Tema.metin)
            TextField("Adres ve durum: ör. Antakya Ürgenpaşa Mh., enkazdayız, 3 kişi", text: $metin, axis: .vertical)
                .font(.body).lineLimit(2...5).padding(12).foregroundColor(Tema.metin)
                .background(RoundedRectangle(cornerRadius: 12).fill(Color.white)).overlay(RoundedRectangle(cornerRadius: 12).stroke(Tema.cizgi, lineWidth: 1))
                .accessibilityIdentifier("metin")
            if !metin.trimmingCharacters(in: .whitespaces).isEmpty {
                Text(tur.map { "“\(Cagri.ad($0))” seçimi de yazınızla birlikte gönderilecek. İstemiyorsanız seçimi kaldırın." }
                     ?? "Durum seçmediniz: telefon yazınızdan anlayacak ve çağrıyı acil olarak gönderecek.")
                    .font(.subheadline.bold()).foregroundColor(Tema.amber)
            }
            Text(ozetYazisi).foregroundColor(Tema.metin).frame(maxWidth: .infinity, alignment: .leading).padding(12)
                .background(RoundedRectangle(cornerRadius: 12).fill(Tema.ozetZemin))
            Button { gonderBas() } label: { Text("Çağrıyı gönder").font(.title2.bold()).frame(maxWidth: .infinity, minHeight: 64) }
                .foregroundColor(.white).background(RoundedRectangle(cornerRadius: 14).fill(Tema.kirmizi)).accessibilityIdentifier("acilGonder")
            if !mesh.acilDurum.isEmpty {
                VStack(alignment: .leading, spacing: 10) {
                    Text(mesh.acilDurum).font(.headline).foregroundColor(Tema.metin).accessibilityIdentifier("acilDurum")
                    if mesh.sonCagriId != nil && !mesh.kurumAldi {
                        Button {
                            // a second tap confirms: correcting a real call by accident would be as bad as a false one
                            mesh.duzelt() // one tap: if it was itself a mistake, the call can be sent again at once
                        } label: {
                            Text("Yanlışlıkla gönderdim · yardım gerekmiyor")
                                .kalinDugme(Tema.kart, Tema.metin, kenar: Tema.cizgi)
                        }.accessibilityIdentifier("duzelt")
                    }
                }.padding(14).frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 14).fill(mesh.kurumAldi ? Tema.yesilZemin : Tema.kirmiziZemin))
            }
        }
    }

    private func durumDugmesi(_ k: String, buyuk: Bool) -> some View {
        let secili = tur == k
        return Button { secimDegistir(k) } label: {
            VStack(alignment: buyuk ? .leading : .center, spacing: 4) {
                Text(Cagri.ad(k)).font(buyuk ? .title3.bold() : .subheadline.bold())
                    .foregroundColor(secili ? .white : (buyuk ? Tema.kirmizi : Tema.metin)).multilineTextAlignment(buyuk ? .leading : .center)
                if buyuk || secili {
                    Text(secili ? "✓ Seçildi · kaldırmak için yine dokunun" : (Cagri.altYazi[k] ?? ""))
                        .font(buyuk ? .subheadline : .caption2).foregroundColor(secili ? .white : Tema.ikincil)
                }
            }
            .frame(maxWidth: .infinity, minHeight: buyuk ? 96 : 60, alignment: buyuk ? .topLeading : .center).padding(12)
            .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(secili ? Tema.kirmizi : Tema.ozetZemin))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(secili ? Tema.kirmizi : (buyuk ? Tema.kirmiziKenar : Tema.cizgi), lineWidth: 1))
        }
        .accessibilityIdentifier("tur_\(k)").accessibilityValue(secili ? "seçili" : "seçili değil")
    }

    private var ozetYazisi: String {
        let yazili = !metin.trimmingCharacters(in: .whitespaces).isEmpty
        var s = "Gönderilecekler\n• Durum: \(tur.map(Cagri.ad) ?? (yazili ? "yazdığınızdan anlaşılacak" : "—"))\n"
        s += "• Kişi sayısı: \(kisi.map(String.init) ?? "belirtilmedi")\n"
        s += "• Yanınızda: \(ozel.isEmpty ? "belirtilmedi" : ozel.sorted().map(Cagri.ozelAd).joined(separator: ", "))\n"
        if yazili { s += "• Mesaj: \"\(String(metin.trimmingCharacters(in: .whitespaces).prefix(80)))\"\n" }
        return s + "• Konum: izin verildiyse GPS eklenir"
    }

    private func geriSayimKatmani(_ kalan: Int) -> some View {
        ZStack {
            Color(red: 14 / 255, green: 26 / 255, blue: 34 / 255).opacity(0.97).ignoresSafeArea()
            VStack(spacing: 22) {
                Text("Çağrı \(kalan) saniye içinde\ngönderilecek").font(.title.bold()).foregroundColor(.white).multilineTextAlignment(.center)
                Button { geriSayim = nil; mesh.iptalBildir() } label: { Text("İptal et").font(.largeTitle.bold()).frame(maxWidth: .infinity, minHeight: 96) }
                    .foregroundColor(Tema.kirmizi).background(RoundedRectangle(cornerRadius: 16).fill(Color.white)).accessibilityIdentifier("iptal")
                Button { gonder() } label: { Text("Hemen gönder").font(.title3.bold()).frame(maxWidth: .infinity, minHeight: 60) }
                    .foregroundColor(.white).background(RoundedRectangle(cornerRadius: 14).fill(Tema.kirmizi))
                    .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color.white, lineWidth: 2)).accessibilityIdentifier("hemenGonder")
            }.padding(24)
        }
    }

    private func kart<C: View>(@ViewBuilder _ icerik: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 12, content: icerik).padding(16).frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Tema.kart).shadow(color: .black.opacity(0.06), radius: 3, y: 1))
    }

    // MARK: actions

    private func secimDegistir(_ k: String?) {
        // tapping the selected situation again clears it: a mistaken tap is undone where it happened
        tur = (k == nil || tur == k) ? nil : k
        UIAccessibility.post(notification: .announcement, argument: tur.map { "\(Cagri.ad($0)) seçildi" } ?? "Seçim kaldırıldı")
    }

    private func gonderBas() {
        guard tur != nil || !metin.trimmingCharacters(in: .whitespaces).isEmpty else { return mesh.durumYaz("Önce durumunuzu seçin ya da kısaca yazın.") }
        guard mesh.kurumVar else { return mesh.kurumYok() }
        mesh.konumIste()
        if tur == "guvende" { return gonder() } // "I am safe" has no countdown: nothing urgent can go out by mistake
        geriSayim = 5 // a stray tap must not send a call: five seconds with a large CANCEL
    }

    private func gonder() {
        geriSayim = nil
        mesh.emergency(tur: tur, kisi: kisi, ozel: ozel, metin: metin)
    }

    private func sinyalDegistir() {
        sinyal.toggle()
        if sinyal { Siren.shared.baslat() } else { Siren.shared.durdur() }
    }
}

/// A loud repeating tone so rescuers can find a person they cannot see. Plays through the speaker even when the
/// ringer is silent.
final class Siren {
    static let shared = Siren()
    private var oynatici: AVAudioPlayer?

    func baslat() {
        try? AVAudioSession.sharedInstance().setCategory(.playback)
        try? AVAudioSession.sharedInstance().setActive(true)
        oynatici = try? AVAudioPlayer(data: Siren.ton())
        oynatici?.numberOfLoops = -1
        oynatici?.volume = 1
        oynatici?.play()
    }

    func durdur() { oynatici?.stop(); oynatici = nil }

    /// 0.7 s of 3.1 kHz (a whistle's pitch, carries well through debris) then 0.9 s silence, as 16-bit mono WAV.
    private static func ton() -> Data {
        let hz = 22050, n = Int(Double(hz) * 1.6)
        var ornek = [Int16](repeating: 0, count: n)
        for i in 0..<Int(Double(hz) * 0.7) { ornek[i] = Int16(sin(2 * Double.pi * 3100 * Double(i) / Double(hz)) * 30000) }
        var d = Data()
        func ekle<T>(_ v: T) { withUnsafeBytes(of: v) { d.append(contentsOf: $0) } }
        d.append(contentsOf: Array("RIFF".utf8)); ekle(UInt32(36 + n * 2).littleEndian); d.append(contentsOf: Array("WAVEfmt ".utf8))
        ekle(UInt32(16).littleEndian); ekle(UInt16(1).littleEndian); ekle(UInt16(1).littleEndian); ekle(UInt32(hz).littleEndian)
        ekle(UInt32(hz * 2).littleEndian); ekle(UInt16(2).littleEndian); ekle(UInt16(16).littleEndian)
        d.append(contentsOf: Array("data".utf8)); ekle(UInt32(n * 2).littleEndian)
        for o in ornek { ekle(o.littleEndian) }
        return d
    }
}

/// Apple's grouped light surfaces (same values as Android's MainActivity): a light grey ground with a soft sheen, white
/// cards without frames, near-black text (secondary text 9:1, readable on a cracked screen), one accent: red.
enum Tema {
    static let zeminUst = Color(red: 242 / 255, green: 242 / 255, blue: 247 / 255)
    static let zeminAlt = Color(red: 226 / 255, green: 226 / 255, blue: 232 / 255)
    static let kart = Color.white
    static let cizgi = Color(red: 209 / 255, green: 209 / 255, blue: 214 / 255)
    static let ozetZemin = Color(red: 242 / 255, green: 242 / 255, blue: 247 / 255)
    static let metin = Color(red: 28 / 255, green: 28 / 255, blue: 30 / 255)
    static let ikincil = Color(red: 72 / 255, green: 72 / 255, blue: 74 / 255)
    static let kirmizi = Color(red: 179 / 255, green: 38 / 255, blue: 30 / 255)
    static let kirmiziKenar = Color(red: 240 / 255, green: 190 / 255, blue: 186 / 255)
    static let kirmiziZemin = Color(red: 253 / 255, green: 236 / 255, blue: 234 / 255)
    static let yesil = Color(red: 11 / 255, green: 110 / 255, blue: 79 / 255)
    static let yesilZemin = Color(red: 221 / 255, green: 240 / 255, blue: 231 / 255)
    static let amber = Color(red: 138 / 255, green: 90 / 255, blue: 0)
}

private struct KutuStil: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.foregroundColor(Tema.metin)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(configuration.isPressed ? Tema.cizgi : Tema.ozetZemin))
    }
}

private extension Text {
    func kalinDugme(_ zemin: Color, _ yazi: Color, kenar: Color? = nil) -> some View {
        self.font(.headline).foregroundColor(yazi).multilineTextAlignment(.center).frame(maxWidth: .infinity, minHeight: 56).padding(.horizontal, 12)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(zemin))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(kenar ?? zemin, lineWidth: 1))
    }
}
